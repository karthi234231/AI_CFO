-- V1: organizations (tenant boundary) and audit-friendly timestamps
--
-- Purpose: create the tenant root. Every other table in the schema carries an organization_id
--   foreign key, so this is the first migration by necessity - nothing can be created before the
--   table everything references exists.
-- Owning module: identity (com.fintech.cfo.identity). Cross-cutting tenant infrastructure rather
--   than a business module; V2 through V10 are the module schemas.
-- Design decisions:
--   * The primary key is a client-supplied UUID, not a sequence. Identifiers must be assignable
--     before a row is inserted so a calculation run, its result and the opportunity it produces can
--     all be named in the same transaction and cross-referenced by an audit trail written
--     afterwards. A sequence would make that impossible without a flush between each write.
--   * No foreign key to any other table in this migration, and no ON DELETE CASCADE: an
--     organization is the root of the tenant tree and deleting one is an explicit, deliberate
--     operation rather than a cascade triggered by something else.
--   * No NOT NULL on legal_name or registration_number because an organization may be created from
--     a lightweight onboarding flow before its legal identity is known. The rest of the schema is
--     strict about money and identity; identity onboarding is the one place where partial data is
--     legitimate.
--   * base_currency and timezone are per tenant, not global, because a single deployment serves
--     multiple tenants whose reporting periods and local dates differ. They are the defaults every
--     subsequent currency and date interpretation for that tenant falls back to.
--   * `active` exists as a flag rather than as a deletion, because a deactivated tenant's financial
--     records must remain readable and auditable; removing a tenant must never orphan them.
--   * version is the JPA @Version column: concurrent edits to the same organization (a rename, a
--     currency change) must not silently overwrite one another, and a lost update here would change
--     the reporting currency of an entire tenant.

CREATE TABLE organizations (
    id                  UUID PRIMARY KEY,
    name                VARCHAR(255) NOT NULL,
    legal_name          VARCHAR(255),
    registration_number VARCHAR(120),
    base_currency       CHAR(3)      NOT NULL DEFAULT 'INR',
    timezone            VARCHAR(64)  NOT NULL DEFAULT 'UTC',
    active              BOOLEAN      NOT NULL DEFAULT TRUE,
    -- TIMESTAMPTZ, not TIMESTAMP: a timestamp without a zone records an ambiguous instant. These
    -- are the columns every other table's audit pair is compared against.
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version             BIGINT       NOT NULL DEFAULT 0
);

-- Registration number is globally unique because it is a statutory identifier, not a tenant-local
-- one: the same company registering twice is a data-quality defect worth refusing. The predicate
-- `WHERE registration_number IS NOT NULL` is required - in PostgreSQL a unique index treats NULLs
-- as distinct, so without it the index would still allow any number of onboarding-incomplete
-- tenants while enforcing the rule only for those that have supplied one.
CREATE UNIQUE INDEX ux_organizations_registration_number
    ON organizations (registration_number) WHERE registration_number IS NOT NULL;

-- Serving the "which tenants are live" query on every authentication. Low cardinality and tiny
-- table, so this is about avoiding a sequential scan on a path that runs before any request work.
CREATE INDEX ix_organizations_active ON organizations (active);

-- Shared audit columns applied to every business table below.
-- created_by/updated_by hold the acting user id (UUID) for full traceability.
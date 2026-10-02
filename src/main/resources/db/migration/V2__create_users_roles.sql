-- V2: users, roles, permissions, memberships
--
-- Purpose: the identity and authorization schema. Turns the tenant root from V1 into an
--   enforceable boundary by making "which principals may act in which tenant with which rights"
--   a stored fact rather than a claim in a token.
-- Owning module: identity (com.fintech.cfo.identity).
-- Design decisions:
--   * The user is global, the membership is tenant-scoped. A CFO analyst who works for two
--     customers is one user with two memberships, not two user rows. Splitting them is what lets
--     an audit attribute an action to a person across tenants.
--   * Authorization is role-based with a separate permission catalogue, so a permission is defined
--     once and granted through roles rather than restated per user.
--   * role_permissions and memberships carry no `version` column: both are pure edges that are
--     replaced wholesale rather than mutated in place, so an optimistic-lock counter on them would
--     protect a concurrency case that cannot arise.

CREATE TABLE users (
    id             UUID PRIMARY KEY,
    email          VARCHAR(320) NOT NULL,
    display_name   VARCHAR(255) NOT NULL,
    -- Nullable because this system federates to an external OIDC issuer and does not hold
    -- passwords itself. The column exists so a locally-managed principal remains possible without a
    -- migration, and so an account is never indistinguishable from a federated one.
    password_hash  VARCHAR(255),
    status         VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    last_login_at  TIMESTAMPTZ,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version        BIGINT       NOT NULL DEFAULT 0
);

-- Uniqueness is case-insensitive via `lower(email)`. A plain unique index on email would permit
-- Alice@x.com and alice@x.com as separate principals, which is a duplicate-account takeover
-- waiting to happen; an expression index is the only way to express the rule without adding a
-- generated or normalized column. 320 is the RFC 5321 maximum length, so the constraint tests the
-- real limit rather than an arbitrary one.
CREATE UNIQUE INDEX ux_users_email ON users (lower(email));

-- Roles are data rather than a Java enum so a new role can be introduced by a deployment without a
-- code change and without a migration on every environment.
CREATE TABLE roles (
    id          UUID PRIMARY KEY,
    code        VARCHAR(64)  NOT NULL,
    name        VARCHAR(255) NOT NULL,
    description VARCHAR(1000),
    -- Marks the roles the application itself depends on (tenant admin, auditor). Such a role may be
    -- renamed in the UI but not deleted, so a deployment cannot quietly remove the ability to
    -- administer a tenant.
    system_role BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version     BIGINT       NOT NULL DEFAULT 0
);

-- The code is the authorization key that appears in policy expressions, so it must be unique and
-- stable; `name` is the display label and is free to change or repeat.
CREATE UNIQUE INDEX ux_roles_code ON roles (code);

-- The permission catalogue is global and tenant-independent by design. If permissions were
-- per-tenant, a code change to the enforcement logic would need a data migration in every tenant.
CREATE TABLE permissions (
    id          UUID PRIMARY KEY,
    code        VARCHAR(120) NOT NULL,
    description VARCHAR(500),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_permissions_code ON permissions (code);

-- Join table between roles and permissions. Both foreign keys cascade on delete: a permission or a
-- role that is removed must not leave a dangling grant behind, because a dangling grant is a row
-- that looks like an authorization and grants nothing.
CREATE TABLE role_permissions (
    role_id       UUID NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    permission_id UUID NOT NULL REFERENCES permissions (id) ON DELETE CASCADE,
    -- Composite primary key instead of a surrogate id: the pair is the identity, so this makes
    -- duplicate grants unrepresentable rather than merely discouraged.
    PRIMARY KEY (role_id, permission_id)
);

-- A user may hold several roles within one organization.
CREATE TABLE memberships (
    id              UUID PRIMARY KEY,
    -- ON DELETE CASCADE on both ends: deleting a tenant must remove its memberships, and deleting a
    -- user must remove them too. Leaving either would leave a membership row that grants access to
    -- a tenant that no longer exists.
    organization_id UUID        NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    user_id         UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- Deliberately no CASCADE: a role referenced by a membership must be retired explicitly, so
    -- access cannot be revoked by deleting the role definition and silently recreated later.
    role_id         UUID        NOT NULL REFERENCES roles (id),
    status          VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    version         BIGINT      NOT NULL DEFAULT 0
);

-- Membership is the tenant authorization boundary: one active membership per user+org+role.
-- The uniqueness is per (user, org, role) rather than per (user, org) precisely because a user may
-- hold several roles; making it per (user, org) would cap every principal at a single role. All
-- three columns appear because the uniqueness claim is about the combination.
CREATE UNIQUE INDEX ux_memberships_user_org_role ON memberships (user_id, organization_id, role_id);

-- Both indexes are tenant-scoped with status second, matching the two access questions exactly:
-- "who can act in this tenant" (per-request authorization) and "what does this principal have in
-- this tenant" (a user's own view). Leading with organization_id keeps every query inside one
-- tenant's slice of the table.
CREATE INDEX ix_memberships_org ON memberships (organization_id, status);
CREATE INDEX ix_memberships_user ON memberships (user_id, status);
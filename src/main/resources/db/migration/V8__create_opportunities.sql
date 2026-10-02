-- V8: Economic Opportunity Record (the central product object)
--
-- Purpose: the product's central object. Everything upstream exists to produce these rows, and
--   everything downstream - review, action, realization - hangs off them. This is the first
--   migration where the customer-facing concept, rather than the plumbing, drives the shape.
-- Owning module: opportunity (com.fintech.cfo.opportunity); the investigations table at the bottom
--   belongs to investigation (com.fintech.cfo.investigation) but is created here because it
--   depends only on opportunities and splitting it would buy nothing.
-- Design decisions:
--   * status (lifecycle) and validation_status (has a human confirmed it) are separate. A detected
--     opportunity can be OPEN while still PENDING validation, and conflating them would make "what
--     is in flight" unanswerable.
--   * impact is stored with explicit bounds. A range is a more honest statement than a point
--     estimate when the underlying sample is partial, and the bounds are what a reviewer uses to
--     judge whether the claim is worth pursuing.
--   * The child tables are append-only where they record history (reviews, lifecycle events) and
--     versioned where they record current state (assignments). A lifecycle event is history; an
--     assignment is a mutable claim on someone's attention.

CREATE TABLE opportunities (
    id                     UUID PRIMARY KEY,
    organization_id        UUID          NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    -- The human-facing identifier quoted in reviews and reports. It is separate from the UUID
    -- because the UUID is meaningless to read aloud, and this value is read aloud constantly.
    reference              VARCHAR(64)   NOT NULL,
    opportunity_type       VARCHAR(48)   NOT NULL,
    title                  VARCHAR(500)  NOT NULL,
    description            VARCHAR(4000),
    status                 VARCHAR(32)   NOT NULL,
    -- PENDING until a human accepts or rejects the detection. An opportunity is never presented as
    -- a fact on the strength of the calculation alone.
    validation_status      VARCHAR(32)   NOT NULL DEFAULT 'PENDING',
    priority               VARCHAR(16)   NOT NULL DEFAULT 'MEDIUM',
    confidence             VARCHAR(16)   NOT NULL DEFAULT 'HIGH',
    currency               CHAR(3)       NOT NULL,
    impact_amount          NUMERIC(20, 4) NOT NULL DEFAULT 0,
    -- The bounds are optional because not every opportunity type is quantified from a sample; where
    -- they are absent the point estimate is presented without a false claim of precision.
    impact_lower_bound     NUMERIC(20, 4),
    impact_upper_bound     NUMERIC(20, 4),
    affected_count         BIGINT        NOT NULL DEFAULT 0,
    -- Provenance back to the engine. Nullable rather than NOT NULL so an opportunity raised by hand
    -- from an investigation is a first-class row and not a second-class citizen.
    calculation_run_id     UUID          REFERENCES calculation_runs (id),
    primary_result_id      UUID          REFERENCES calculation_results (id),
    owner_id               UUID,
    -- Free-form narrative the detection or the reviewer supplies; TEXT because it holds structured
    -- JSON context in some flows and prose in others.
    business_context       TEXT,
    recommended_action     VARCHAR(2000),
    detected_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    validated_at           TIMESTAMPTZ,
    realized_at            TIMESTAMPTZ,
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version                BIGINT        NOT NULL DEFAULT 0
);

-- The reference a user quotes must identify exactly one opportunity within the tenant.
CREATE UNIQUE INDEX ux_opportunities_org_reference ON opportunities (organization_id, reference);

-- The main list view: this tenant's opportunities in a given status, most valuable first. The
-- descending amount is part of the index because the list is nearly always sorted by value, and
-- sorting after a fetch would be an unbounded sort.
CREATE INDEX ix_opportunities_org_status ON opportunities (organization_id, status, impact_amount DESC);

-- "My opportunities" across every status.
CREATE INDEX ix_opportunities_org_owner ON opportunities (organization_id, owner_id);

-- The review queue: pending items only, so the validation workload is a single index range scan.
CREATE INDEX ix_opportunities_validation ON opportunities (organization_id, validation_status);

-- The entity-level detail behind the headline figure. Kept in its own table rather than as a list
-- column because each row is independently addressable - evidence is attached to it, and a
-- transaction is cited by it - and because summing a set of amounts is what the figure above is.
CREATE TABLE opportunity_impacts (
    id               UUID PRIMARY KEY,
    organization_id  UUID          NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    opportunity_id   UUID          NOT NULL REFERENCES opportunities (id) ON DELETE CASCADE,
    -- Polymorphic: the affected entity may be a transaction, a customer or an invoice, and the
    -- opportunity is the aggregate that spans them. No foreign key, because the union of those
    -- types belongs to other modules.
    entity_type      VARCHAR(64)   NOT NULL,
    entity_id        UUID          NOT NULL,
    currency         CHAR(3)       NOT NULL,
    amount           NUMERIC(20, 4) NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- The contributions for one opportunity, which is how the aggregate is recomputed and explained.
CREATE INDEX ix_opportunity_impacts_opp ON opportunity_impacts (opportunity_id);

-- The reverse question: "which opportunities involve this transaction?" - asked during an
-- investigation, and not answerable from the index above.
CREATE INDEX ix_opportunity_impacts_entity ON opportunity_impacts (entity_type, entity_id);

-- The specific findings behind an opportunity. Append-only: a finding is a fact observed, and
-- changing one retroactively would make the record of why an opportunity was raised unknowable.
CREATE TABLE opportunity_findings (
    id               UUID PRIMARY KEY,
    organization_id  UUID        NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    opportunity_id   UUID        NOT NULL REFERENCES opportunities (id) ON DELETE CASCADE,
    finding_type     VARCHAR(48) NOT NULL,
    severity         VARCHAR(16) NOT NULL,
    title            VARCHAR(500) NOT NULL,
    detail           VARCHAR(2000),
    -- Which result raised this, so a challenge on the finding can reach the arithmetic behind it.
    calculation_result_id UUID   REFERENCES calculation_results (id),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Findings are read grouped by severity to present the most serious first, so severity is the
-- second key rather than a filter applied after the fetch.
CREATE INDEX ix_opportunity_findings_opp ON opportunity_findings (opportunity_id, severity);

-- Human decisions. Append-only, and deliberately never updated: if a decision is reversed, the
-- reversal is a new row. Collapsing them into one mutable row would erase the fact that the
-- opportunity's quality changed over time.
CREATE TABLE opportunity_reviews (
    id               UUID PRIMARY KEY,
    organization_id  UUID        NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    opportunity_id   UUID        NOT NULL REFERENCES opportunities (id) ON DELETE CASCADE,
    reviewer_id      UUID,
    decision         VARCHAR(24) NOT NULL,
    -- NOT NULL: a decision without its reasoning is not reviewable, and the reasoning is the part
    -- a later reader needs.
    rationale        VARCHAR(4000) NOT NULL,
    decided_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The review history, newest decision first.
CREATE INDEX ix_opportunity_reviews_opp ON opportunity_reviews (opportunity_id, decided_at DESC);

-- The status transition log. This is the audit trail for the opportunity's own lifecycle, and it
-- records the actor so "who approved this" is answerable without inferring it from audit_events.
CREATE TABLE opportunity_lifecycle_events (
    id               UUID PRIMARY KEY,
    organization_id  UUID        NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    opportunity_id   UUID        NOT NULL REFERENCES opportunities (id) ON DELETE CASCADE,
    -- Null on the creation event, where there is no prior state. Nullable is therefore a semantic
    -- distinction, not an oversight.
    from_status      VARCHAR(32),
    to_status        VARCHAR(32) NOT NULL,
    actor_id         UUID,
    note             VARCHAR(2000),
    occurred_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Reconstructs the timeline for one opportunity.
CREATE INDEX ix_opportunity_lifecycle_opp ON opportunity_lifecycle_events (opportunity_id, occurred_at);

-- The single owner of an opportunity. Unlike the review and event tables this row is versioned and
-- mutable, because reassignment replaces a claim rather than adding to a history.
CREATE TABLE opportunity_assignments (
    id               UUID PRIMARY KEY,
    organization_id  UUID        NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    opportunity_id   UUID        NOT NULL REFERENCES opportunities (id) ON DELETE CASCADE,
    assignee_id      UUID        NOT NULL,
    assigned_by      UUID,
    due_at           TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    version          BIGINT      NOT NULL DEFAULT 0
);

-- Uniqueness on opportunity_id alone is the point of this table: exactly one current assignee. If
-- the key included assignee_id, reassignment would insert a second row and "who owns this" would
-- have two answers. The previous owner survives in opportunity_lifecycle_events.
CREATE UNIQUE INDEX ux_opportunity_assignments_opp ON opportunity_assignments (opportunity_id);

-- The deep-dive that follows a disputed opportunity. Placed in this migration because it has no
-- dependency beyond opportunities, and separating it would only fragment the schema.
CREATE TABLE investigations (
    id               UUID PRIMARY KEY,
    organization_id  UUID        NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    -- Not unique per opportunity: an opportunity may be investigated more than once (a challenge
    -- reopening a rejected detection), and each investigation has its own reference, its own owner
    -- and its own resolution.
    opportunity_id   UUID        NOT NULL REFERENCES opportunities (id) ON DELETE CASCADE,
    reference        VARCHAR(64) NOT NULL,
    status           VARCHAR(32) NOT NULL,
    opened_by        UUID,
    assigned_to      UUID,
    summary          VARCHAR(2000),
    -- NULL while open. The column existing at all is what allows an investigation to be closed
    -- with no finding, which is a legitimate and common outcome.
    resolution       VARCHAR(4000),
    opened_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_at        TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    version          BIGINT       NOT NULL DEFAULT 0
);

-- Investigation reference is tenant-scoped for the same reason as the opportunity's.
CREATE UNIQUE INDEX ux_investigations_org_reference ON investigations (organization_id, reference);

-- The open-investigations list for an opportunity.
CREATE INDEX ix_investigations_opp ON investigations (opportunity_id, status);
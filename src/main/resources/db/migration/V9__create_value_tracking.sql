-- V9: action + realized-value tracking
-- Links a validated opportunity to the action taken and the money actually recovered.
--
-- Purpose: close the loop. V8 records what was found; this records what was done about it and
--   whether the money arrived. Without it the system has no answer to the only question a CFO
--   ultimately asks - did it pay for itself?
-- Owning module: value (com.fintech.cfo.value).
-- Design decisions:
--   * The chain is deliberately four distinct steps rather than one table: a plan is an intention, an
--     execution is the attempt, an outcome is the measurement, and a realized value is the money.
--     Collapsing them would make it impossible to record a plan that was never executed, or an
--     execution whose effect could not be measured - both are routine.
--   * Attributed amount is constrained non-negative. Every other amount column in the schema is
--     signed, because a reversal or a refund is a legitimate negative. Attribution is different: a
--     negative attribution has no meaning and is always an arithmetic error, so the database
--     refuses it rather than letting it reach a realized-value total.

CREATE TABLE action_plans (
    id                UUID PRIMARY KEY,
    organization_id   UUID        NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    opportunity_id    UUID        NOT NULL REFERENCES opportunities (id) ON DELETE CASCADE,
    title             VARCHAR(500) NOT NULL,
    description       VARCHAR(4000),
    action_type       VARCHAR(48) NOT NULL,
    -- Where the action is carried out (a billing system, a collections desk). Free text rather than
    -- a reference table, because the set of target systems is owned outside this system entirely.
    target_system     VARCHAR(128),
    status            VARCHAR(32) NOT NULL DEFAULT 'PLANNED',
    owner_id          UUID,
    due_at            TIMESTAMPTZ,
    -- What the action is expected to recover. The currency is nullable because a plan may be
    -- justified on grounds other than a recoverable amount - a compliance fix, for instance.
    expected_value    NUMERIC(20, 4),
    expected_currency CHAR(3),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    version           BIGINT      NOT NULL DEFAULT 0
);

-- The plans for an opportunity filtered by state - the worklist a reviewer actually walks.
CREATE INDEX ix_action_plans_opp ON action_plans (opportunity_id, status);

-- "My actions across the tenant", which is a person's queue rather than an opportunity's.
CREATE INDEX ix_action_plans_owner ON action_plans (organization_id, owner_id, status);

-- An attempt at carrying out a plan. A plan may be executed more than once (a partial recovery, a
-- retried credit note), so there is no uniqueness on action_plan_id.
CREATE TABLE action_executions (
    id               UUID PRIMARY KEY,
    organization_id  UUID        NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    action_plan_id   UUID        NOT NULL REFERENCES action_plans (id) ON DELETE CASCADE,
    status           VARCHAR(32) NOT NULL,
    executed_by      UUID,
    execution_note   VARCHAR(4000),
    -- NULL until it happens. Distinguishing "not yet executed" from "executed at this instant" is
    -- the difference between an open plan and a completed one, so the nullable timestamp is the
    -- record of that.
    executed_at      TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    version          BIGINT      NOT NULL DEFAULT 0
);

-- The execution history of a plan by state, used to decide whether the plan is done or whether it
-- needs another attempt.
CREATE INDEX ix_action_executions_plan ON action_executions (action_plan_id, status);

-- The measurement. measured_at is a DATE, not a TIMESTAMPTZ: a realized amount is measured at a
-- financial date, and an instant would invite the false precision of knowing the minute a ledger
-- posting landed.
CREATE TABLE outcomes (
    id                UUID PRIMARY KEY,
    organization_id   UUID          NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    opportunity_id    UUID          NOT NULL REFERENCES opportunities (id) ON DELETE CASCADE,
    -- Nullable: an outcome may be observed from the ledger without anyone having executed an
    -- action through this system. Requiring the link would make that measurement impossible to
    -- record, and it is a routine way to discover an organic recovery.
    action_execution_id UUID        REFERENCES action_executions (id),
    status            VARCHAR(32)   NOT NULL,
    outcome_type      VARCHAR(48)   NOT NULL,
    -- Defaults to zero rather than being nullable: an outcome that recovered nothing is a real,
    -- important result, and it must be recorded explicitly rather than inferred from absence.
    measured_amount   NUMERIC(20, 4) NOT NULL DEFAULT 0,
    measured_currency CHAR(3)       NOT NULL,
    -- How the figure was obtained (ledger extract, invoice audit). Kept so a reviewer can weigh a
    -- precisely measured outcome against an estimated one.
    measurement_method VARCHAR(64),
    measured_at       DATE          NOT NULL,
    notes             VARCHAR(4000),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version           BIGINT        NOT NULL DEFAULT 0
);

-- Outcome history for an opportunity, most recent measurement first.
CREATE INDEX ix_outcomes_opp ON outcomes (opportunity_id, measured_at DESC);

-- The tenant's own realized-value reporting, which spans opportunities.
CREATE INDEX ix_outcomes_org_date ON outcomes (organization_id, measured_at DESC);

-- The money itself, as distinct from the measurement of it. Split from outcomes because one
-- measurement can yield more than one realized amount - a single invoice settlement covering two
-- priced items, for instance - and one realized amount can also be recorded before any outcome
-- exists, from a bank reconciliation.
CREATE TABLE realized_values (
    id                UUID PRIMARY KEY,
    organization_id   UUID          NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    opportunity_id    UUID          NOT NULL REFERENCES opportunities (id) ON DELETE CASCADE,
    outcome_id        UUID          REFERENCES outcomes (id),
    realization_status VARCHAR(32)  NOT NULL,
    amount            NUMERIC(20, 4) NOT NULL,
    currency          CHAR(3)       NOT NULL,
    realized_at       DATE          NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version           BIGINT        NOT NULL DEFAULT 0
);

-- No non-negativity CHECK here, unlike value_attributions below: a clawback is a legitimate
-- negative realized value, and it must be recordable so the net figure stays correct.
CREATE INDEX ix_realized_values_opp ON realized_values (opportunity_id, realized_at DESC);

-- Attribution links a realized amount back to the opportunity that predicted it.
CREATE TABLE value_attributions (
    id                UUID PRIMARY KEY,
    organization_id   UUID          NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    opportunity_id    UUID          NOT NULL REFERENCES opportunities (id) ON DELETE CASCADE,
    -- Nullable for the same reason as outcomes.action_execution_id: value can be recognized without
    -- a prior measurement record.
    realized_value_id UUID          REFERENCES realized_values (id),
    attribution_method VARCHAR(32)  NOT NULL,
    -- NOT NULL DEFAULT-less: an attribution with no amount says nothing, and requiring the figure
    -- here is what keeps the opportunity's realized total a sum rather than a count.
    attributed_amount NUMERIC(20, 4) NOT NULL,
    currency          CHAR(3)       NOT NULL,
    confidence        VARCHAR(16)   NOT NULL DEFAULT 'HIGH',
    notes             VARCHAR(2000),
    attributed_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version           BIGINT       NOT NULL DEFAULT 0
);

-- The attribution history of an opportunity, which is what a realized-versus-expected comparison
-- reads.
CREATE INDEX ix_value_attributions_opp ON value_attributions (opportunity_id, attributed_at DESC);

-- The non-negativity rule. Attribution is the one money column where a negative value has no
-- interpretation: it would mean credit given back against a recovered amount, which belongs in
-- realized_values with a negative amount and a clawback status. Allowing it here would make every
-- opportunity total able to net off unrelated magnitudes. The constraint is added by ALTER to
-- mirror the policy-style additions in V6, so the rule reads as a schema-wide invariant rather than
-- as a property of one column.
ALTER TABLE value_attributions
    ADD CONSTRAINT ck_value_attributions_non_negative CHECK (attributed_amount >= 0);
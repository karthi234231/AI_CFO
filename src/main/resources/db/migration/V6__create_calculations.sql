-- V6: deterministic Financial Truth Engine
-- A calculation run pins the exact rule version + input snapshot so a result
-- can be reproduced bit-for-bit months later. All money is NUMERIC, never float.
--
-- Purpose: the comparison engine. Given what a transaction should have cost (from V5 terms) and
--   what it actually cost (from V4 records), produce a variance with an explanation that can be
--   defended later.
-- Owning module: financialtruth (com.fintech.cfo.financialtruth).
-- Design decisions:
--   * Reproducibility is a schema requirement, not a feature. rule_version and input_checksum on the
--     run pin what was evaluated; a result therefore stands alone, with no need for the inputs to
--     survive unchanged.
--   * expected, actual, variance and impact are stored as separate amount/currency pairs rather than
--     one netted figure. The two constraints at the bottom of this file enforce that a currency is
--     never attached to an amount that is not in it - the single most dangerous class of error in a
--     multi-currency financial system.
--   * entity_type/entity_id is a polymorphic reference, deliberately without a foreign key. The
--     engine calculates over invoices, lines and transactions alike, and a hard reference to one of
--     them would make the other two unrepresentable.

CREATE TABLE calculation_runs (
    id               UUID PRIMARY KEY,
    organization_id  UUID        NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    calculation_type VARCHAR(48) NOT NULL,
    status           VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    -- The version of the rule set that produced this run's results. Without it a result cannot be
    -- explained after the rules change, which is the question an auditor asks first.
    rule_version     VARCHAR(64) NOT NULL,
    period_start     DATE,
    period_end       DATE,
    -- SHA-256 over the canonical inputs. Two runs with the same checksum must produce the same
    -- results; the index below makes that check a lookup, so reproducibility is verifiable rather
    -- than asserted.
    input_checksum   CHAR(64)    NOT NULL,
    triggered_by     UUID,
    -- Link to the Spring Batch execution that performed the run, so a partial failure can be
    -- resumed and a result traced to the machinery that produced it.
    job_instance_id  BIGINT,
    started_at       TIMESTAMPTZ,
    completed_at     TIMESTAMPTZ,
    failure_reason   VARCHAR(2000),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    version          BIGINT      NOT NULL DEFAULT 0
);

-- The run list for a tenant, newest first - what an operator watches during a scheduled run.
CREATE INDEX ix_calculation_runs_org ON calculation_runs (organization_id, created_at DESC);

-- Serves the reproducibility check ("has this exact input been calculated before?"). Leading with
-- organization_id keeps the comparison inside one tenant, which is required for the check to be
-- meaningful: the same inputs for two tenants are different inputs.
CREATE INDEX ix_calculation_runs_checksum ON calculation_runs (organization_id, input_checksum);

-- One row per evaluated entity per run. Nothing here is updated after insert: a corrected
-- calculation is a new run, so the historical record of what was believed at a point in time is
-- never rewritten.
CREATE TABLE calculation_results (
    id                   UUID PRIMARY KEY,
    organization_id      UUID          NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    calculation_run_id   UUID          NOT NULL REFERENCES calculation_runs (id) ON DELETE CASCADE,
    -- Polymorphic subject of the calculation. No foreign key by design; see the header note.
    entity_type          VARCHAR(64)   NOT NULL,
    entity_id            UUID          NOT NULL,
    calculation_type     VARCHAR(48)   NOT NULL,
    -- The rule that fired and the version it was at, cited by the explanation so the user can see
    -- not merely that a variance exists but which clause produced it.
    rule_code            VARCHAR(64)   NOT NULL,
    rule_version         VARCHAR(64)   NOT NULL,
    expected_amount      NUMERIC(20, 4),
    expected_currency    CHAR(3),
    actual_amount        NUMERIC(20, 4),
    actual_currency      CHAR(3),
    -- Kept distinct from expected and actual rather than computed by subtraction, because the
    -- signed difference is itself an audited figure and must be visible in the record.
    variance_amount      NUMERIC(20, 4),
    variance_currency    CHAR(3),
    impact_amount        NUMERIC(20, 4),
    impact_currency      CHAR(3),
    variance_type        VARCHAR(32),
    confidence           VARCHAR(16)   NOT NULL DEFAULT 'HIGH',
    explanation          VARCHAR(2000),
    -- Free-form per-result detail, kept as TEXT because its shape varies by calculation type and a
    -- bounded column would truncate the very evidence that explains the number.
    details              TEXT,
    calculated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- Results are always read as a whole run; the per-row lookups that follow are all through the
-- entity, never by result id in a list.
CREATE INDEX ix_calculation_results_run ON calculation_results (calculation_run_id);

-- "Everything ever calculated about this invoice / customer / transaction", which is the query behind
-- an opportunity's affected-entity list and behind a reviewer's challenge.
CREATE INDEX ix_calculation_results_entity ON calculation_results (organization_id, entity_type, entity_id);

-- Guard: a calculation may never report a variance without its currency.
-- This is the schema's central safety property. An amount without a currency is an uninterpretable
-- number, and a variance that acquires one from a neighbouring row during a mapping change is
-- exactly how a figure from one tenant gets quoted in another tenant's currency. The predicate is
-- one-directional on purpose: a currency with no amount is harmless, so only the dangerous direction
-- is forbidden.
ALTER TABLE calculation_results
    ADD CONSTRAINT ck_calc_results_variance_currency
    CHECK (variance_amount IS NULL OR variance_currency IS NOT NULL);

-- The same guard for impact, which is aggregated across results and therefore more exposed to
-- picking up a currency by accident. Both constraints are added by ALTER rather than inline so that
-- the rule reads as a schema-wide policy, not as a property of one column.
ALTER TABLE calculation_results
    ADD CONSTRAINT ck_calc_results_impact_currency
    CHECK (impact_amount IS NULL OR impact_currency IS NOT NULL);
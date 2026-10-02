-- V10: audit trail + idempotency
-- Append-only operational audit. Rows are never updated or deleted.
--
-- Purpose: the last migration, because it depends on nothing and is depended on by nothing - it
--   observes every other table without being constrained by any of them. Placing it last also means
--   the business schema is complete and reviewable before the record of it is created.
-- Owning module: platform (com.fintech.cfo.platform).
-- Design decisions:
--   * No foreign keys to the tables being audited, deliberately. An audit trail must be able to
--     record events about entities that no longer exist, and must not be deleted when a business
--     record is. Referencing the business tables would make the trail the least durable thing in
--     the schema.
--   * Append-only, and idempotency_records is the only table in the schema with an update path,
--     because a request genuinely does transition from in-flight to completed.

CREATE TABLE audit_events (
    id               UUID PRIMARY KEY,
    -- Nullable and unconstrained: the audit trail must survive the deletion of the organization
    -- and of the actor, and must record events with no actor at all (a scheduled calculation).
    organization_id  UUID,
    actor_id         UUID,
    event_type       VARCHAR(64) NOT NULL,
    -- entity_type and entity_id are text and a UUID with no foreign key, so an event stays readable
    -- and complete forever, even about a deleted record.
    entity_type      VARCHAR(64) NOT NULL,
    entity_id        UUID,
    -- The join key back to the logs. This is the field that makes "it looked wrong on Tuesday"
    -- answerable: an id from a response body retrieves this row and every log line for the request.
    correlation_id   VARCHAR(64),
    request_id       VARCHAR(64),
    data_source      VARCHAR(64),
    calculation_run_id UUID,
    opportunity_id   UUID,
    -- 1000 characters, bounded so one verbose event cannot dominate the table's storage. Longer
    -- narrative belongs in details.
    outcome          VARCHAR(1000),
    -- TEXT for structured before/after context, whose size depends entirely on the record being
    -- described. It holds no customer payloads by convention, matching the log restriction.
    details          TEXT,
    -- 45 is the maximum length of a fully expanded IPv6 address, so the value is never truncated
    -- into a different address that still parses as valid.
    ip_address       VARCHAR(45),
    user_agent       VARCHAR(512),
    -- The one column that gives the table its order, and never updated: mutating it would break the
    -- append-only guarantee the whole audit design rests on.
    occurred_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Each index answers exactly one question the trail is actually asked. Leading columns are chosen
-- for selectivity, because a column appearing only late in the key cannot narrow the scan.

-- "Everything this tenant did in a period" - the compliance question, and the most frequent one.
CREATE INDEX ix_audit_events_org_time ON audit_events (organization_id, occurred_at DESC);

-- "What happened to this one record" - the support investigation. entity_type leads because a
-- record id is only unique within its type.
CREATE INDEX ix_audit_events_entity ON audit_events (entity_type, entity_id, occurred_at DESC);

-- "Everything belonging to this one request" - the log correlation lookup, and the reason
-- correlation_id is stored at all.
CREATE INDEX ix_audit_events_correlation ON audit_events (correlation_id);

-- "What did this person do" - an access review. Leading with actor_id keeps it off a full scan of a
-- table that only grows.
CREATE INDEX ix_audit_events_actor ON audit_events (actor_id, occurred_at DESC);

-- Idempotency state for a client-supplied key. This is the one mutable table in the schema: the row
-- is inserted in IN_PROGRESS and later transitions to COMPLETED, which is a real state change and
-- not a rewriting of history.
CREATE TABLE idempotency_records (
    id               UUID PRIMARY KEY,
    -- Uniqueness is global, not per tenant, and that is deliberate. A per-tenant key would let two
    -- organizations use the same string, and a bug in tenant scoping would then be invisible rather
    -- than caught by a constraint violation.
    idempotency_key  VARCHAR(255) NOT NULL,
    -- Nullable, so the mechanism still works for an endpoint that resolves a tenant from something
    -- other than the key itself.
    organization_id  UUID,
    -- SHA-256 of the request body. This is what makes the mechanism safe rather than merely
    -- convenient: without it a client that reused a key for a genuinely different request would be
    -- served the first response - reporting success for work that was never done.
    request_fingerprint CHAR(64)  NOT NULL,
    response_status  INT,
    -- Stored as the original text, not a parsed object, so a replay reproduces the original response
    -- byte for byte including its number formatting. Re-serialising could change it.
    response_body    TEXT,
    state            VARCHAR(32) NOT NULL DEFAULT 'IN_PROGRESS',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at       TIMESTAMPTZ NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Optimistic locking. Two concurrent requests carrying the same key both read version 0; only the
    -- first UPDATE succeeds, and the loser replays the winner's stored response instead of running
    -- the handler a second time. A BIGINT counter rather than a timestamp because it must change on
    -- every write, and two writes in the same instant would not.
    version          BIGINT      NOT NULL DEFAULT 0
);

-- Enforces the global uniqueness that the replay logic depends on. If this could be violated, a
-- replay would read an arbitrary one of several rows.
CREATE UNIQUE INDEX ux_idempotency_key ON idempotency_records (idempotency_key);

-- Expiry is swept in bulk on a schedule. Without this index every sweep is a sequential scan of a
-- table that only ever grows.
CREATE INDEX ix_idempotency_expiry ON idempotency_records (expires_at);
-- V3: secure ingestion - source files, runs, raw records, errors
-- Raw source data is preserved immutably: nothing overwrites an uploaded row.
--
-- Purpose: the entry point where untrusted data becomes trustworthy. Records what arrived, proves
--   what it was, and keeps the raw bytes so nothing downstream has to be trusted after the fact.
-- Owning module: ingestion (com.fintech.cfo.ingestion).
-- Design decisions:
--   * Nothing here is updated in place. A re-import produces new rows; corrections happen in the
--     canonical tables of V4, never by editing what the source said. That is what makes evidence
--     defensible: the system can always show what the supplier actually submitted.
--   * Every table carries organization_id directly rather than only a file reference. A period-wide
--     or run-wide query must never have to join through source_files to establish tenancy, because
--     a missed join is a cross-tenant leak.
--   * The same file may be uploaded to several tenants. The unique constraint below is therefore
--     scoped per organization, not global.

CREATE TABLE source_files (
    id                 UUID PRIMARY KEY,
    organization_id    UUID        NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    -- Stored as supplied by the client but never used as a path. The sanitised name is what
    -- FilenameSanitiser produces for storage; this column exists to show the operator what the
    -- upload claimed to be.
    original_filename  VARCHAR(512) NOT NULL,
    content_type       VARCHAR(255),
    -- Opaque storage key, not a filesystem path, so the backing store can change without a data
    -- migration and so no path-traversal consequence is possible from a stored value.
    storage_key        VARCHAR(1024) NOT NULL,
    size_bytes         BIGINT       NOT NULL,
    -- SHA-256 of the content, used for duplicate detection and to prove later that a referenced
    -- artifact has not changed since the result that relied on it.
    checksum_sha256    CHAR(64)     NOT NULL,
    -- The type the client declared, recorded even though it is not trusted: the pair of declared
    -- and detected type is itself the security signal, and a mismatch is what a disguised upload
    -- looks like.
    declared_file_type VARCHAR(32)  NOT NULL,
    detected_file_type VARCHAR(32),
    -- PENDING until the malware and archive checks have run. A file is never parsed while its
    -- security verdict is still PENDING, so an unscanned file cannot reach the parser.
    security_status    VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    -- No foreign key to users: the audit trail must survive the deletion of the principal who
    -- performed it, and an upload by a since-deleted service account must remain intelligible.
    uploaded_by        UUID,
    uploaded_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Content-addressed deduplication: the same bytes must not be ingested twice within a tenant, or
-- downstream canonical rows would be duplicated by an operation that looked idempotent. Scoped by
-- organization_id because the same ledger legitimately belongs to more than one tenant. The
-- predicate keeps rows without a checksum (never expected, but nullable in practice during a
-- partial write) out of the constraint.
CREATE UNIQUE INDEX ux_source_files_org_checksum ON source_files (organization_id, checksum_sha256);

-- Matches the upload list query "this tenant's files, newest first" without a sort.
CREATE INDEX ix_source_files_org ON source_files (organization_id, uploaded_at DESC);

-- A run is the unit of observability and retry for an upload. status is the operator-visible
-- outcome; stage is the finer position within the pipeline (upload, parse, validate, normalize,
-- persist) so a run stuck mid-pipeline says where.
CREATE TABLE ingestion_runs (
    id               UUID PRIMARY KEY,
    organization_id  UUID        NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    source_file_id   UUID        NOT NULL REFERENCES source_files (id) ON DELETE CASCADE,
    status           VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    stage            VARCHAR(32) NOT NULL DEFAULT 'UPLOAD',
    source_system    VARCHAR(64),
    -- Row counts are stored rather than recomputed: accepted + rejected = total is the invariant an
    -- operator checks first when a run looks wrong, and deriving it later means a second scan of
    -- a table that may since have been archived.
    total_rows       BIGINT,
    accepted_rows    BIGINT,
    rejected_rows    BIGINT,
    -- Link to the Spring Batch execution. Lets a failed run be resumed from the same job instance
    -- instead of restarting the file, which is the only way to reprocess a large upload without
    -- duplicating the rows an earlier attempt already accepted.
    job_instance_id  BIGINT,
    started_at       TIMESTAMPTZ,
    completed_at     TIMESTAMPTZ,
    -- Bounded at 2000 characters deliberately: a stack trace does not belong here, and a
    -- diagnostic that fits is readable in a status endpoint.
    failure_reason   VARCHAR(2000),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    version          BIGINT      NOT NULL DEFAULT 0
);

-- Both indexes are read far more often than written: the run list for a tenant, and the
-- "everything this file produced" trace used during a reprocess.
CREATE INDEX ix_ingestion_runs_org ON ingestion_runs (organization_id, created_at DESC);
CREATE INDEX ix_ingestion_runs_file ON ingestion_runs (source_file_id);

-- Raw parsed row preserved exactly as read, so evidence never depends on later normalization.
CREATE TABLE source_records (
    id               UUID PRIMARY KEY,
    organization_id  UUID        NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    source_file_id   UUID        NOT NULL REFERENCES source_files (id) ON DELETE CASCADE,
    ingestion_run_id UUID        NOT NULL REFERENCES ingestion_runs (id) ON DELETE CASCADE,
    -- Position in the file, 1-based, and the natural key for replay: reprocessing the same file
    -- replaces the row at the same position rather than appending a second copy of it.
    row_number       BIGINT      NOT NULL,
    -- The untransformed field values. Stored verbatim, including the original text of a number
    -- that later failed to parse, so a validation failure can be explained with the source's own
    -- representation instead of a guess.
    raw_payload      TEXT        NOT NULL,
    valid            BOOLEAN     NOT NULL DEFAULT TRUE,
    validation_errors TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Enforces one row per position per file. Without it, a retry that re-reads a file would double
    -- the row count and every total derived from it.
    UNIQUE (source_file_id, row_number)
);

-- Serves the two questions a run is judged by: how many rows did it reject, and which ones.
-- Leading with ingestion_run_id keeps the answer inside one run; valid second lets the common
-- "all failures" query avoid touching the accepted majority.
CREATE INDEX ix_source_records_run ON source_records (ingestion_run_id, valid);

-- Rejections are kept, not discarded. A row that failed validation is the evidence for the
-- decision to exclude it, and an operator disputing a total needs to see which rows were left out.
CREATE TABLE ingestion_errors (
    id               UUID PRIMARY KEY,
    organization_id  UUID        NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    ingestion_run_id UUID        NOT NULL REFERENCES ingestion_runs (id) ON DELETE CASCADE,
    error_type       VARCHAR(32) NOT NULL,
    severity         VARCHAR(16) NOT NULL,
    -- Nullable because a file-level failure (a corrupt archive) has no row to point at; row and
    -- field together locate the fault precisely when it does.
    row_number       BIGINT,
    field_name       VARCHAR(255),
    -- 2000 characters is enough for a specific, actionable message. A diagnostic that needs more
    -- than that is usually a stack trace, which belongs in the logs.
    message          VARCHAR(2000) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Severity second in the key because the dominant query is "show me this run's errors", usually
-- filtered to the blocking ones; an index that led with severity would not serve the unfiltered
-- form and would be far larger.
CREATE INDEX ix_ingestion_errors_run ON ingestion_errors (ingestion_run_id, severity);
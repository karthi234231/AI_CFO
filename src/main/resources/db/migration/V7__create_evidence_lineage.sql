-- V7: evidence + lineage
-- Every monetary result must be reconstructable:
-- Calculation -> affected transaction -> canonical record -> source row -> source file
--
-- Purpose: make the chain in that comment traversable. The truth engine produces a number; this
--   schema is what lets a reviewer walk from that number back to the supplier's own file.
-- Owning module: evidence (com.fintech.cfo.evidence).
-- Design decisions:
--   * Evidence is content-addressed. content_hash is SHA-256, and uniqueness is on the hash, so
--     "have we captured this artifact before" is a question the schema answers rather than an
--     assumption the service makes.
--   * Two representations of provenance, kept separate on purpose. evidence_references records a
--     direct, typed link between two objects; lineage_nodes/lineage_edges records a general graph
--     that can span tenants of types without a fixed vocabulary. Collapsing them would force every
--     reference to be described in the vocabulary of the graph.
--   * Subject columns are polymorphic (subject_type + subject_id, from_type + to_type). No foreign
--     key, because evidence is captured about entities across every module and a hard reference
--     would couple this schema to all of them.

CREATE TABLE evidence_snapshots (
    id              UUID PRIMARY KEY,
    organization_id UUID        NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    subject_type    VARCHAR(64) NOT NULL,
    subject_id      UUID        NOT NULL,
    content_type    VARCHAR(128) NOT NULL,
    -- The hash that makes the snapshot verifiable: it can be recomputed from content at any time to
    -- prove the stored text is what was captured, and unchanged.
    content_hash    CHAR(64)    NOT NULL,
    content         TEXT        NOT NULL,
    captured_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Re-capturing identical content for the same subject is a no-op, not a new row. This is what stops
-- a nightly job from growing the table without adding evidence, and it is scoped per tenant so two
-- tenants may legitimately hold the same content independently.
CREATE UNIQUE INDEX ux_evidence_snapshots_subject_hash
    ON evidence_snapshots (organization_id, subject_type, subject_id, content_hash);

-- Finds every snapshot of a subject regardless of content - the "show me everything we know about
-- this invoice" query, which cannot use the hash-leading index above.
CREATE INDEX ix_evidence_snapshots_subject ON evidence_snapshots (subject_type, subject_id);

-- A discrete artifact: a document, an image, a quoted clause. Distinct from a snapshot in that its
-- bytes live in object storage under storage_key rather than inline in a TEXT column.
CREATE TABLE evidences (
    id                   UUID PRIMARY KEY,
    organization_id      UUID        NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    evidence_type        VARCHAR(32) NOT NULL,
    title                VARCHAR(500) NOT NULL,
    description          VARCHAR(2000),
    -- No ON DELETE CASCADE: evidence must outlive the ingestion run that produced it, and certainly
    -- the file record itself. Retaining raw uploads cannot be allowed to withdraw the proof.
    source_file_id       UUID        REFERENCES source_files (id),
    -- The row within the file. This pair is what turns "the supplier's file" into "row 4,317",
    -- which is the level a disputing counterparty will insist on.
    source_row_number    BIGINT,
    storage_key          VARCHAR(1024),
    -- Hash of the stored bytes. Verified before the evidence is attached to a finding, so a
    -- corrupted or substituted object is detected at capture rather than at review.
    content_hash         CHAR(64)    NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    version              BIGINT      NOT NULL DEFAULT 0
);

-- The evidence list for a tenant, grouped by kind - the shape of the evidence panel in the UI.
CREATE INDEX ix_evidences_org ON evidences (organization_id, evidence_type);

-- Reverse lookup from a source file row to the evidence drawn from it, which is how a changed or
-- disputed source row is traced to every conclusion that relied on it.
CREATE INDEX ix_evidences_source_file ON evidences (source_file_id, source_row_number);

-- A typed edge between two arbitrary objects. No foreign keys on from/to by design: the graph must
-- be able to cross module boundaries, and a foreign key would let the deletion of an unrelated row
-- silently break a lineage chain that is part of an audit answer.
CREATE TABLE evidence_references (
    id               UUID PRIMARY KEY,
    organization_id  UUID        NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    evidence_id      UUID        NOT NULL REFERENCES evidences (id) ON DELETE CASCADE,
    from_type        VARCHAR(64) NOT NULL,
    from_id          UUID        NOT NULL,
    to_type          VARCHAR(64) NOT NULL,
    to_id            UUID        NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- An edge is identified by its endpoints, not by a surrogate id, so recording the same reference
-- twice is impossible. Note this index has no organization_id: the same edge must not exist twice
-- even if reached from two tenants' contexts, and endpoints are UUIDs so the key is already
-- unambiguous.
CREATE UNIQUE INDEX ux_evidence_references_edge
    ON evidence_references (from_type, from_id, to_type, to_id);

-- Reverse traversal. The forward index cannot serve it, and "what references this" is asked at
-- least as often as "what does this reference".
CREATE INDEX ix_evidence_references_reverse ON evidence_references (to_type, to_id);

-- General-purpose provenance graph. Kept separate from evidence_references because node and edge
-- identity here is the entity itself, so the graph can represent a derived object (a calculation, a
-- normalized record) that is not evidence in its own right.
CREATE TABLE lineage_nodes (
    id              UUID PRIMARY KEY,
    organization_id UUID        NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    node_type       VARCHAR(64) NOT NULL,
    node_id         UUID        NOT NULL,
    label           VARCHAR(500),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One node per entity per tenant, so a traversal that crosses module boundaries joins on a unique
-- key rather than fanning out across duplicate registrations of the same entity.
CREATE UNIQUE INDEX ux_lineage_nodes_node ON lineage_nodes (organization_id, node_type, node_id);

CREATE TABLE lineage_edges (
    id              UUID PRIMARY KEY,
    organization_id UUID        NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    -- Both endpoints cascade with the graph: deleting a node removes its edges rather than leaving
    -- edges that point at nothing, which would make a traversal silently incomplete.
    from_node_id    UUID        NOT NULL REFERENCES lineage_nodes (id) ON DELETE CASCADE,
    to_node_id      UUID        NOT NULL REFERENCES lineage_nodes (id) ON DELETE CASCADE,
    relation_type   VARCHAR(48) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- An edge is identified by endpoints plus relation, so re-running the lineage builder is idempotent
-- and the same pair may legitimately carry two different relation types.
CREATE UNIQUE INDEX ux_lineage_edges_edge
    ON lineage_edges (from_node_id, to_node_id, relation_type);

-- Downstream traversal. Lineage is read far more often backwards (what fed this?) than forwards,
-- and the unique index above leads with from_node_id so it cannot serve this.
CREATE INDEX ix_lineage_edges_to ON lineage_edges (to_node_id);
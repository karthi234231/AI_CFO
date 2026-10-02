# What each TODO file must become (read with its Test)

All 148 STUB + 24 SPEC grouped. SPEC = Javadoc IS the spec.

## Ingestion SPEC 1 + STUB 3
- controller/IngestionController SPEC: thin adapter over
  IngestionService+StatusService. POST multipart upload,
  GET status poll, GET errors. Tenant from principal,
  Idempotency-Key, never echo bytes. DTOs already REAL.
- repository 3 STUB: source_files/runs/errors CRUD +
  findByOrg + checksum unique.

## Financial SPEC 17
- controller 3, normalizer 4, repository 5, service 4:
  read each file's Javadoc top-to-bottom, it lists every
  rule (e.g. InvoiceNormalizer: keep reported vs
  recomputed separate, null dueDate stays null, mixed
  currency=>mismatch not convert, lines in lineNumber
  order). Implement exactly that. Tests:
  InvoiceNormalizationTest, FinancialDataServiceTest.

## Contract STUB 5
- 2 controllers + 3 repos. Mirror financial pattern.

## Opportunity 40 / Value 24 / Evidence 21 (STUB)
Chain: detect(|var|>=threshold AND conf HIGH/MED)
-> validate(zero/LOW/dupe reject) -> lifecycle state
machine (audit each) -> action -> outcome (amount<=opp,
needs evidenceId) -> attribution sum==total -> lineage
DAG edges (cycle reject). Tests: OpportunityDetection/
Lifecycle/ValidationTest, Action/Outcome/AttributionTest,
Evidence/LineageServiceTest define exact thresholds.

## AI 24 / Reporting 10 / Investigation 7 (STUB)
AI reads persisted run+opp only, guardrail: every output
Money exists in input, citations non-empty else BLOCKED.
Prompts must gain {{vars}} + do-not-invent. Reporting:
totals from FinancialImpact per currency. Investigation:
DAG walk source->row->result->opp->action->outcome.

## Identity 30 / Processing 8 (STUB) + 4 SPEC
Identity: OIDC JWT->Tenant RBAC. Processing ingestion
job 4 files are SPEC (read Javadoc). Tests:
IdentityServiceTest, TenantAccess/Auth tests.

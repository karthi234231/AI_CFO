# AI_CFO — Complete Project Explanation

> **What this file is**
> A single, self-contained walkthrough of this repository: the complete file tree, the goal of
> every file, the runtime *flow of journey* through each module, the *flow of implementation*
> (why the lines exist), and the reasoning behind the design. Sections 1–13 come from independent
> passes over the codebase; each one lists every file it owns and what that file is for.
>
> Every source file in `src/main`, `src/test`, `pom.xml`, the YAML/XML config and all ten Flyway
> migrations now carries in-file comments explaining the *why* of its lines.

---

## 0. What this project is

**AI_CFO** is a multi-tenant **financial intelligence platform**. It ingests a company's contracts
and transactional data (invoices, customers, products) from spreadsheets and source systems,
deterministically computes the *financial truth* for every invoice — the amount the company was
**entitled to** under its contract terms versus the amount it was **actually billed** — then turns
each variance into a trackable **economic opportunity** with an owner, an action plan, and a
measured realised outcome.

The value proposition is not "AI made a report". It is:

1. **Every number is deterministic and reproducible.** Financial truth is computed by plain Java
   (`BigDecimal`, explicit rounding, no `double`, no floating-point drift, injected clocks).
2. **Every number is traceable.** Each canonical record carries a `SourceReference` (source system,
   record id, file id, row number) and a checksum, so any figure can be walked back to the row of
   the spreadsheet that produced it.
3. **AI is fenced and can never produce a number.** The LLM layer may only *extract* contract terms
   and *explain* results that the deterministic engine already produced. Its output is validated,
   confidence-scored, and cross-checked against an allow-list; any number it invents is flagged
   `DISPUTED`. This is `docs/decisions/ADR-002-financial-truth-over-ai.md`.

### Technology

| | |
|---|---|
| Language | Java 25 |
| Framework | Spring Boot 4.1.1 |
| Persistence | Spring Data JPA + PostgreSQL, schema owned by Flyway (`V1`–`V10`) |
| Security | Spring Security (OAuth2 resource server / JWT) + tenant scoping |
| Batch | Spring Batch (`processing/**`) |
| Mapping | MapStruct 1.6.3, `unmappedTargetPolicy=ERROR` |
| Null safety | JSpecify `@NullMarked` / `@Nullable`, `ScopedValue` for tenant context |
| Parsing | Apache Commons CSV 1.14.1, Apache POI 5.4.1 (XLSX) |
| Documents | OpenPDF 2.0.3 |
| API docs | springdoc-openapi 3.1.0 (3.x is required for Boot 4) |
| Tests | JUnit 5, MockMvc, ArchUnit 1.4.1, Testcontainers (PostgreSQL) |

### Architecture at a glance

A **modular monolith** (ADR-001): one deployable Spring Boot application, but code is partitioned
into business modules that may only import `shared` and `platform` and the JDK/Spring — never each
other. Where two modules must exchange data, the boundary is a type owned by the *consumer* or a
port interface, and the wiring is deferred to an integration milestone. This is enforced
conventionally today (`module-boundaries.md`, ArchUnit on the test classpath) and it is what keeps
the financial logic auditable: you can read one module without reading eleven.

```
                    ┌──────────────────────────────────────────┐
   HTTP / JWT ────► │  platform: web · security · audit · …    │  cross-cutting
                    └───────────────┬──────────────────────────┘
                                    │
  ┌─────────┐   ┌────────────┐  ┌──┴─────────┐  ┌────────────┐  ┌───────────┐  ┌────────┐
  │identity │──►│ ingestion  │─►│ financial  │─►│ financial  │─►│  evidence │─►│  ...   │
  │ (tenant)│   │ (files)    │  │ (data)     │  │  truth     │  │ (lineage) │  │        │
  └─────────┘   └────────────┘  └──┬─────────┘  └──┬─────────┘  └───────────┘  └────────┘
                                    │               │
                             ┌──────┴───────┐  ┌────┴──────────┐   ┌──────────┐
                             │  contract    │─►│ opportunity   │──►│  value   │
                             │  (terms)     │  │  (lifecycle)  │   │(outcome) │
                             └──────┬───────┘  └───────────────┘   └────┬─────┘
                                    │                                    │
                             ┌──────┴───────┐                    ┌──────┴─────┐
                             │     ai       │                    │reporting / │
                             │ (extraction) │                    │processing  │
                             └──────────────┘                    └────────────┘

  shared — Money, CurrencyCode, OrganizationId, SourceReference, Preconditions, …
  platform — ApiResponse, GlobalExceptionHandler, filters, audit, idempotency, storage
```

### The end-to-end flow of journey

1. **Authenticate & scope.** A JWT arrives; the tenant context is bound for the request and every
   downstream query filters on `organization_id`. *(Section 2 — planned.)*
2. **Ingest.** A CSV/XLSX export is uploaded, malware-scanned, filename-sanitised, parsed,
   schema-validated row by row, and persisted in batches. Bad rows are rejected individually, not
   fatally. *(Section 3 — implemented.)*
3. **Normalise.** Customers, products and invoices are canonicalised and de-duplicated via
   entity-resolution keys. *(Section 4 — models implemented, services stubs.)*
4. **Resolve the contract.** Effective pricing and discount terms are extracted, versioned and
   selected by specificity + effective window. *(Section 6 — implemented.)*
5. **Compute financial truth.** Expected amount is derived from contract terms; actual amount from
   the invoice; the difference is classified into a `VarianceType` with a confidence score, using
   explicit `RoundingPolicy` at every step. *(Section 5 — implemented.)*
6. **Attach evidence.** Source references, immutable snapshots and content hashes are recorded, and
   lineage edges are written so any result can be walked back. *(Section 7 — model implemented.)*
7. **Raise an opportunity.** A significant variance becomes an `EconomicOpportunity` carrying the
   impact, the findings, the affected transactions and the next actions. It moves through
   `DRAFT → VALIDATED → UNDER_REVIEW → APPROVED/CHALLENGED → ASSIGNED → IN_PROGRESS → REALIZED/REJECTED`.
   *(Section 8 — state machine implemented, services stubs.)*
8. **Close the loop.** An approved opportunity becomes an action plan, an execution, a
   `RealizedValue`, and finally a recorded `Outcome` scored against what was promised. *(Section 9.)*
9. **Explain & report.** AI explains an already-computed opportunity; PDF reports are generated;
   batch jobs recalculate on a schedule. *(Sections 10, 11.)*

### Implementation status — read this before the sections

This repository is mid-build. 465 Java files; **310 are implemented and 155 are still
generator-produced placeholders** (a package declaration, a `TODO: Implement X` Javadoc and an empty
class body). Read the sections with that in mind: where a module is largely stubbed, its section
documents the *intended* design and says so explicitly rather than inventing a runtime.

| Module | Files | Implemented | Stub | Section |
|---|---:|---:|---:|---|
| `shared` | 24 | 24 | 0 | 1 |
| `platform` | 28 | 28 | 0 | 1 |
| `identity` | 30 | 0 | 30 | 2 |
| `ingestion` | 79 | 75 | 4 | 3 |
| `financial` | 42 | 26 | 16 | 4 |
| `financialtruth` | 50 | 46 | 4 | 5 |
| `contract` | 55 | 50 | 5 | 6 |
| `evidence` | 26 | 11 | 15 | 7 |
| `opportunity` | 40 | 18 | 22 | 8 |
| `value` | 24 | 1 | 23 | 9 |
| `ai` | 37 | 30 | 7 | 10 |
| `investigation` | 7 | 0 | 7 | 11 |
| `reporting` | 10 | 0 | 10 | 11 |
| `processing` | 12 | 0 | 12 | 11 |
| `CfoApplication` | 1 | 1 | 0 | 1 |
| **Total** | **465** | **310** | **155** | |

**Known build state.** `mvnw compile` currently fails with ~146 MapStruct errors in
`financial/mapper/**`: the `@Named` conversion methods on the `@MapperConfig FinancialMappingSupport`
are not resolving through `@Mapper(config = ...)`, and `InvoiceLineResponse` declares a `version`
property that `InvoiceLine` does not have. This breakage predates the documentation work and is
recorded, not fixed, in Sections 4 and 13. Testcontainers tests cannot be executed in this
environment (no Docker/Postgres).

---

## Complete file tree

`.git`, `target/`, `probe/` and `.kilo/worktrees/` are omitted. Worktree copies of the same source
are deliberately excluded.

```text

|-- .mvn
|   `-- wrapper
|       `-- maven-wrapper.properties
|-- docs
|   |-- architecture
|   |   |-- all phases final goal
|   |   |-- financial-truth.md
|   |   |-- flow of files
|   |   |-- module-boundaries.md
|   |   |-- module-implementation-rules.md
|   |   |-- phase-0-scope.md
|   |   |-- security-model.md
|   |   `-- system-architecture.md
|   |-- code-flow
|   |   |-- _TEMPLATE.md
|   |   |-- 00-how-to-read.md
|   |   |-- 00-overview.md
|   |   |-- 01-ingestion-financial.md
|   |   |-- 02-truth-contract.md
|   |   |-- 03-opportunity-value-evidence.md
|   |   |-- 04-ai-reporting.md
|   |   |-- 05-platform-shared-config.md
|   |   |-- 06-appendices.md
|   |   |-- 10-ingestion-deep.md
|   |   |-- 11-truth-deep.md
|   |   |-- 12-contract-kernel-deep.md
|   |   |-- 13-stub-roadmap.md
|   |   |-- placeholders.md
|   |   |-- README.md
|   |   `-- STATUS.md
|   |-- decisions
|   |   |-- ADR-001-modular-monolith.md
|   |   |-- ADR-002-financial-truth-over-ai.md
|   |   |-- ADR-003-economic-opportunity-record.md
|   |   `-- ADR-004-source-data-lineage.md
|   `-- explain
|       |-- 01-foundation-shared-platform.md
|       |-- 02-identity-tenancy-security.md
|       |-- 03-ingestion-file-pipeline.md
|       |-- 04-financial-data.md
|       |-- 05-financial-truth-engine.md
|       |-- 06-contracts-and-terms.md
|       |-- 07-evidence-and-lineage.md
|       |-- 08-opportunity-lifecycle.md
|       |-- 09-value-realization.md
|       |-- 10-ai-layer.md
|       |-- 11-investigation-reporting-processing.md
|       |-- 12-runtime-config-and-schema.md
|       `-- 13-tests-build-and-docs.md
|-- src
|   |-- main
|   |   |-- java
|   |   |   `-- com
|   |   |       `-- fintech
|   |   |           `-- cfo
|   |   |               |-- ai
|   |   |               |   |-- client
|   |   |               |   |   |-- DocumentExtractionClient.java
|   |   |               |   |   |-- DocumentExtractionPort.java
|   |   |               |   |   |-- EmbeddingClient.java
|   |   |               |   |   |-- EmbeddingPort.java
|   |   |               |   |   |-- LlmClient.java
|   |   |               |   |   |-- LlmPort.java
|   |   |               |   |   `-- package-info.java
|   |   |               |   |-- controller
|   |   |               |   |   |-- ContractInterpretationController.java
|   |   |               |   |   `-- ExplanationController.java
|   |   |               |   |-- dto
|   |   |               |   |   |-- ContractInterpretationResponse.java
|   |   |               |   |   |-- ExplainOpportunityRequest.java
|   |   |               |   |   |-- ExplanationResponse.java
|   |   |               |   |   |-- InterpretContractRequest.java
|   |   |               |   |   `-- package-info.java
|   |   |               |   |-- enums
|   |   |               |   |   |-- AiProcessingStatus.java
|   |   |               |   |   |-- AiTaskType.java
|   |   |               |   |   |-- AiValidationStatus.java
|   |   |               |   |   |-- CodedEnum.java
|   |   |               |   |   `-- package-info.java
|   |   |               |   |-- extraction
|   |   |               |   |   |-- ContractExtractionService.java
|   |   |               |   |   |-- ExtractionResult.java
|   |   |               |   |   |-- package-info.java
|   |   |               |   |   `-- StructuredExtractionValidator.java
|   |   |               |   |-- model
|   |   |               |   |   |-- AiAnalysis.java
|   |   |               |   |   |-- AiExplanation.java
|   |   |               |   |   |-- ExtractedCommercialTerm.java
|   |   |               |   |   |-- ExtractedDocument.java
|   |   |               |   |   |-- LlmMessage.java
|   |   |               |   |   |-- LlmResponse.java
|   |   |               |   |   `-- package-info.java
|   |   |               |   |-- pdf
|   |   |               |   |   |-- AiPdfService.java
|   |   |               |   |   `-- PdfExtractionService.java
|   |   |               |   `-- service
|   |   |               |       |-- AiContextService.java
|   |   |               |       |-- AiExplanationService.java
|   |   |               |       |-- AiGuardrailService.java
|   |   |               |       |-- ContractInterpretationService.java
|   |   |               |       `-- package-info.java
|   |   |               |-- contract
|   |   |               |   |-- controller
|   |   |               |   |   |-- ContractController.java
|   |   |               |   |   `-- ContractTermController.java
|   |   |               |   |-- dto
|   |   |               |   |   |-- CommercialEvaluationContext.java
|   |   |               |   |   |-- CommercialRuleEvaluation.java
|   |   |               |   |   |-- CommercialRuleResponse.java
|   |   |               |   |   |-- ContractResponse.java
|   |   |               |   |   |-- ContractTermResponse.java
|   |   |               |   |   |-- DiscountCapReason.java
|   |   |               |   |   |-- DiscountEvaluation.java
|   |   |               |   |   |-- DiscountTermResponse.java
|   |   |               |   |   |-- EffectiveTerms.java
|   |   |               |   |   |-- EffectiveTermsQuery.java
|   |   |               |   |   |-- package-info.java
|   |   |               |   |   |-- PricingTermResponse.java
|   |   |               |   |   |-- ResolvedPrice.java
|   |   |               |   |   `-- RuleEvaluationResult.java
|   |   |               |   |-- enums
|   |   |               |   |   |-- CodedEnum.java
|   |   |               |   |   |-- CommercialRuleType.java
|   |   |               |   |   |-- ContractStatus.java
|   |   |               |   |   |-- ContractTermType.java
|   |   |               |   |   |-- DiscountType.java
|   |   |               |   |   |-- package-info.java
|   |   |               |   |   |-- PricingType.java
|   |   |               |   |   `-- ReviewStatus.java
|   |   |               |   |-- extraction
|   |   |               |   |   |-- ContractTermExtractionService.java
|   |   |               |   |   |-- ContractTermExtractor.java
|   |   |               |   |   |-- ExtractedContractTerm.java
|   |   |               |   |   `-- package-info.java
|   |   |               |   |-- model
|   |   |               |   |   |-- CanonicalForm.java
|   |   |               |   |   |-- CanonicalText.java
|   |   |               |   |   |-- CommercialRule.java
|   |   |               |   |   |-- CommercialRuleParameters.java
|   |   |               |   |   |-- Contract.java
|   |   |               |   |   |-- ContractTerm.java
|   |   |               |   |   |-- DiscountTerm.java
|   |   |               |   |   |-- EffectiveWindow.java
|   |   |               |   |   |-- package-info.java
|   |   |               |   |   |-- PricingTerm.java
|   |   |               |   |   |-- RuleExpression.java
|   |   |               |   |   |-- ScopedTerm.java
|   |   |               |   |   `-- VersionedTerm.java
|   |   |               |   |-- repository
|   |   |               |   |   |-- CommercialRuleRepository.java
|   |   |               |   |   |-- ContractRepository.java
|   |   |               |   |   `-- ContractTermRepository.java
|   |   |               |   `-- service
|   |   |               |       |-- CommercialRuleService.java
|   |   |               |       |-- ContractService.java
|   |   |               |       |-- ContractTermService.java
|   |   |               |       |-- DiscountService.java
|   |   |               |       |-- EffectiveTermResolver.java
|   |   |               |       |-- EffectiveTermsService.java
|   |   |               |       |-- InputChecksum.java
|   |   |               |       |-- MonetaryScale.java
|   |   |               |       |-- package-info.java
|   |   |               |       |-- PriceResolutionService.java
|   |   |               |       `-- TermSelector.java
|   |   |               |-- evidence
|   |   |               |   |-- controller
|   |   |               |   |   |-- EvidenceController.java
|   |   |               |   |   `-- LineageController.java
|   |   |               |   |-- dto
|   |   |               |   |   |-- EvidenceDetailResponse.java
|   |   |               |   |   |-- EvidenceResponse.java
|   |   |               |   |   `-- LineageResponse.java
|   |   |               |   |-- enums
|   |   |               |   |   |-- CodedEnum.java
|   |   |               |   |   |-- EvidenceType.java
|   |   |               |   |   |-- LineageRelationType.java
|   |   |               |   |   |-- package-info.java
|   |   |               |   |   `-- SourceType.java
|   |   |               |   |-- model
|   |   |               |   |   |-- ContentHash.java
|   |   |               |   |   |-- Evidence.java
|   |   |               |   |   |-- EvidenceArtifact.java
|   |   |               |   |   |-- EvidenceReference.java
|   |   |               |   |   |-- EvidenceSnapshot.java
|   |   |               |   |   |-- LineageEdge.java
|   |   |               |   |   |-- LineageNode.java
|   |   |               |   |   |-- package-info.java
|   |   |               |   |   |-- SourceLocation.java
|   |   |               |   |   `-- SubjectRef.java
|   |   |               |   |-- repository
|   |   |               |   |   |-- EvidenceReferenceRepository.java
|   |   |               |   |   |-- EvidenceRepository.java
|   |   |               |   |   `-- LineageRepository.java
|   |   |               |   `-- service
|   |   |               |       |-- EvidenceService.java
|   |   |               |       |-- EvidenceSnapshotService.java
|   |   |               |       `-- LineageService.java
|   |   |               |-- financial
|   |   |               |   |-- controller
|   |   |               |   |   |-- CustomerController.java
|   |   |               |   |   |-- InvoiceController.java
|   |   |               |   |   `-- ProductController.java
|   |   |               |   |-- dto
|   |   |               |   |   |-- CustomerResponse.java
|   |   |               |   |   |-- InvoiceLineResponse.java
|   |   |               |   |   |-- InvoiceResponse.java
|   |   |               |   |   |-- ProductResponse.java
|   |   |               |   |   `-- TransactionResponse.java
|   |   |               |   |-- enums
|   |   |               |   |   |-- AccountingPeriodStatus.java
|   |   |               |   |   |-- InvoiceStatus.java
|   |   |               |   |   |-- package-info.java
|   |   |               |   |   |-- ReconciliationStatus.java
|   |   |               |   |   |-- SourceSystem.java
|   |   |               |   |   |-- SourceSystemFamily.java
|   |   |               |   |   `-- TransactionType.java
|   |   |               |   |-- mapper
|   |   |               |   |   |-- CustomerMapper.java
|   |   |               |   |   |-- FinancialMappingSupport.java
|   |   |               |   |   |-- InvoiceMapper.java
|   |   |               |   |   |-- ProductMapper.java
|   |   |               |   |   |-- SourceSystemMapper.java
|   |   |               |   |   `-- TransactionMapper.java
|   |   |               |   |-- model
|   |   |               |   |   |-- AccountingPeriod.java
|   |   |               |   |   |-- Customer.java
|   |   |               |   |   |-- FinancialTransaction.java
|   |   |               |   |   |-- Invoice.java
|   |   |               |   |   |-- InvoiceLine.java
|   |   |               |   |   |-- package-info.java
|   |   |               |   |   `-- Product.java
|   |   |               |   |-- normalization
|   |   |               |   |   |-- CustomerNormalizer.java
|   |   |               |   |   |-- EntityResolutionKey.java
|   |   |               |   |   |-- FinancialDataNormalizer.java
|   |   |               |   |   |-- InvoiceNormalizer.java
|   |   |               |   |   `-- ProductNormalizer.java
|   |   |               |   |-- repository
|   |   |               |   |   |-- CustomerRepository.java
|   |   |               |   |   |-- FinancialTransactionRepository.java
|   |   |               |   |   |-- InvoiceLineRepository.java
|   |   |               |   |   |-- InvoiceRepository.java
|   |   |               |   |   `-- ProductRepository.java
|   |   |               |   `-- service
|   |   |               |       |-- CustomerService.java
|   |   |               |       |-- FinancialDataService.java
|   |   |               |       |-- InvoiceService.java
|   |   |               |       `-- ProductService.java
|   |   |               |-- financialtruth
|   |   |               |   |-- calculator
|   |   |               |   |   |-- ActualAmountCalculator.java
|   |   |               |   |   |-- ExpectedAmountCalculator.java
|   |   |               |   |   |-- FinancialTruthEngine.java
|   |   |               |   |   |-- ImpactAggregator.java
|   |   |               |   |   |-- package-info.java
|   |   |               |   |   `-- VarianceCalculator.java
|   |   |               |   |-- controller
|   |   |               |   |   |-- CalculationController.java
|   |   |               |   |   `-- CalculationRunController.java
|   |   |               |   |-- dto
|   |   |               |   |   |-- AmountResponse.java
|   |   |               |   |   |-- CalculationResponse.java
|   |   |               |   |   |-- CalculationRunResponse.java
|   |   |               |   |   |-- FinancialImpactResponse.java
|   |   |               |   |   |-- package-info.java
|   |   |               |   |   |-- RunCalculationRequest.java
|   |   |               |   |   `-- VarianceResponse.java
|   |   |               |   |-- enums
|   |   |               |   |   |-- CalculationConfidence.java
|   |   |               |   |   |-- CalculationStatus.java
|   |   |               |   |   |-- CalculationType.java
|   |   |               |   |   |-- CodedEnum.java
|   |   |               |   |   |-- DiscountType.java
|   |   |               |   |   |-- ImpactDirection.java
|   |   |               |   |   |-- package-info.java
|   |   |               |   |   |-- PricingType.java
|   |   |               |   |   |-- RuleStatus.java
|   |   |               |   |   `-- VarianceType.java
|   |   |               |   |-- model
|   |   |               |   |   |-- ActualValue.java
|   |   |               |   |   |-- CalculationInput.java
|   |   |               |   |   |-- CalculationResult.java
|   |   |               |   |   |-- CalculationRun.java
|   |   |               |   |   |-- DiscountTerm.java
|   |   |               |   |   |-- ExpectedValue.java
|   |   |               |   |   |-- FinancialImpact.java
|   |   |               |   |   |-- InvoiceLineInput.java
|   |   |               |   |   |-- package-info.java
|   |   |               |   |   |-- PricingTerm.java
|   |   |               |   |   |-- RoundingPolicy.java
|   |   |               |   |   |-- TermEvaluation.java
|   |   |               |   |   `-- Variance.java
|   |   |               |   |-- repository
|   |   |               |   |   |-- CalculationResultRepository.java
|   |   |               |   |   `-- CalculationRunRepository.java
|   |   |               |   |-- rules
|   |   |               |   |   |-- DiscountVarianceRule.java
|   |   |               |   |   |-- FinancialRule.java
|   |   |               |   |   |-- package-info.java
|   |   |               |   |   |-- PricingVarianceRule.java
|   |   |               |   |   |-- RuleContext.java
|   |   |               |   |   `-- RuleEvaluationResult.java
|   |   |               |   `-- service
|   |   |               |       |-- CalculationRunService.java
|   |   |               |       |-- CalculationService.java
|   |   |               |       |-- package-info.java
|   |   |               |       `-- ReproducibilityService.java
|   |   |               |-- identity
|   |   |               |   |-- controller
|   |   |               |   |   |-- OrganizationController.java
|   |   |               |   |   |-- RoleController.java
|   |   |               |   |   `-- UserController.java
|   |   |               |   |-- dto
|   |   |               |   |   |-- OrganizationResponse.java
|   |   |               |   |   |-- PermissionResponse.java
|   |   |               |   |   |-- RoleResponse.java
|   |   |               |   |   `-- UserResponse.java
|   |   |               |   |-- enums
|   |   |               |   |   |-- PermissionType.java
|   |   |               |   |   |-- RoleType.java
|   |   |               |   |   `-- UserStatus.java
|   |   |               |   |-- model
|   |   |               |   |   |-- Membership.java
|   |   |               |   |   |-- Organization.java
|   |   |               |   |   |-- Permission.java
|   |   |               |   |   |-- Role.java
|   |   |               |   |   `-- User.java
|   |   |               |   |-- repository
|   |   |               |   |   |-- MembershipRepository.java
|   |   |               |   |   |-- OrganizationRepository.java
|   |   |               |   |   |-- RoleRepository.java
|   |   |               |   |   `-- UserRepository.java
|   |   |               |   |-- security
|   |   |               |   |   |-- CurrentUser.java
|   |   |               |   |   |-- CurrentUserProvider.java
|   |   |               |   |   |-- JwtAuthenticationConverter.java
|   |   |               |   |   |-- SecurityConfig.java
|   |   |               |   |   |-- SecurityHeadersConfig.java
|   |   |               |   |   |-- TenantContext.java
|   |   |               |   |   `-- TenantContextFilter.java
|   |   |               |   `-- service
|   |   |               |       |-- AuthorizationService.java
|   |   |               |       |-- OrganizationService.java
|   |   |               |       |-- TenantAccessService.java
|   |   |               |       `-- UserService.java
|   |   |               |-- ingestion
|   |   |               |   |-- controller
|   |   |               |   |   `-- IngestionController.java
|   |   |               |   |-- dto
|   |   |               |   |   |-- IngestionErrorResponse.java
|   |   |               |   |   |-- IngestionResponse.java
|   |   |               |   |   |-- IngestionStatusResponse.java
|   |   |               |   |   |-- package-info.java
|   |   |               |   |   |-- StartIngestionRequest.java
|   |   |               |   |   `-- UploadedFileResponse.java
|   |   |               |   |-- enums
|   |   |               |   |   |-- ColumnType.java
|   |   |               |   |   |-- FieldType.java
|   |   |               |   |   |-- FileSecurityStatus.java
|   |   |               |   |   |-- FileType.java
|   |   |               |   |   |-- HeaderMode.java
|   |   |               |   |   |-- IngestionErrorType.java
|   |   |               |   |   |-- IngestionStage.java
|   |   |               |   |   |-- IngestionStatus.java
|   |   |               |   |   |-- package-info.java
|   |   |               |   |   |-- ParseStatus.java
|   |   |               |   |   |-- RejectionReason.java
|   |   |               |   |   |-- RowStatus.java
|   |   |               |   |   `-- ValidationSeverity.java
|   |   |               |   |-- model
|   |   |               |   |   |-- ColumnSchema.java
|   |   |               |   |   |-- FileChecksum.java
|   |   |               |   |   |-- FileParseResult.java
|   |   |               |   |   |-- FileRejection.java
|   |   |               |   |   |-- FileSecurityResult.java
|   |   |               |   |   |-- IngestionError.java
|   |   |               |   |   |-- IngestionLimits.java
|   |   |               |   |   |-- IngestionProcessingResult.java
|   |   |               |   |   |-- IngestionRequest.java
|   |   |               |   |   |-- IngestionRun.java
|   |   |               |   |   |-- IngestionSchema.java
|   |   |               |   |   |-- package-info.java
|   |   |               |   |   |-- ParsedCell.java
|   |   |               |   |   |-- ParsedRow.java
|   |   |               |   |   |-- RejectedRow.java
|   |   |               |   |   |-- Rejection.java
|   |   |               |   |   |-- RowCoordinate.java
|   |   |               |   |   |-- SanitisedFilename.java
|   |   |               |   |   |-- SourceFile.java
|   |   |               |   |   |-- SourceRecord.java
|   |   |               |   |   |-- UploadMetadata.java
|   |   |               |   |   |-- ValidationFinding.java
|   |   |               |   |   `-- ValidationResult.java
|   |   |               |   |-- parser
|   |   |               |   |   |-- ArchiveGuard.java
|   |   |               |   |   |-- CsvFileParser.java
|   |   |               |   |   |-- CsvParseOptions.java
|   |   |               |   |   |-- ExcelCellReader.java
|   |   |               |   |   |-- ExcelFileParser.java
|   |   |               |   |   |-- ExcelParseOptions.java
|   |   |               |   |   |-- FileParser.java
|   |   |               |   |   |-- HeaderDetector.java
|   |   |               |   |   |-- package-info.java
|   |   |               |   |   |-- ParseOutcome.java
|   |   |               |   |   |-- ParseRequest.java
|   |   |               |   |   `-- TextDecoding.java
|   |   |               |   |-- repository
|   |   |               |   |   |-- IngestionErrorRepository.java
|   |   |               |   |   |-- IngestionRepository.java
|   |   |               |   |   `-- SourceFileRepository.java
|   |   |               |   |-- security
|   |   |               |   |   |-- FilenameSanitiser.java
|   |   |               |   |   |-- FileUploadSecurityService.java
|   |   |               |   |   |-- MalwareScanService.java
|   |   |               |   |   |-- package-info.java
|   |   |               |   |   `-- UploadAuthorizationService.java
|   |   |               |   |-- service
|   |   |               |   |   |-- FileSecurityService.java
|   |   |               |   |   |-- FileValidationService.java
|   |   |               |   |   |-- IngestionOrchestrator.java
|   |   |               |   |   |-- IngestionService.java
|   |   |               |   |   |-- IngestionStatusService.java
|   |   |               |   |   `-- package-info.java
|   |   |               |   `-- validator
|   |   |               |       |-- ContentSniffer.java
|   |   |               |       |-- DataQualityValidator.java
|   |   |               |       |-- DataTypeValidator.java
|   |   |               |       |-- DuplicateValidator.java
|   |   |               |       |-- FormulaInjectionSanitiser.java
|   |   |               |       |-- package-info.java
|   |   |               |       |-- RequiredFieldValidator.java
|   |   |               |       |-- SchemaValidator.java
|   |   |               |       |-- TypedValueParser.java
|   |   |               |       `-- UploadFileValidator.java
|   |   |               |-- investigation
|   |   |               |   |-- controller
|   |   |               |   |   `-- InvestigationController.java
|   |   |               |   |-- dto
|   |   |               |   |   |-- CreateInvestigationRequest.java
|   |   |               |   |   `-- InvestigationResponse.java
|   |   |               |   |-- enums
|   |   |               |   |   `-- InvestigationStatus.java
|   |   |               |   |-- model
|   |   |               |   |   `-- Investigation.java
|   |   |               |   |-- repository
|   |   |               |   |   `-- InvestigationRepository.java
|   |   |               |   `-- service
|   |   |               |       `-- InvestigationService.java
|   |   |               |-- opportunity
|   |   |               |   |-- controller
|   |   |               |   |   |-- OpportunityAssignmentController.java
|   |   |               |   |   |-- OpportunityController.java
|   |   |               |   |   `-- OpportunityReviewController.java
|   |   |               |   |-- dto
|   |   |               |   |   |-- AssignOpportunityRequest.java
|   |   |               |   |   |-- ChallengeOpportunityRequest.java
|   |   |               |   |   |-- OpportunityDetailResponse.java
|   |   |               |   |   |-- OpportunityResponse.java
|   |   |               |   |   |-- OpportunitySummaryResponse.java
|   |   |               |   |   |-- RejectOpportunityRequest.java
|   |   |               |   |   `-- ValidateOpportunityRequest.java
|   |   |               |   |-- enums
|   |   |               |   |   |-- CodedEnum.java
|   |   |               |   |   |-- FindingSeverity.java
|   |   |               |   |   |-- FindingType.java
|   |   |               |   |   |-- OpportunityConfidence.java
|   |   |               |   |   |-- OpportunityPriority.java
|   |   |               |   |   |-- OpportunityStatus.java
|   |   |               |   |   |-- OpportunityType.java
|   |   |               |   |   |-- package-info.java
|   |   |               |   |   |-- ReviewDecision.java
|   |   |               |   |   `-- ValidationStatus.java
|   |   |               |   |-- model
|   |   |               |   |   |-- AffectedTransactionRef.java
|   |   |               |   |   |-- CalculationReference.java
|   |   |               |   |   |-- EconomicOpportunity.java
|   |   |               |   |   |-- EvidenceReference.java
|   |   |               |   |   |-- FindingDraft.java
|   |   |               |   |   |-- NextAction.java
|   |   |               |   |   |-- OpportunityAssignment.java
|   |   |               |   |   |-- OpportunityFinding.java
|   |   |               |   |   |-- OpportunityImpact.java
|   |   |               |   |   |-- OpportunityLifecycleEvent.java
|   |   |               |   |   |-- OpportunityReview.java
|   |   |               |   |   `-- package-info.java
|   |   |               |   |-- repository
|   |   |               |   |   |-- OpportunityLifecycleRepository.java
|   |   |               |   |   |-- OpportunityRepository.java
|   |   |               |   |   `-- OpportunityReviewRepository.java
|   |   |               |   `-- service
|   |   |               |       |-- OpportunityDetectionService.java
|   |   |               |       |-- OpportunityLifecycleService.java
|   |   |               |       |-- OpportunityReviewService.java
|   |   |               |       |-- OpportunityService.java
|   |   |               |       `-- OpportunityValidationService.java
|   |   |               |-- platform
|   |   |               |   |-- audit
|   |   |               |   |   |-- AuditEvent.java
|   |   |               |   |   |-- AuditEventEntity.java
|   |   |               |   |   |-- AuditEventJpaRepository.java
|   |   |               |   |   |-- AuditEventType.java
|   |   |               |   |   |-- AuditRepository.java
|   |   |               |   |   `-- AuditService.java
|   |   |               |   |-- config
|   |   |               |   |   |-- ApplicationProperties.java
|   |   |               |   |   |-- AsyncConfig.java
|   |   |               |   |   |-- JacksonConfig.java
|   |   |               |   |   |-- OpenApiConfig.java
|   |   |               |   |   `-- TransactionConfig.java
|   |   |               |   |-- idempotency
|   |   |               |   |   |-- IdempotencyFilter.java
|   |   |               |   |   |-- IdempotencyRecord.java
|   |   |               |   |   |-- IdempotencyRepository.java
|   |   |               |   |   `-- IdempotencyService.java
|   |   |               |   |-- observability
|   |   |               |   |   |-- BusinessMetrics.java
|   |   |               |   |   |-- MetricsConfiguration.java
|   |   |               |   |   `-- TracingConfiguration.java
|   |   |               |   |-- persistence
|   |   |               |   |   |-- JpaConfiguration.java
|   |   |               |   |   `-- PersistenceAuditListener.java
|   |   |               |   |-- storage
|   |   |               |   |   |-- ObjectStoragePort.java
|   |   |               |   |   |-- ObjectStorageService.java
|   |   |               |   |   `-- StorageObject.java
|   |   |               |   `-- web
|   |   |               |       |-- ApiErrorResponse.java
|   |   |               |       |-- ApiResponse.java
|   |   |               |       |-- CorrelationIdFilter.java
|   |   |               |       |-- GlobalExceptionHandler.java
|   |   |               |       `-- RequestLoggingFilter.java
|   |   |               |-- processing
|   |   |               |   |-- common
|   |   |               |   |   |-- JobExecutionService.java
|   |   |               |   |   `-- JobFailureHandler.java
|   |   |               |   |-- financialtruth
|   |   |               |   |   |-- CalculationJobConfiguration.java
|   |   |               |   |   |-- CalculationJobLauncher.java
|   |   |               |   |   |-- CalculationProcessor.java
|   |   |               |   |   `-- CalculationWriter.java
|   |   |               |   |-- ingestion
|   |   |               |   |   |-- IngestionJobConfiguration.java
|   |   |               |   |   |-- IngestionJobLauncher.java
|   |   |               |   |   |-- IngestionProcessor.java
|   |   |               |   |   `-- IngestionWriter.java
|   |   |               |   `-- reporting
|   |   |               |       |-- ReportJobConfiguration.java
|   |   |               |       `-- ReportJobLauncher.java
|   |   |               |-- reporting
|   |   |               |   |-- controller
|   |   |               |   |   `-- ReportController.java
|   |   |               |   |-- dto
|   |   |               |   |   |-- GenerateOpportunityReportRequest.java
|   |   |               |   |   `-- ReportResponse.java
|   |   |               |   |-- enums
|   |   |               |   |   `-- ReportType.java
|   |   |               |   |-- model
|   |   |               |   |   |-- Report.java
|   |   |               |   |   `-- ReportArtifact.java
|   |   |               |   |-- pdf
|   |   |               |   |   |-- PdfReportGenerator.java
|   |   |               |   |   `-- PdfTemplateService.java
|   |   |               |   `-- service
|   |   |               |       |-- OpportunityReportService.java
|   |   |               |       `-- ReportService.java
|   |   |               |-- shared
|   |   |               |   |-- domain
|   |   |               |   |   |-- CurrencyCode.java
|   |   |               |   |   |-- DateRange.java
|   |   |               |   |   |-- Money.java
|   |   |               |   |   |-- OrganizationId.java
|   |   |               |   |   |-- SourceReference.java
|   |   |               |   |   |-- TenantId.java
|   |   |               |   |   |-- UserId.java
|   |   |               |   |   `-- VersionedValue.java
|   |   |               |   |-- enums
|   |   |               |   |   |-- Currency.java
|   |   |               |   |   |-- ProcessingStatus.java
|   |   |               |   |   `-- Status.java
|   |   |               |   |-- exception
|   |   |               |   |   |-- AccessDeniedException.java
|   |   |               |   |   |-- BusinessRuleException.java
|   |   |               |   |   |-- ConflictException.java
|   |   |               |   |   |-- DomainException.java
|   |   |               |   |   |-- NotFoundException.java
|   |   |               |   |   `-- ValidationException.java
|   |   |               |   |-- security
|   |   |               |   |   |-- SecurityContext.java
|   |   |               |   |   `-- SecurityPrincipal.java
|   |   |               |   |-- util
|   |   |               |   |   |-- DateTimeUtils.java
|   |   |               |   |   |-- HashUtils.java
|   |   |               |   |   `-- IdGenerator.java
|   |   |               |   `-- validation
|   |   |               |       |-- package-info.java
|   |   |               |       `-- Preconditions.java
|   |   |               |-- value
|   |   |               |   |-- controller
|   |   |               |   |   |-- ActionController.java
|   |   |               |   |   `-- OutcomeController.java
|   |   |               |   |-- dto
|   |   |               |   |   |-- ActionResponse.java
|   |   |               |   |   |-- CreateActionRequest.java
|   |   |               |   |   |-- OutcomeResponse.java
|   |   |               |   |   |-- RealizedValueResponse.java
|   |   |               |   |   `-- RecordOutcomeRequest.java
|   |   |               |   |-- enums
|   |   |               |   |   |-- ActionStatus.java
|   |   |               |   |   |-- AttributionMethod.java
|   |   |               |   |   |-- CodedEnum.java
|   |   |               |   |   |-- OutcomeStatus.java
|   |   |               |   |   `-- RealizationStatus.java
|   |   |               |   |-- model
|   |   |               |   |   |-- ActionExecution.java
|   |   |               |   |   |-- ActionPlan.java
|   |   |               |   |   |-- Outcome.java
|   |   |               |   |   |-- RealizedValue.java
|   |   |               |   |   `-- ValueAttribution.java
|   |   |               |   |-- repository
|   |   |               |   |   |-- ActionRepository.java
|   |   |               |   |   |-- OutcomeRepository.java
|   |   |               |   |   `-- RealizedValueRepository.java
|   |   |               |   `-- service
|   |   |               |       |-- ActionService.java
|   |   |               |       |-- OutcomeService.java
|   |   |               |       |-- RealizedValueService.java
|   |   |               |       `-- ValueAttributionService.java
|   |   |               `-- CfoApplication.java
|   |   `-- resources
|   |       |-- db
|   |       |   `-- migration
|   |       |       |-- V1__create_organizations.sql
|   |       |       |-- V10__create_audit.sql
|   |       |       |-- V2__create_users_roles.sql
|   |       |       |-- V3__create_ingestion.sql
|   |       |       |-- V4__create_financial_data.sql
|   |       |       |-- V5__create_contracts.sql
|   |       |       |-- V6__create_calculations.sql
|   |       |       |-- V7__create_evidence_lineage.sql
|   |       |       |-- V8__create_opportunities.sql
|   |       |       `-- V9__create_value_tracking.sql
|   |       |-- prompts
|   |       |   |-- contract-term-extraction.txt
|   |       |   `-- opportunity-explanation.txt
|   |       |-- static
|   |       |-- templates
|   |       |-- application.properties
|   |       |-- application.yml
|   |       |-- application-dev.yml
|   |       |-- application-prod.yml
|   |       |-- application-test.yml
|   |       `-- logback-spring.xml
|   `-- test
|       |-- java
|       |   `-- com
|       |       `-- fintech
|       |           `-- cfo
|       |               |-- api
|       |               |   |-- CalculationControllerTest.java
|       |               |   |-- IngestionControllerTest.java
|       |               |   `-- OpportunityControllerTest.java
|       |               |-- architecture
|       |               |   |-- DependencyRuleTest.java
|       |               |   `-- ModuleBoundaryTest.java
|       |               |-- contract
|       |               |   |-- CommercialRuleServiceTest.java
|       |               |   `-- ContractServiceTest.java
|       |               |-- evidence
|       |               |   |-- EvidenceServiceTest.java
|       |               |   `-- LineageServiceTest.java
|       |               |-- financial
|       |               |   |-- FinancialDataServiceTest.java
|       |               |   `-- InvoiceNormalizationTest.java
|       |               |-- financialtruth
|       |               |   |-- CalculationReproducibilityTest.java
|       |               |   |-- DiscountVarianceRuleTest.java
|       |               |   |-- FinancialRegressionTest.java
|       |               |   |-- FinancialTruthEngineTest.java
|       |               |   |-- MoneyTest.java
|       |               |   |-- PricingVarianceRuleTest.java
|       |               |   `-- TruthEngineFixtures.java
|       |               |-- identity
|       |               |   |-- IdentityServiceTest.java
|       |               |   `-- TenantAccessServiceTest.java
|       |               |-- ingestion
|       |               |   |-- CsvFileParserTest.java
|       |               |   |-- ExcelFileParserTest.java
|       |               |   |-- FileValidationServiceTest.java
|       |               |   `-- IngestionServiceTest.java
|       |               |-- investigation
|       |               |-- opportunity
|       |               |   |-- OpportunityDetectionTest.java
|       |               |   |-- OpportunityLifecycleTest.java
|       |               |   `-- OpportunityValidationTest.java
|       |               |-- platform
|       |               |-- reporting
|       |               |-- security
|       |               |   |-- AuthenticationTest.java
|       |               |   |-- AuthorizationTest.java
|       |               |   |-- FileUploadSecurityTest.java
|       |               |   `-- TenantIsolationTest.java
|       |               |-- shared
|       |               |-- value
|       |               |   |-- ActionServiceTest.java
|       |               |   |-- OutcomeServiceTest.java
|       |               |   `-- ValueAttributionTest.java
|       |               |-- CfoApplicationTests.java
|       |               |-- TestCfoApplication.java
|       |               `-- TestcontainersConfiguration.java
|       `-- resources
|-- .gitattributes
|-- .gitignore
|-- HELP.md
|-- mvnw
|-- mvnw.cmd
|-- pom.xml
|-- README.md
|-- tc2.log
`-- test-compile-current.log


```

---

## The cross-cutting rules every file obeys

These are recorded in `docs/architecture/module-implementation-rules.md` and are the reason many
individual lines look the way they do. They explain a large share of the comments in the sections
below.

1. **Money is a type, not a number.** `shared.domain.Money` is the only representation of an amount.
   No `double` or `float` anywhere. Currency mismatches throw rather than convert silently — FX
   belongs to a separate, audited component that does not exist yet. Division always takes an
   explicit scale and `RoundingMode`. Storage is `NUMERIC(20,4)` for amounts, `NUMERIC(20,6)` for
   quantities.
2. **Determinism.** No `LocalDate.now()`, `Instant.now()` or `OffsetDateTime.now()` in calculation
   or pricing logic — time is injected. No randomness, no locale-sensitive `toUpperCase()` without
   `Locale.ROOT`, no hash-ordered iteration feeding a sum. Any reproducible calculation carries an
   input checksum.
3. **Evidence and lineage.** Every monetary result is traceable to the row that produced it. Never
   store a full source document inside a business entity — store a reference and a checksum.
4. **Tenancy.** `organization_id` is the boundary; every tenant-owned table has it and every query
   filters on it. Scope comes from the authenticated principal, never from a request body or query
   parameter. A record missing within the caller's tenant is reported as not-found — never as
   "belongs to someone else". Never log monetary values, contract text, file contents or tokens.
5. **Schema.** Owned by the Flyway migrations. Entities map to the exact table/column names;
   migrations are not edited to suit an entity. `ddl-auto` never creates tables in production.
6. **API.** `ApiResponse<T>` for success, `GlobalExceptionHandler` for failure — never hand-rolled
   error bodies. Typed exceptions from `shared.exception`. Never return an entity; map to a DTO.
7. **Build discipline.** MapStruct runs with `unmappedTargetPolicy=ERROR`: a silently dropped
   amount field would corrupt financial reporting, so the build must fail instead.
8. **Comments.** Javadoc explains *why* a decision was made and what invariant it protects. It does
   not narrate what the code obviously does.

---

# Module explanations

The thirteen sections below were produced by independent passes over the codebase. Each covers one
slice, lists every file it owns, and gives that slice's runtime flow and implementation rationale.


---

## 1. Foundation — shared kernel & platform

### Module goal

This slice is the floor every other module stands on. `shared/**` supplies the vocabulary of the
business — money, currency, business dates, tenant and user identity, provenance pointers — as
immutable value types with the arithmetic already guarded, so that no two modules can disagree
about what a rupee amount is. `platform/**` supplies the machinery every request depends on
before a controller is ever reached: correlation IDs, structured request logging, idempotent
retries, a single exception-to-HTTP translation layer, append-only audit, tenant-scoped object
storage, JSON rules that keep monetary values out of binary floating point, and the metric hygiene
that keeps financial data out of telemetry.

The organising idea is that the dangerous decisions are made once, here, and are then hard to
undo. Money refuses to compare across currencies. `Preconditions` refuses to let two copies of the
same guard raise different exceptions. Audit writes are isolated so a storage fault cannot roll
back a legitimate financial operation. The error shape is fixed. Nothing downstream has to
rediscover any of these, and nothing downstream is permitted to quietly change them.

---

### File inventory

Every file in the slice, with what it is for.

#### Bootstrap

| Path | Goal of this file |
| --- | --- |
| `CfoApplication.java` | Entry point. Declares the component-scan root (`com.fintech.cfo`) and enables `@ConfigurationPropertiesScan` so typed properties records anywhere in the tree are registered by being present. Contains no logic on purpose. |

#### `shared/domain` — the business vocabulary

| Path | Goal of this file |
| --- | --- |
| `shared/domain/Money.java` | The foundational type. An immutable amount bound to one currency, refusing mixed-currency arithmetic, requiring explicit scale and rounding mode for division, and comparing/equality-checking by value rather than by `BigDecimal` scale so `100` and `100.00` are the same amount. |
| `shared/domain/CurrencyCode.java` | Interned, validated ISO-4217 three-letter code. Holds no FX behaviour; interning is safe precisely because the shape check bounds the cache at 26^3 entries. |
| `shared/domain/DateRange.java` | Inclusive business-date range with no time-zone semantics. Counts days inclusively so a single-day period is one day, and treats touching ranges as overlapping. |
| `shared/domain/TenantId.java` | Strongly typed tenant identifier. An identifier only — never an authorization decision, which is enforced separately. |
| `shared/domain/OrganizationId.java` | Strongly typed organization identifier, the tenant boundary for enterprise financial data. |
| `shared/domain/UserId.java` | Strongly typed user identifier. |
| `shared/domain/VersionedValue.java` | A value plus the exact version and effective instant at which it applied, so a historical calculation can name the rule version it used. Treats the effective instant itself as in force. |
| `shared/domain/SourceReference.java` | Immutable pointer from normalized data back to the originating system, record, file and row. The end of the traceability chain; deliberately stores references and never source content. |

#### `shared/enums` — cross-module status vocabulary

| Path | Goal of this file |
| --- | --- |
| `shared/enums/Currency.java` | Curated closed set of currencies the business transacts in, for config values and enum-typed API parameters. Converts losslessly to and from `CurrencyCode`. |
| `shared/enums/ProcessingStatus.java` | Status of an asynchronous processing unit. Separates `SKIPPED` from `FAILED` so an ineligible batch is not alerted on as an error. |
| `shared/enums/Status.java` | Coarse lifecycle status shared across long-running business records, alongside the Phase 0 opportunity/value lifecycle it defers to. |

#### `shared/exception` — the error contract

| Path | Goal of this file |
| --- | --- |
| `shared/exception/DomainException.java` | Base for deliberate application failures, carrying a stable machine-readable code alongside the human message. The code is the client contract; the message is a diagnostic. |
| `shared/exception/ValidationException.java` | Domain validation failure. The exception every `Preconditions` guard raises, so a bad request is a 400 rather than an unhandled 500. |
| `shared/exception/NotFoundException.java` | Unresolvable resource, reported so that under tenant isolation the message cannot reveal that a record belongs to someone else. |
| `shared/exception/ConflictException.java` | State conflict: optimistic locking, duplicate operation, idempotency conflict, illegal transition. |
| `shared/exception/AccessDeniedException.java` | Application-layer authorization refusal, for decisions Spring Security's own authorization does not make. |
| `shared/exception/BusinessRuleException.java` | A well-formed request rejected by a business rule. Exists so the calculation engine raises it rather than returning a fabricated or default monetary value. |

#### `shared/security` — request identity

| Path | Goal of this file |
| --- | --- |
| `shared/security/SecurityPrincipal.java` | Immutable authenticated caller: user, email, organization scope, authorities, and whether the tenant was verified server-side. Never accepted from client-supplied request parameters. |
| `shared/security/SecurityContext.java` | Static access point reading Spring Security's `SecurityContextHolder`, so there is exactly one source of truth for request identity. Returns null for anonymous, throws for `requirePrincipal`. |

#### `shared/util` and `shared/validation`

| Path | Goal of this file |
| --- | --- |
| `shared/util/DateTimeUtils.java` | The single injectable clock. Business dates are UTC; tests inject a fixed `Clock` so period boundaries and reproducibility hold. Also owns month/quarter/financial-year arithmetic. |
| `shared/util/HashUtils.java` | SHA-256 helpers for file checksums and content fingerprints. Integrity and deduplication only, never password hashing. Streams so large documents never load whole. |
| `shared/util/IdGenerator.java` | Single seam for identifier generation. Random UUIDs so records can be created across modules without a central sequence and without leaking record counts. |
| `shared/validation/Preconditions.java` | The guard clauses every module's compact constructor uses. Centralised because twenty-six hand-copied private helpers had drifted and made the same defect a 500 in one model and a 400 in another. |
| `shared/validation/package-info.java` | Package-level rationale for the guard-clause centralisation, and the `@NullMarked` nullness default for the package. |

#### `platform/audit` — append-only history

| Path | Goal of this file |
| --- | --- |
| `platform/audit/AuditEvent.java` | Immutable transport type for an audit fact. Fifteen shared fields so one query answers any compliance question; no setters, so a recorded fact cannot be rewritten. |
| `platform/audit/AuditEventType.java` | Closed set of auditable event kinds, grouped by concern so a reviewer can separately ask "who touched this data" and "what happened in the business". Values are storage contracts. |
| `platform/audit/AuditEventEntity.java` | JPA mapping for `audit_events`, with indexes for the three access patterns (org+time, entity+time, correlation). Append-only; `occurred_at` is never updated. |
| `platform/audit/AuditRepository.java` | Narrow read/write port over audit storage. Append is the only write; every read takes an explicit limit so a diagnostic call cannot become expensive. |
| `platform/audit/AuditEventJpaRepository.java` | Spring Data queries for the same port, including the entity-history and correlation lookups the port promises. |
| `platform/audit/AuditService.java` | The entry point for recording audit facts. Writes in `REQUIRES_NEW` so an audit row survives independently of the business transaction, and swallows storage failures so audit trouble never rolls back a legitimate financial operation. Returns an outcome so callers who need a complete trail can escalate. |

#### `platform/config` — application wiring

| Path | Goal of this file |
| --- | --- |
| `platform/config/ApplicationProperties.java` | Typed, immutable configuration bound from `cfo.application.*`, with a default for every field so the app starts with no config block at all. Secrets deliberately excluded. |
| `platform/config/JacksonConfig.java` | JSON rules that matter financially: monetary numbers go through `BigDecimal` and never `double`, trailing tokens are rejected, polymorphic deserialization stays off, and shared value objects render as their canonical string form. |
| `platform/config/TransactionConfig.java` | Declares that application services own transaction boundaries and repositories do not, and records why ingestion and calculation are chunked rather than wrapped in one large transaction. |
| `platform/config/AsyncConfig.java` | Virtual-thread executor for lightweight, non-durable work only, plus the handler that stops a failure in a `void @Async` method from disappearing silently. |
| `platform/config/OpenApiConfig.java` | Document-level OpenAPI model: service title, bearer scheme applied globally, and the correlation header with the exact constraints the filter enforces. |

#### `platform/idempotency` — exactly-once writes

| Path | Goal of this file |
| --- | --- |
| `platform/idempotency/IdempotencyRecord.java` | Persisted claim with an optimistic-locking version, a payload fingerprint, the stored response, and an explicit four-state lifecycle. Two concurrent requests with the same key cannot both complete. |
| `platform/idempotency/IdempotencyService.java` | Claims a key in its own short transaction *before* the business work starts, so a process death mid-handler replays rather than duplicates. Rejects a key reused with a different payload. |
| `platform/idempotency/IdempotencyRepository.java` | Three storage operations: lookup by key, cheap existence check, and the expiry sweep — the only deletion in the mechanism. |
| `platform/idempotency/IdempotencyFilter.java` | Applies idempotency to unsafe methods carrying an `Idempotency-Key`. Buffers the response so a completed claim replays verbatim, and abandons the key when the handler fails so the caller may retry. |

#### `platform/observability` — metrics without leaks

| Path | Goal of this file |
| --- | --- |
| `platform/observability/BusinessMetrics.java` | Business-level meters for calculation runs, variances, ingestion batches, opportunity transitions, audit failures and idempotent replays. Counts and latencies only; monetary values are never tag values. |
| `platform/observability/MetricsConfiguration.java` | Registry hygiene enforced in code: drops any meter carrying an identity tag and caps total distinct meters, so instrumentation cannot become the incident or export financial data. |
| `platform/observability/TracingConfiguration.java` | Deliberately does *not* configure a tracer, since `micrometer-tracing` is absent and a tracer without an exporter costs work and yields nothing. Configures only the common application tag. |

#### `platform/persistence` — JPA auditing

| Path | Goal of this file |
| --- | --- |
| `platform/persistence/JpaConfiguration.java` | Enables JPA auditing and supplies the auditor beans, resolving to empty for scheduled and async work so automated activity is attributed to the system rather than to whoever triggered it. |
| `platform/persistence/PersistenceAuditListener.java` | The same auditor resolution as a testable collaborator, plus two small helpers for deciding whether a row is attributable to a real user. |

#### `platform/storage` — evidence blobs

| Path | Goal of this file |
| --- | --- |
| `platform/storage/ObjectStoragePort.java` | Hexagonal port for object storage so financial modules never depend on a vendor SDK. Streaming both ways; `localPath` throws by default because a remote store cannot honour it. |
| `platform/storage/ObjectStorageService.java` | Tenant-scoped, checksum-verified facade over the port. Names objects by tenant so one tenant's key cannot reach another's evidence, and reports a foreign key as "not found" rather than "forbidden". Ships a sandboxed local-filesystem default that cannot be escaped with `../`. |
| `platform/storage/StorageObject.java` | Metadata handle for a stored blob, carrying no content so large documents never pass through the heap twice. |

#### `platform/web` — the HTTP edge

| Path | Goal of this file |
| --- | --- |
| `platform/web/CorrelationIdFilter.java` | Creates or validates one correlation ID per request, publishes it as a response header, and puts it in the MDC. Runs at `HIGHEST_PRECEDENCE` so everything downstream can join its logs. |
| `platform/web/RequestLoggingFilter.java` | Exactly one structured entry per request, timed on a monotonic clock and logged in `finally` so failures are never missed. Runs at `HIGHEST_PRECEDENCE + 1` so the MDC is already populated. Never logs payloads, headers or financial data. |
| `platform/web/ApiResponse.java` | Standard success envelope: payload plus metadata carrying the correlation ID supplied by the caller, never regenerated. |
| `platform/web/ApiErrorResponse.java` | The single stable error envelope. Clients branch on `code`; stack traces, SQL, class names and provider internals must never appear. |
| `platform/web/GlobalExceptionHandler.java` | Funnels every exception into one response shape and decides status, code, title and safe detail per exception type. Ordered so specific handlers beat the catch-all, and never echoes an unexpected exception's message. |

---

### Flow of journey

Runtime path of a single `POST /api/v1/invoices` request, then the same path for a retried write.

1. **Bootstrap.** `CfoApplication.main` starts the context. `@SpringBootApplication` scans from
   `com.fintech.cfo`; `@ConfigurationPropertiesScan` binds `platform.config.ApplicationProperties`.
   `JacksonConfig`, `TransactionConfig`, `AsyncConfig`, `OpenApiConfig`, `JpaConfiguration` and the
   observability filters are all registered here.
2. **Filter chain, in order.** Both edge filters extend `OncePerRequestFilter` and are ordered by
   `@Order`, which is the whole of the ordering contract:
   - `CorrelationIdFilter` at `Ordered.HIGHEST_PRECEDENCE`. Resolves the inbound `X-Correlation-ID`
     if it is present, at most 64 chars, matching `[A-Za-z0-9._-]+`; otherwise mints a UUID. Sets the
     request attribute, echoes the header, and puts `correlationId` in the MDC. It must run first
     because everything downstream reads that MDC entry, and it must run before authentication
     because an unauthenticated request still needs an ID in the log.
   - `RequestLoggingFilter` at `Ordered.HIGHEST_PRECEDENCE + 1`. Its dependence on the MDC entry is
     the reason for the exact offset, and it is a hard requirement rather than a preference.
   - Spring Security's filter chain. *Not present in this slice*: `identity/security` is currently a
     set of unimplemented stubs, so nothing in the repository establishes a `SecurityPrincipal` yet.
     See the caveat under implementation notes.
   - `IdempotencyFilter`, unordered among servlet filters but still inside the chain. `shouldNotFilter`
     returns early for GET/HEAD/OPTIONS/TRACE and for any request without an `Idempotency-Key`.
3. **Idempotency claim.** For an unsafe method carrying a key, the filter normalises the key,
   fingerprints the request *line* only (`method + URI + query` — never the body, which the handler
   still needs), and calls `IdempotencyService.claim`. That claim runs in `REQUIRES_NEW` and is
   committed before the handler runs.
   - `Claim.Proceed` → continue to step 4.
   - `Claim.Replay` → write the stored status and body verbatim with `Idempotency-Replayed: true`,
     and stop. The handler never runs.
   - `Claim.InProgress` → throw `ConflictException`, which step 6 renders as a 409 in the standard
     error shape.
4. **Handler.** Response is wrapped in a `ContentCachingResponseWrapper` so it can be replayed later;
   the controller runs, and services use `@Transactional` boundaries from `TransactionConfig`.
5. **Return path.** `IdempotencyFilter` reads the final status. Below 400 it calls `complete`, storing
   the status and body *before* releasing the buffered body, so a retry racing this response can
   already replay it; at or above 400 it calls `abandon`. Either way the buffer is copied to the real
   response. Then the chain unwinds through `RequestLoggingFilter`, which logs once in `finally`.
6. **Exceptions.** Anything thrown inside the controller is translated by `GlobalExceptionHandler`:
   `MethodArgumentNotValidException` and `ConstraintViolationException` to 400, `NotFoundException` to
   404, `ConflictException` to 409, `AccessDeniedException` to 403, `BusinessRuleException` to 422,
   other `DomainException` to 400 with the exception's own code, and everything else to a fixed 500.
   `build` reads the correlation ID from the request attribute, so every error body and the response
   header always agree.

### Flow of journey — background work

1. A job in `processing/` starts, typically outside any HTTP request.
2. `SecurityContext.currentPrincipal()` returns null, so `JpaConfiguration`'s auditor yields
   `Optional.empty()` and `AuditService.tenantId()`/`actorId()` yield null. Automated activity is
   recorded as system activity.
3. `AuditService.record` runs in `REQUIRES_NEW`, so its row commits independently of whatever
   transaction the job holds. If the job later rolls back, the audit row remains — the attempt
   happened.
4. If the audit write itself fails, `DataAccessException` is caught and logged, and `AuditOutcome`
   reports `recorded=false`. The financial operation is not rolled back.
5. `DateTimeUtils.now()` supplies timestamps, so a test can substitute a fixed clock.

### Flow of journey — evidence upload and read

1. Caller supplies a tenant UUID and a stream to `ObjectStorageService.store`.
2. `buildKey` produces `<tenantId>/<category>/<uuid>-<sanitised name>`; `sanitize` strips anything
   outside `[A-Za-z0-9._-]` and truncates from the left so the extension survives.
3. The port stores and returns a `StorageObject` including a computed SHA-256.
4. On read, `retrieve` and `delete` both pass through `requireTenantPrefix`, which rejects any key
   not already under the caller's tenant prefix and reports it as `NotFoundException` — 404, not 403,
   so the response cannot confirm that another tenant's object exists.
5. `verify` re-hashes a supplied stream and compares. A read failure returns false: an unverifiable
   document is never treated as a verified one.

---

### Flow of implementation

#### Invariants other modules may depend on

**`Money` never mixes currencies and never rounds implicitly.** Every two-operand operation passes
through `requireSameCurrency`, so a mismatch is reported identically wherever it is noticed.
`equals` and `compareTo` use `BigDecimal.compareTo`, so `100` and `100.00` are the same amount and a
hash set holds one entry for it; `hashCode` strips trailing zeros to stay consistent with that.
`divide` requires an explicit scale and `RoundingMode` because `BigDecimal` division is otherwise
exact-or-exception. Nothing in the system uses `double` or `float` for money — `JacksonConfig`
enforces the JSON side of the same rule.

**Validation failures are always `ValidationException`.** `Preconditions` is the single source of
guard clauses, and every one of them raises that type. This is what makes a rejected value a 400 in
every module instead of a 500 in some and a 400 in others.

**Audit facts are immutable and append-only.** No setters on `AuditEvent`; `withCorrelation` returns
a copy. Audit rows are written in `REQUIRES_NEW` and never updated or deleted by application code.
The deliberate consequence is that the trail is *not* transactionally consistent with business data:
a rolled-back operation leaves its audit row. Callers needing "the record exists and is audited, or
neither happened" must inspect the returned `AuditOutcome`.

**Idempotency claims are durable before the work starts.** A key is claimed in its own committed
transaction. That ordering is what makes a mid-handler crash replay instead of duplicate. A key
presented with a different payload fingerprint is a `ConflictException`, never a stale replay.

**Errors have one shape.** `GlobalExceptionHandler` produces the same envelope for every failure,
with a stable `code` clients branch on. Unexpected exceptions log their stack trace and reply with a
fixed generic string.

**Metrics carry no identity and no money.** `MetricsConfiguration` drops any meter with an
identity tag and caps distinct meters; `BusinessMetrics` records counts and latencies and puts
amounts into untagged distributions.

**Storage keys are tenant-scoped and traversal-safe.** Keys are built with the tenant prefix,
reads are prefix-checked, and the local adapter re-normalizes each resolved path and confirms it
stays inside the root.

#### What each module may depend on

Measured across the repository, the shared kernel is imported 300+ times; the platform packages are
not imported by other modules at all — they are wired by Spring, so their coupling is annotation-based
rather than import-based.

| Slice package | Imported by other modules | Times |
| --- | --- | --- |
| `shared.domain` | contract, financialtruth, financial, evidence, opportunity, ingestion, value | 121 |
| `shared.exception` | all of the above | 98 |
| `shared.validation` | financial, contract | 8 |
| `shared.util` | ingestion, financial, financialtruth | 6 |
| `shared.security` | — (only `shared` and `platform` reference it) | 2 |

Most-imported individual types: `ValidationException` (81), `Money` (40), `OrganizationId` (28),
`CurrencyCode` (28), `BusinessRuleException` (15), `SourceReference` (15), `Preconditions` (8).

Module-level reach: `contract` 34 files, `financialtruth` 32, `opportunity` 14, `financial` 13,
`ingestion` 12, `evidence` 10, `value` 1.

#### Why the key lines exist

- **`CurrencyCode.INTERNED` as an unbounded `ConcurrentHashMap`.** Safe only because a valid code is
  exactly three upper-case ASCII letters, so the cache cannot exceed 26^3 entries. The comment says
  so at the field, because an unbounded cache would otherwise be a leak by inspection.
- **`Locale.ROOT` in every case conversion.** A Turkish default locale lower-cases `i` to a dotted
  capital, which would corrupt currency-code normalisation and environment parsing.
- **`DateRange.days()` adding 1.** `ChronoUnit.between` is end-exclusive while the type is inclusive;
  without the increment a one-day period measures zero.
- **`VersionedValue.isInForceAt` using `!isBefore` rather than a half-open window.** Half-open would
  exclude the exact instant a version took effect, so a naive comparison silently falls back to the
  previous version and reports a plausible wrong number.
- **`Preconditions.requireNumeric` checking scale before precision.** `precision` counts significant
  digits including fractional ones, so checking the digit budget first would accept `0.000001` on a
  `NUMERIC(20,4)` column and reject it only by accident of digit alignment. Zero is exempt from the
  scale check because `BigDecimal.ZERO` is `0E-9`.
- **`IdempotencyService.claim` catching `DataIntegrityViolationException`.** Two threads can race
  between the read and the insert; the unique index is the real arbiter, so the loser re-reads and
  takes the normal decision path rather than failing the request.
- **`IdempotencyService.decide` ordering fingerprint, then expiry, then state.** A payload mismatch
  is a client defect that must be reported whatever the state; an expired record has no response
  worth replaying.
- **`AuditService` catching and logging rather than propagating.** Losing an audit row must never
  roll back a legitimate financial operation — the failure is surfaced through `AuditOutcome` and
  `BusinessMetrics.recordAuditFailure` instead.
- **`ObjectStorageService.requireTenantPrefix` throwing `NotFoundException`, not
  `AccessDeniedException`.** 404 rather than 403, because 403 confirms the object exists.
- **`CorrelationIdFilter` validating the inbound ID against `^[A-Za-z0-9._-]+$`.** The value is
  echoed into a response header and a log line, so it is sanitised on the way in rather than
  trusted.
- **`RequestLoggingFilter` using `System.nanoTime()` and logging in `finally`.** A monotonic clock
  cannot go backwards on NTP resync; logging only on the success path would go silent exactly when
  something has gone wrong.
- **`MetricsConfiguration.forbidIdentityTags`.** A metric label is indexed, retained and often
  visible on shared dashboards, so the failure is enforced in code rather than by convention.

#### Caveats worth stating plainly

- **The identity module is unimplemented.** `identity/**` is currently stub placeholders, so nothing
  in the repository constructs a `SecurityPrincipal` and `SecurityContext.currentPrincipal()`
  always returns null at runtime. `shared/security` is the consumer side of a contract whose producer
  does not exist yet. This slice documents what the code states rather than a working JWT path.
- **Spring Security is not wired.** There is no `SecurityFilterChain` in this slice, so the filter
  ordering described above places `IdempotencyFilter` after a security chain that is not yet present.
- **Tracing is intentionally absent**, by decision recorded in `TracingConfiguration`, not by
  oversight.

---

### Key comments added

The slice already carried substantial design rationale; none of it was reworded or removed. The
comments below were added to fill genuine gaps, chosen because each one records a decision a reader
would otherwise have to reverse-engineer.

- **`shared/util/IdGenerator`** — the class was three lines of code with a one-line comment. Added why
  the id strategy is a bean rather than a static call (it is the seam for a future snowflake/ULID
  migration), why random UUIDs rather than a sequence (no contention, no count disclosure, ids
  mintable before insert), and the negative guarantee that matters most: id ordering must never be
  relied on, so newest-first queries sort on a timestamp column.
- **`Money`** — added `@param`/`@return` across the arithmetic surface, plus why there is no
  `divide(BigDecimal)`, why `equals` uses `compareTo` rather than `BigDecimal.equals`, and why
  `toString` uses `toPlainString` (toString would render `100` as `1E+2`).
- **`shared/validation/Preconditions`** — the terse guards documented their behaviour but not their
  contract. Added full `@param`/`@return`/`@throws`, noting that each returns its input so it can wrap
  an assignment inline.
- **`IdempotencyService.decide`** — the ordering of its three checks was undocumented and is the
  subtlest logic in the platform. Added why fingerprint precedes expiry precedes state, and why
  `FAILED`/`EXPIRED` reset to `proceed` rather than waiting out the retention window.
- **`ObjectStoragePort.exists`** — added why a `RuntimeException` is reported as "absent" (an outage
  must not cause callers to skip work that should have happened) and why the retrieved stream is not
  closed here.
- **`JpaConfiguration` / `PersistenceAuditListener`** — added why empty is the correct auditor for
  system work: a sentinel id would put automated rows in a user's activity history and destroy the
  answer to "who ran the nightly calculation".
- **`AsyncConfig`** — added why virtual threads suit this executor, and specifically why the uncaught
  exception handler is only load-bearing for `void` methods (a value-returning async method surfaces
  its own failure through the Future).
- **`BusinessMetrics`** — added why amounts are never tag values and why row counts go into a
  distribution rather than a tag.
- **`AuditEvent`** — added why three of the fifteen fields are not validated in the compact
  constructor: system-generated activity has no tenant and no human actor, and rejecting those events
  would leave the most interesting rows unrecorded.
- **`AuditEventType.isSecurityEvent`** — added why this is a switch and not a naming-prefix test: a
  prefix convention classifies a new constant by how it was spelled.
- **`ApplicationProperties` / `OpenApiConfig`** — added the boundary each holds: `ApplicationProperties`
  describes the deployment and is not a feature-flag home; the OpenAPI model is document-level only,
  so per-endpoint documentation lives on the controllers.
- **`CfoApplication`** — added why both annotations are present, since `@ConfigurationPropertiesScan`
  is what registers `ApplicationProperties` without any module remembering to.

#### A note on concurrent edits

Several files in this slice (`DateTimeUtils`, `HashUtils`, `GlobalExceptionHandler`,
`RequestLoggingFilter`, `CorrelationIdFilter`, `AuditEventEntity`, `IdempotencyRecord`,
`IdempotencyFilter`, `AuditService`) arrived already carrying extensive design-rationale Javadoc, and
at least one was rewritten by another process while this documentation pass was reading it. On-disk
content was treated as authoritative, every file was re-read immediately before editing, and all
existing comments were preserved verbatim. No executable statement, import, signature, annotation or
indentation was altered.

---

## 2. Identity, tenancy & authorization

#### Module goal

Every business operation in this system must know **who** is performing it and
**which organization (tenant)** they belong to. The identity module provides
that knowledge: model (User, Organization, Membership, Role, Permission),
authentication plumbing (JWT conversion, Spring Security configuration,
tenant-context filter), and authorization checks (`TenantAccessService`,
`AuthorizationService`). It is the gateway that every downstream module
(ingestion, financial, contract, reporting, etc.) depends on for tenant-scoped,
auditable operations.

#### Implementation status

This is a **designed-but-unimplemented milestone**, per the vertical-slice
ordering in `docs/architecture/flow of files` (Phase 2: "Identity + tenant
security"). All 30 files under `identity/` are generator-stub placeholders —
package declaration, a `TODO: Implement X` Javadoc block, and an empty class
body. No business logic, no signatures, no annotations exist yet.

The **tenancy and security primitives live in `shared/` and ARE implemented**:

| Layer | File | Status |
|---|---|---|
| `shared/domain` | `TenantId.java` | Implemented |
| `shared/domain` | `UserId.java` | Implemented |
| `shared/domain` | `OrganizationId.java` | Implemented |
| `shared/domain` | `SourceReference.java` | Implemented |
| `shared/security` | `SecurityPrincipal.java` | Implemented |
| `shared/security` | `SecurityContext.java` | Implemented |
| `shared/exception` | `AccessDeniedException.java` | Implemented (out of slice, referenced) |

The split is plain: the identity module is scaffolding; the shared primitives
it will build on are real. Until identity is implemented, no request can be
authenticated or tenant-scoped, so integration with downstream modules cannot
begin.

#### File inventory

##### identity/security/ (7 files — commented this pass; code is planned, not written)

| File | Goal of this file |
|---|---|
| `CurrentUser.java` | Injection point that surfaces the authenticated `SecurityPrincipal` to controllers and services. |
| `SecurityConfig.java` | Spring Security configuration: registers the JWT converter, tenant-context filter, and authorization rules in the correct order. |
| `JwtAuthenticationConverter.java` | Converts a verified JWT into a Spring `Authentication` carrying a `SecurityPrincipal`; must not derive tenant scope from the token body. |
| `CurrentUserProvider.java` | Fail-fast accessor that resolves the `SecurityPrincipal` from `SecurityContext`, throwing `AccessDeniedException` when absent. |
| `TenantContext.java` | Request-scoped holder for the resolved `OrganizationId`, backed by a `ScopedValue` (not `ThreadLocal`). |
| `SecurityHeadersConfig.java` | Configures HTTP response security headers (CSP, HSTS, X-Frame-Options) as defense-in-depth. |
| `TenantContextFilter.java` | Servlet filter that binds the tenant scope via `ScopedValue` around the downstream chain, reading tenant only from the verified principal. |

##### identity/service/ (4 files — commented this pass; code is planned, not written)

| File | Goal of this file |
|---|---|
| `UserService.java` | Application service for user lifecycle, always scoped to the caller's organization. |
| `TenantAccessService.java` | THE authority check enforcing §6: verifies the principal belongs to the requested organization. |
| `OrganizationService.java` | Application service for organization provisioning, strictly scoped to the caller's own tenant. |
| `AuthorizationService.java` | Business-layer authorization: checks the principal holds the required authority for the caller's organization. |

##### identity/model/ (5 files — commented this pass; code is planned, not written)

| File | Goal of this file |
|---|---|
| `Membership.java` | Domain model linking a `User` to an `Organization` with a `Role`; the record that establishes tenant membership. |
| `Organization.java` | Domain model and root aggregate for the tenant boundary (`organization_id` from §6). |
| `Permission.java` | Domain model: a named authorization right, always scoped to one organization. |
| `Role.java` | Domain model: a named collection of `Permission`s, scoped to one organization. |
| `User.java` | Domain model: a person who authenticates; identity is carried in `SecurityPrincipal`. |

##### identity/enums/ (3 files — commented this pass; code is planned, not written)

| File | Goal of this file |
|---|---|
| `UserStatus.java` | Closed set of user lifecycle states that gate whether authentication succeeds. |
| `RoleType.java` | Closed vocabulary of tenant-scoped role categories (OWNER, ADMIN, MEMBER). |
| `PermissionType.java` | Closed vocabulary of permission types that drive authority strings and `AuthorizationService` checks. |

##### identity/dto/ (4 files — commented this pass; code is planned, not written)

| File | Goal of this file |
|---|---|
| `OrganizationResponse.java` | API-safe DTO projected from `Organization` for HTTP responses. |
| `PermissionResponse.java` | API-safe DTO projected from `Permission` for HTTP responses. |
| `RoleResponse.java` | API-safe DTO projected from `Role` for HTTP responses. |
| `UserResponse.java` | API-safe DTO projected from `User` for HTTP responses. |

##### identity/controller/ (3 files — left byte-identical per §11)

| File | Goal of this file |
|---|---|
| `UserController.java` | Planned REST controller for user management operations. |
| `RoleController.java` | Planned REST controller for role management operations. |
| `OrganizationController.java` | Planned REST controller for organization management operations. |

##### identity/repository/ (4 files — left byte-identical per §11)

| File | Goal of this file |
|---|---|
| `UserRepository.java` | Planned persistence port for `User` records, scoped to `organization_id` on every query. |
| `RoleRepository.java` | Planned persistence port for `Role` records, scoped to `organization_id`. |
| `OrganizationRepository.java` | Planned persistence port for `Organization` records. |
| `MembershipRepository.java` | Planned persistence port for `Membership` records, the authoritative tenant link. |

##### shared/security/ (2 files — implemented; Javadoc enhanced this pass)

| File | Goal of this file |
|---|---|
| `SecurityPrincipal.java` | Immutable record carrying `userId`, `email`, `organizationId`, `authorities`, and `tenantVerified`. |
| `SecurityContext.java` | Static holder that reads the `SecurityPrincipal` from Spring Security's `SecurityContextHolder`. |

##### shared/domain/ (4 files — implemented; Javadoc enhanced this pass)

| File | Goal of this file |
|---|---|
| `TenantId.java` | Strongly typed, immutable tenant identifier; identifier only, not an authorization decision. |
| `UserId.java` | Strongly typed, immutable user identifier; prevents cross-type confusion at compile time. |
| `OrganizationId.java` | Strongly typed, immutable organization identifier; the tenant boundary from §6. |
| `SourceReference.java` | Immutable pointer from normalized data back to its origin file/row for lineage (§5). |

#### Flow of journey (runtime request path)

```
JWT  →  JwtAuthenticationConverter  →  SecurityContext  →  TenantContextFilter
     →  ScopedValue (TenantContext)  →  AuthorizationService/TenantAccessService
     →  Service  →  Repository
```

1. **Incoming JWT** is verified by Spring Security's filter chain.
   - *IMPLEMENTED* (Spring Security standard) — *CONVERTER PLANNED*
2. **`JwtAuthenticationConverter`** converts the verified `Jwt` into an
   `Authentication` carrying a `SecurityPrincipal`, extracting userId, email,
   and authority claims — but **not** organization scope from the token body.
   - *PLANNED*
3. **`SecurityContext`** holds the authenticated `SecurityPrincipal` as the
   single source of truth for request identity, reading from Spring Security's
   `SecurityContextHolder`.
   - *IMPLEMENTED*
4. **`TenantContextFilter`** runs after authentication, reads the organization
   from the verified `SecurityPrincipal`, and binds it via `ScopedValue` through
   `TenantContext` so the entire downstream chain observes one tenant.
   - *PLANNED*
5. **ScopedValue tenant context** (`TenantContext`) makes the `OrganizationId`
   available to all downstream calls within the same request scope, replacing
   what would otherwise be a `ThreadLocal` (immutable, lexically scoped,
   auto-unbound — §1).
   - *PLANNED*
6. **`AuthorizationService` / `TenantAccessService`** check that the principal
   is tenant-verified and holds the required authority for the caller's
   organization, throwing `AccessDeniedException` on failure.
   - *PLANNED*
7. **Service** (e.g. `UserService`, `OrganizationService`) performs business
   logic, always filtering queries on the principal's organization (§6).
   - *PLANNED*
8. **Repository** (e.g. `UserRepository`) persists/retrieves records, scoped to
   `organization_id` on every query (§6).
   - *PLANNED* (left untouched per §11)

#### Flow of implementation

**Tenancy model.** `organization_id` is the tenant boundary (§6). Every
tenant-owned table will carry it and every query will filter on it. The
identifier types are the implemented shared primitives: `OrganizationId`
(the boundary), `TenantId` (identifier-only, never an authorization
decision), and `UserId` (strongly typed to prevent cross-type confusion).
The identity model layer will wire these into `Organization`, `User`,
`Membership`, `Role`, and `Permission`, each scoped to exactly one
organization.

**Security-principal contract.** `SecurityPrincipal` is the single record of
who the caller is and which organizations they may act for. It is immutable
(a record), established server-side from a **verified** JWT, and its
`organizationId` is set only after a server-side `Membership` check — never
accepted from a client request body or query parameter (§6). The
`tenantVerified` flag is the gate: `isTenantResolved()` returns true only
when both `organizationId` is present **and** `tenantVerified` is true.
`AccessDeniedException` is the thrown signal when a principal is missing or
unauthorized.

**`ScopedValue` vs `ThreadLocal`.** `SecurityContext` (implemented) delegates
to Spring Security's `SecurityContextHolder` — the canonical request-identity
holder populated by Spring's filter chain. `TenantContext` /
`TenantContextFilter` (planned) will bridge from that principal into a
`ScopedValue`-bound tenant scope (§1): `ScopedValue` is immutable, lexically
scoped, and automatically unbound on exit, so a tenant value cannot leak
into a pooled thread. This is why tenant scope lives in `identity/security`,
separate from identity in `shared/security`.

**Duplication risk.** There is a deliberate, documented overlap between
`identity/security/` stubs and `shared/security/`:

- `identity/security/{CurrentUser, CurrentUserProvider}` — planned injection
  points — will read from `shared/security/SecurityContext`, which already
  holds `shared/security/SecurityPrincipal`.
- `identity/security/{TenantContext, TenantContextFilter}` — planned
  `ScopedValue` binding — wraps the organization from
  `SecurityPrincipal.organizationId()`.

The risk is that these planned types could duplicate the principal-holding
role that `SecurityContext` already fills. The boundary is: `SecurityContext`
owns **who**, `TenantContext` owns **which tenant**. They must not be
collapsed into one type, or the lexical scope of `ScopedValue` (needed for
leak prevention) would be lost. This is recorded in the class-level comments
on both sides.

**Invariants from §6 (restated in the comments above):**

1. `organization_id` is the tenant boundary; every query filters on it.
2. Organization id is **never** accepted from a request body or query
   parameter — it comes from the authenticated `SecurityPrincipal` via
   `SecurityContext`.
3. A record not found within the caller's tenant returns not-found; never
   distinguish "absent" from "belongs to someone else".
4. Never log monetary values, credentials, or bearer tokens — only
   identifiers, statuses, and correlation IDs.

#### Key comments added

This pass added only **comments** — no executable code, no imports, no
signatures, no annotations, no indentation changes.

**Identity stubs commented (22 files):** one class-level Javadoc was prepended
above each existing `TODO` block in:

- `identity/security/`: `CurrentUser`, `SecurityConfig`,
  `JwtAuthenticationConverter`, `CurrentUserProvider`, `TenantContext`,
  `SecurityHeadersConfig`, `TenantContextFilter` — each states its role in the
  authn/authz chain, the §6 invariants it must honour, and intended
  collaborators.
- `identity/service/`: `UserService`, `TenantAccessService` (the §6 gate),
  `OrganizationService`, `AuthorizationService`.
- `identity/model/`: `Membership`, `Organization`, `Permission`, `Role`,
  `User`.
- `identity/enums/`: `UserStatus`, `RoleType`, `PermissionType`.
- `identity/dto/`: `OrganizationResponse`, `PermissionResponse`,
  `RoleResponse`, `UserResponse`.

**Identity stubs left byte-identical (7 files):** all files under
`identity/controller/` and `identity/repository/` per §11 — verified
unchanged.

**Shared files enhanced (3 files):**

- `shared/security/SecurityContext.java` — added WHY `ScopedValue` is not
  used directly (Spring Security populates the holder; the identity layer
  bridges via `TenantContextFilter`).
- `shared/domain/TenantId.java` — added WHY immutable record (thread safety
  across pooled threads, determinism per §4).
- `shared/domain/UserId.java` — expanded to explain WHY strongly typed and
  immutable (compile-time type safety, leak prevention across tenants).


---

## Module explanations

## 3. Ingestion — secure file pipeline

#### Module goal

Ingestion turns an untrusted file — a bank statement, an ERP invoice export, a
finance team's spreadsheet — into rows that the rest of the system is willing to
treat as financial evidence. It exists because that conversion is the single
point where attacker-controlled bytes become numbers somebody will later report
to a board, so the module is built as a series of gates rather than as a parser:
nothing may look at the *content* of an upload until its *name*, its
*signatures*, its *declared type* and its *actual type* have all been shown to
agree, and no row is ever trusted on the strength of having been read
successfully. Three properties are treated as non-negotiable throughout and are
the reason for most of the structure below. **Traceability** — every row carries
the file, sheet and 1-based row it came from, so any rupee can be traced back to
the line that produced it. **Honesty about failure** — a row that is refused
always says why, and a file that was only partly read is never reported as fully
read. **Reproducibility** — no wall clock, no randomness, no locale-dependent
parsing and no coercion, so re-running the same upload months later yields the
same rows, the same findings and the same rejection reasons.

#### File inventory

All paths are relative to `src/main/java/com/fintech/cfo/`.

##### `ingestion/controller`

- **`ingestion/controller/IngestionController.java`** — Placeholder for the HTTP
  surface: accept an upload, start a run, report status, list a run's errors.
  Intended as a thin adapter over the service layer; the pipeline below it is
  plain objects with no Spring annotations and does not depend on this class.

##### `ingestion/dto`

- **`ingestion/dto/UploadedFileResponse.java`** — The file-security gate's verdict:
  declared and detected type reported *separately*, both filenames (original for
  audit, sanitised for reuse), and the SHA-256 digest. Never the bytes.
- **`ingestion/dto/StartIngestionRequest.java`** — What a client supplies to start
  a run, notably the schema. The schema arrives with the request rather than
  being inferred from the file, because a schema read out of the upload could
  describe itself and then satisfy its own validation.
- **`ingestion/dto/IngestionResponse.java`** — Projection of
  `IngestionProcessingResult` for a client that started the run. Rejected rows
  are listed individually with coordinates and reasons, not as a count, because
  "3 rows were rejected" does not let anyone fix the export.
- **`ingestion/dto/IngestionStatusResponse.java`** — Snapshot for status polling,
  carrying the V3 `version` counter so a client can distinguish a newer state
  from a stale one. Deliberately carries no row values.
- **`ingestion/dto/IngestionErrorResponse.java`** — One structured error.
  `rowNumber` is nullable, and that is the point: a corrupt container has no row,
  and inventing one would put a false row number into an audit record.
- **`ingestion/dto/package-info.java`** — Package contract: pure records with
  `from` projections, no framework annotations, and no response echoes uploaded
  content.

##### `ingestion/enums`

- **`ingestion/enums/FileType.java`** — Formats the module can read, plus
  `UNSUPPORTED` (a recognised format deliberately refused) and `UNKNOWN` (a typo).
  Declared type and detected type are recorded separately and compared.
- **`ingestion/enums/FileSecurityStatus.java`** — `PENDING` / `PASSED` /
  `REJECTED`, persisted to `source_files.security_status`. Only `PASSED` allows
  parsing; there is no "proceed anyway" state.
- **`ingestion/enums/ParseStatus.java`** — How far a parse got. `PARTIAL` is the
  load-bearing constant: rows were recovered but the file was not fully seen.
- **`ingestion/enums/IngestionStatus.java`** — Run lifecycle.
  `COMPLETED_WITH_REJECTIONS` exists so a run that processed every row but
  refused some can never be reported as a clean run.
- **`ingestion/enums/IngestionStage.java`** — The stage a run has reached,
  persisted to `ingestion_runs.stage`, so a stalled run names the exact gate it
  stalled at.
- **`ingestion/enums/IngestionErrorType.java`** — Classification of a problem
  (file security, type, parse, schema, required field, data type, quality,
  formula injection, duplicate, limit, internal). What a client filters on.
- **`ingestion/enums/RejectionReason.java`** — The exhaustive vocabulary an
  operator reads, grouped file / schema / row / field / security / quality.
  Deliberately specific: "row 4, column amount, INVALID_AMOUNT" is actionable in
  a way "invalid file" never is.
- **`ingestion/enums/ValidationSeverity.java`** — `INFO` / `WARNING` / `ERROR`.
  Only `ERROR` blocks a row; warnings are recorded but still processed.
- **`ingestion/enums/RowStatus.java`** — `ACCEPTED` / `REJECTED` / `SKIPPED`.
  There is no fourth silent bucket: a row that is not accepted always has a
  reason attached.
- **`ingestion/enums/ColumnType.java`** — What the *destination* expects a column
  to be. Deliberately a different vocabulary from `FieldType`, so the validator
  can report "the workbook says text, the schema says amount" instead of
  coercing one into the other.
- **`ingestion/enums/FieldType.java`** — What a reader *observed* in a cell.
  Recorded rather than collapsed into a string because the difference between a
  genuine `-1000` and an injected formula payload is only visible if the type was
  kept.
- **`ingestion/enums/HeaderMode.java`** — How the header row is located:
  heuristic, pinned to the first record, or absent (positional column names).
- **`ingestion/enums/package-info.java`** — Package contract: every constant is
  written to a V3 column, so the string forms and count are a storage contract.

##### `ingestion/model`

- **`ingestion/model/IngestionRequest.java`** — Everything needed for one upload
  in one immutable value. Carries buffered bytes rather than a stream because the
  gate, the checksum and the parser all need the same content, and injects
  `uploadedAt` and `asOfDate` instead of reading a clock.
- **`ingestion/model/IngestionLimits.java`** — The hard ceilings: file size, rows,
  columns, sheets, inflated bytes, archive entries, compression ratio, cell
  length, header scan window. Explicit and injected so a test can lower them and
  prove a guard fires.
- **`ingestion/model/UploadMetadata.java`** — What is known about an upload after
  the gates pass, mirroring the `source_files` columns. Keeps the original and
  the sanitised filename as separate fields, because conflating them is how path
  traversal gets in.
- **`ingestion/model/SanitisedFilename.java`** — A filename proven safe to use as
  a leaf name. A type rather than a string callers are asked to be careful with,
  because its constructor rejects separators, dot segments, whitespace, control
  characters and Windows device names.
- **`ingestion/model/FileChecksum.java`** — SHA-256 fingerprint. Exists for
  reproducibility and because V3 makes `(organization_id, checksum_sha256)` a
  unique key: the same bytes uploaded twice are one source file, not two.
- **`ingestion/model/FileSecurityResult.java`** — Outcome of the pre-parse gates.
  `allowsParsing()` is the only question a parser asks and is false for
  everything except a clean pass.
- **`ingestion/model/FileParseResult.java`** — Everything one parser produced:
  rows read, rows refused and why, and whether the whole file was seen. Keeps
  accepted / rejected / skipped as three separate counts so a run that quietly
  ignored 400 blank rows cannot hide it.
- **`ingestion/model/ParsedRow.java`** — One successfully read row, bound to its
  coordinate. Cells keep header order and their observed type, and `rawPayload`
  is a deterministic dump suitable for a future `source_records.raw_payload`.
- **`ingestion/model/ParsedCell.java`** — One cell as the reader saw it. Keeps
  `text` (cached result) and `rawText` (the formula) apart, which is what lets an
  auditor trace a number to the expression that produced it without the module
  ever evaluating untrusted spreadsheet code.
- **`ingestion/model/RowCoordinate.java`** — File, sheet and 1-based row. The
  hard product requirement behind traceability; row numbers are counted exactly as
  a human would count them in the source.
- **`ingestion/model/Rejection.java`** — Sealed union of everything ingestion
  refused, so the orchestration layer can talk about refusals without caring which
  stage produced them.
- **`ingestion/model/FileRejection.java`** — A whole-file refusal. Has no
  coordinates and does not need them; inventing a row number for a file that
  could not be opened would be false evidence.
- **`ingestion/model/RejectedRow.java`** — A refused row, with the reason, the
  coordinates and — where the problem is narrower — the column, plus the raw
  payload so finance can see what was actually in the row.
- **`ingestion/model/ValidationFinding.java`** — A single structured observation.
  No boolean "invalid" is returned anywhere in the module; findings are also what
  an `ingestion_errors` insert writes verbatim.
- **`ingestion/model/ValidationResult.java`** — Accumulated findings plus the rows
  that survived them. Holds both together because sanitising changes the row, and
  a findings-only object would invite the caller to use the un-neutralised version.
- **`ingestion/model/IngestionProcessingResult.java`** — Terminal output of the
  in-memory flow. Its builder derives the final status from the evidence rather
  than letting a caller choose, so a run with rejections cannot be labelled
  `COMPLETED`.
- **`ingestion/model/IngestionSchema.java`** — The expected shape of a file.
  Supplied by the caller, never inferred from the upload.
- **`ingestion/model/ColumnSchema.java`** — One expected column: name, declared
  type, and whether a row may omit it.
- **`ingestion/model/IngestionRun.java`** — Immutable value mirroring one
  `ingestion_runs` row, with terminal-state guards so a closed run cannot be
  reopened. Not a JPA entity.
- **`ingestion/model/IngestionError.java`** — Immutable value mirroring one
  `ingestion_errors` row, built from a finding so the structured observation and
  the persistable record cannot describe different problems.
- **`ingestion/model/SourceFile.java`** — Immutable value mirroring one
  `source_files` row, enforcing V3's column widths itself so a mapping mistake
  surfaces before an insert rather than as a truncation failure.
- **`ingestion/model/SourceRecord.java`** — Immutable value mirroring one
  `source_records` row: a row exactly as read, with its validity and reasons.
  This type *is* the storage shape of "evidence is a row, never a copy of the
  document".
- **`ingestion/model/package-info.java`** — Package contract: immutable
  throughout, defensively copied, every row carrying a `RowCoordinate`.

##### `ingestion/security`

- **`ingestion/security/UploadAuthorizationService.java`** — Whether the
  authenticated caller may upload for a given organization. Two separate checks
  — the `ingestion:upload` authority *and* a tenant match — because holding the
  authority without the tenant check is the classic cross-tenant leak.
- **`ingestion/security/MalwareScanService.java`** — An offline content screen
  that names dangerous file signatures (PE, ELF, Mach-O, Java class, OLE2, PDF,
  script shebang, NUL smuggling). Explicitly **not** antivirus, and must not be
  described as such: it closes the renamed-payload path without claiming a
  guarantee it cannot keep.
- **`ingestion/security/FilenameSanitiser.java`** — Rebuilds an untrusted
  filename from an allow-list into a `SanitisedFilename`. Rebuilding rather than
  escaping means the output is safe by construction rather than only as safe as
  every future consumer.
- **`ingestion/security/FileUploadSecurityService.java`** — The untrusted-input
  gate, ordered to touch the bytes as little as possible: traversal check,
  signature scan, filename sanitisation, then the type gate.
- **`ingestion/security/package-info.java`** — Package contract: untrusted input
  leaves as a proven type, and this is a content screen, not antivirus.

##### `ingestion/parser`

- **`ingestion/parser/FileParser.java`** — The port every format reader
  implements. Two guarantees every implementation must honour: every row is
  traceable, and nothing is dropped quietly.
- **`ingestion/parser/ParseRequest.java`** — One parse request. Requires
  `asOfDate` and buffers the stream under a hard ceiling, reading one byte past
  the limit so "exactly at the limit" and "one byte over" cannot be confused.
- **`ingestion/parser/ParseOutcome.java`** — The four states a parse can be in,
  as a sealed hierarchy so the compiler forces callers to distinguish "rows read"
  from "rows read from a file we only partly saw".
- **`ingestion/parser/CsvFileParser.java`** — Delimited-text reader on Commons
  CSV. Tokenises *headerless* so a ragged row is recoverable rather than fatal,
  isolates every record in its own try/catch, and reports a mid-file lexer fault
  as `PARTIAL` instead of pretending to have read the rest.
- **`ingestion/parser/ExcelFileParser.java`** — Workbook reader on Apache POI.
  Walks the container before POI sees it, reads each row in isolation, and
  refuses a formula with no cached result rather than fabricating a zero.
- **`ingestion/parser/ExcelCellReader.java`** — Turns one POI cell into a
  `ParsedCell` while preserving its type. Never evaluates a formula, converts
  numeric cells through `BigDecimal` so binary floating point cannot reach the
  money layer, and treats a missing cached result as a refusal.
- **`ingestion/parser/ArchiveGuard.java`** — Decompression-bomb defence for OOXML
  containers, walking the zip under entry-count, inflated-byte and ratio ceilings
  and aborting the moment one is crossed.
- **`ingestion/parser/HeaderDetector.java`** — Chooses where the header row is and
  turns header cells into unique column names. Shared by both readers so the same
  content is treated the same way regardless of format.
- **`ingestion/parser/TextDecoding.java`** — BOM-aware decoding. Detects and
  strips the mark, and refuses invalid bytes under the chosen charset rather than
  substituting replacement characters and calling the result readable.
- **`ingestion/parser/CsvParseOptions.java`** — Delimiter, quote, escape, header
  mode and tolerance for the CSV reader, as an immutable record so the dialect a
  file was read with travels with the result.
- **`ingestion/parser/ExcelParseOptions.java`** — Header mode, hidden-sheet
  handling, sheet allow-list and date-epoch override for the workbook reader.
- **`ingestion/parser/package-info.java`** — Package contract: parsers are the
  first code to look at content, so they only ever run behind the security gate.

##### `ingestion/validator`

- **`ingestion/validator/UploadFileValidator.java`** — The gate a file must pass
  before any parser sees it, requiring the extension, the declared content type,
  the sniffed bytes and the size to agree. A name that says CSV while the bytes
  are a ZIP container is refused rather than coerced.
- **`ingestion/validator/ContentSniffer.java`** — Identifies what a file actually
  is from its leading bytes. A filename is a claim; this is the evidence.
- **`ingestion/validator/SchemaValidator.java`** — Checks the header against the
  expected schema, producing file-level findings only: if a required column is
  absent, every row lacks it, so the upload is refused once rather than
  row-by-row.
- **`ingestion/validator/RequiredFieldValidator.java`** — Checks that columns the
  schema marks required carry a value. Always an error, never a warning: a
  ledger row without a date or amount cannot be posted.
- **`ingestion/validator/DataTypeValidator.java`** — Checks each value can be
  read as its column's declared type. Never coerces, never defaults, never
  truncates.
- **`ingestion/validator/TypedValueParser.java`** — Strict, locale-free parsing of
  decimals, dates and currencies. Refuses ambiguous forms and excess precision
  rather than guessing or rounding.
- **`ingestion/validator/DataQualityValidator.java`** — Well-typed but
  implausible values: dates before the accounting epoch (error) and after the
  reporting cut-off (warning). The cut-off is injected, never read from a clock.
- **`ingestion/validator/FormulaInjectionSanitiser.java`** — Neutralises
  spreadsheet formula injection with the leading apostrophe the spreadsheet
  applications themselves understand, while leaving signed decimals such as
  `-1000` untouched because those are ordinary amounts.
- **`ingestion/validator/DuplicateValidator.java`** — Refuses a row whose
  canonicalised business content already appeared on the same sheet. Stateful by
  design, because a duplicate is a property of the set of rows, not of one row.
- **`ingestion/validator/package-info.java`** — Package contract: nothing here
  coerces a value to make it fit, and amounts are parsed through `BigDecimal`
  only — no `double` or `float` appears in the package.

##### `ingestion/service`

- **`ingestion/service/IngestionService.java`** — The end-to-end in-memory flow
  and the only place the stages are sequenced: gate, parse, validate, count.
  Every input row ends up exactly one of accepted, rejected or skipped.
- **`ingestion/service/FileSecurityService.java`** — The pre-parse gate: may this
  caller upload, is this name safe, are these bytes what the name claims — in
  that order, touching the fewest things first. Turns a screened upload into the
  `UploadMetadata` the rest of the pipeline works from, and computes the
  checksum over the bytes that actually passed.
- **`ingestion/service/IngestionOrchestrator.java`** — Routes an admitted upload
  to the reader for its declared type via an `EnumMap` keyed by `FileType`, so no
  parser can be reached for a type it did not claim. Always returns an outcome;
  never throws for bad input.
- **`ingestion/service/FileValidationService.java`** — The validation facade:
  three ordered gates — may the bytes be parsed at all, does the header match,
  and per-row sanitisation, required fields, types, plausibility, duplicates. The
  row it accepts is the sanitised row, never the raw one.
- **`ingestion/service/IngestionStatusService.java`** — Projects a finished run
  onto the `ingestion_runs` and `ingestion_errors` values. Derives error
  identifiers from the run id and the finding's position rather than randomly, so
  re-projecting the same run yields the same primary keys.
- **`ingestion/service/package-info.java`** — Package contract for the
  orchestration layer.

##### `ingestion/repository`

- **`ingestion/repository/IngestionRepository.java`** — Placeholder for
  `ingestion_runs` persistence. Empty because the producing code is complete and
  only the insert, the optimistic `version` check and the transaction boundary
  remain.
- **`ingestion/repository/IngestionErrorRepository.java`** — Placeholder for
  `ingestion_errors` persistence, which will be a batch write: a 100k-row import
  can carry thousands of findings.
- **`ingestion/repository/SourceFileRepository.java`** — Placeholder for
  `source_files` persistence, which must honour the V3 unique index so
  re-uploading identical bytes returns the existing row rather than creating a
  second source file.

##### `processing/ingestion`

- **`processing/ingestion/IngestionJobConfiguration.java`** — Placeholder for
  the Spring Batch job definition. When implemented it should express the same
  stages as the in-memory flow as one job instance per run, with chunk
  boundaries so a failure costs one chunk rather than the whole import.
- **`processing/ingestion/IngestionJobLauncher.java`** — Placeholder for the
  entry point that starts a job for an admitted upload. Must refuse to launch
  anything the gate did not admit — the reason it sits downstream of the security
  gate rather than in front of it.
- **`processing/ingestion/IngestionProcessor.java`** — Placeholder for the item
  processor, one row per call. Rejections are expected in normal operation, so it
  must record a rejection and return rather than throw.
- **`processing/ingestion/IngestionWriter.java`** — Placeholder for the item
  writer that persists one processed row as a `source_records` row. No monetary
  interpretation happens here; that belongs downstream.

#### Flow of journey

The end-to-end path an upload takes today, in order. Steps 1–2 are the intended
web path; `IngestionController` is still a placeholder, so the flow is driven
today by calling `IngestionService.ingest(...)` directly.

1. **Upload endpoint** — `IngestionController` (placeholder) receives the upload
   and the caller's declared schema (`StartIngestionRequest`) and builds an
   `IngestionRequest`. The bytes arrive as a buffered `byte[]`, already bounded by
   the configured ceiling.
2. **Upload authorisation** — `IngestionService.ingest` calls
   `FileSecurityService.admit`, which first asks `UploadAuthorizationService`.
   The caller must present the `ingestion:upload` authority *and* a tenant equal
   to the target organization. Failure is reported as
   `RejectionReason.ACCESS_DENIED`, not as a file problem, so an audit can tell
   "this user may not upload" from "this file is bad".
3. **Malware scan** — `FileUploadSecurityService.screen` runs
   `MalwareScanService` over the bytes, refusing PE, ELF, Mach-O, Java class,
   OLE2, PDF, shebang and NUL-smuggling signatures. This is a content screen, not
   antivirus; it names a signature and never echoes the bytes.
4. **Filename sanitisation** — `FilenameSanitiser` rebuilds the name from an
   allow-list into a `SanitisedFilename`. A name that carried a directory
   component, a drive letter or a `..` segment was already *refused* rather than
   stripped, by `containsTraversal`, because a genuine upload never has one and
   the attempt is worth recording. Everything downstream receives the sanitised
   name; the raw string never leaves the gate.
5. **Type gate and checksum** — `UploadFileValidator` compares four independent
   claims (extension, declared content type, sniffed content via
   `ContentSniffer`, size) and requires the first three to agree.
   `FileSecurityService` then computes `FileChecksum.sha256` over exactly the
   bytes that passed and emits `UploadMetadata` with status `PASSED`.
6. **Parse** — `IngestionOrchestrator` looks the declared `FileType` up in an
   `EnumMap` and calls the matching `FileParser`, handing it the *sanitised*
   display name so a row coordinate can never quote a traversal path back to a
   client.
   - *CSV*: `CsvFileParser` buffers under the size ceiling, decodes
     BOM-aware and refuses bytes that are not valid text, tokenises with Commons
     CSV in headerless mode, and materialises each record inside its own
     try/catch.
   - *XLSX*: `ExcelFileParser` runs `ArchiveGuard.inspect` **first** — walking the
     zip under entry-count, inflated-byte and ratio ceilings and aborting on the
     first breach — then rejects anything that is not a spreadsheet package
     before `WorkbookFactory` is allowed to inflate it.
7. **Header detection** — `HeaderDetector` locates the header within
   `IngestionLimits.headerScanWindow()` and produces unique column names
   (`column_n` for blanks, `name_2` for repeats). Rows above the header are
   counted as skipped and reported, never silently dropped. Callers who know
   their export can pin the answer with `HeaderMode`.
8. **Validate** — `FileValidationService.validate` runs the schema gate
   (file-level: missing header, missing required column, duplicate column), then
   per row: `FormulaInjectionSanitiser` on every cell, `RequiredFieldValidator`,
   `DataTypeValidator`, `DataQualityValidator`, and finally `DuplicateValidator`.
   The first `ERROR`-severity finding refuses that row alone.
9. **Batch persist** — the Spring Batch path in `processing/ingestion`: the
   launcher starts a job for an admitted run, the job configuration defines the
   stages and chunk boundaries, the processor handles one row per call (recording
   rejections rather than throwing), and the writer persists each row as a
   `source_records` row. All four are placeholders today.
10. **Record the run and status polling** — `IngestionStatusService.toRun` and
    `toErrors` project `IngestionProcessingResult` onto the `IngestionRun` and
    `IngestionError` values that mirror V3, using request-supplied timestamps and
    position-derived error ids. `IngestionStatusResponse.from(run)` is what a
    polling client reads: status, stage, the three row counts, failure reason and
    the `version` counter — and deliberately no row values.

#### Flow of implementation

##### The security model

The pipeline is a chain of gates that narrow what the next stage has to consider,
and the ordering is the design. `FileSecurityService` performs three questions
"in the order that touches the fewest things": may this caller do this at all
(no bytes read), is this name safe (string inspection only), are these bytes what
the name claims (first real content inspection). Only after all three does a
parser exist.

Four properties carry the weight:

- **Proven types, not conventions.** Untrusted input leaves the security package
  as a `SanitisedFilename` and a `FileSecurityResult`. There is no code path
  where a caller can forget to sanitise, because the unsanitised value is never
  passed on. `SanitisedFilename`'s own constructor re-validates, so even a
  hand-constructed instance is safe.
- **Refuse, don't normalise, when the attempt itself is the signal.** A filename
  with a directory component is refused, not stripped: no honest upload has one.
  A filename that merely needed folding is normalised *and* the normalisation is
  reported as a `UNSAFE_FILENAME` warning, so the audit trail records it.
- **Three independent claims must agree.** Extension, declared MIME type and
  sniffed bytes. `ContentSniffer` recognises only what it can positively
  identify, and a generic `application/octet-stream` is treated as "unspecified"
  rather than as a lie, because browsers send it for many honest uploads.
- **Untrusted spreadsheet code is never executed.** `ExcelCellReader` reads a
  formula cell's *cached result* and keeps the expression as raw text. Evaluating
  an uploaded formula would mean running attacker-supplied expressions through
  the JVM's formula engine, and would make results depend on the evaluator rather
  than on the file.

A note on naming: `MalwareScanService` is a content screen, not antivirus. It
holds no signature database, calls no engine and makes no network call. Both it
and its package are documented as such because the class name invites the
stronger claim.

##### Limits enforced

`IngestionLimits` is a record of hard ceilings, injected rather than ambient so a
test can lower one and prove the guard fires. The defaults are an order of
magnitude above a full ERP invoice export for a mid-sized tenant and an order of
magnitude below anything that pressures the heap.

| Limit | Default | Enforced in | Failure mode |
|---|---|---|---|
| `maxFileBytes` | 20 MB | `ParseRequest.readContent` (buffers one byte past), `UploadFileValidator` | `SIZE_LIMIT_EXCEEDED` |
| `maxRowsPerFile` | 100 000 | both parsers' row loops | stop reading, status `PARTIAL` |
| `maxColumns` | 512 | `CsvFileParser.materialise`, `ExcelFileParser.readRow` | row rejected, never truncated |
| `maxSheets` | 32 | `ExcelFileParser.readWorkbook` | stop reading, status `PARTIAL` |
| `maxUncompressedBytes` | 200 MB | `ArchiveGuard.inspect` | `ARCHIVE_LIMIT_EXCEEDED` |
| `maxArchiveEntries` | 2 000 | `ArchiveGuard.inspect` | `ARCHIVE_LIMIT_EXCEEDED` |
| `maxCompressionRatio` | 200× | `ArchiveGuard.exceedsRatio` | `ZIP_BOMB_SUSPECTED` |
| `ratioCheckMinBytes` | 4 096 | `ArchiveGuard.exceedsRatio` | ratio not enforced below this size |
| `maxCellTextLength` | 4 096 | both parsers, `DataQualityValidator` | `VALUE_TOO_LONG`, row rejected |
| `headerScanWindow` | 10 | `HeaderDetector.detect` | header not found, positional names |

Two of these deserve their reasoning stated. **The compression ratio is only
enforced from 4 KB upwards**, because a legitimate small workbook can easily
compress 20:1 and refusing it would train users to bypass the check.
**Declared zip entry sizes are not trusted** — a zip bomb lies in its headers —
so `ArchiveGuard` actually inflates and measures, aborting the moment a ceiling
is crossed. That costs at most the configured ceiling, which is the point.

On zip-slip specifically: this module is entirely in-memory and extracts
nothing, so there is no extraction step for a crafted entry name to attack. Entry
names are still normalised (separators unified, control characters removed,
lower-cased) so a crafted name cannot forge a log line and so the
package-shape test compares against real OOXML paths.

##### Partial-success strategy

Rejections are the normal case, not the exception — a 100k-row export with three
bad rows is a success with three findings. The design therefore makes a bad row
*impossible to lose* rather than trying to prevent it:

- **Exactly one of row or rejection.** Both parsers materialise each record
  through a `RowOutcome` that can only ever produce a `ParsedRow` or a
  `RejectedRow`. There is no third state in which a row vanishes without a
  trace.
- **Row-level isolation.** Each record is read inside its own `try/catch`. A
  wrong field count, an unreadable cell, an over-long value or a formula with no
  cached result rejects that row alone; the next row is read normally.
- **Honest partial reads.** A fault *inside the lexer* (an unterminated quote
  mid-file) cannot be resumed from, because the token stream is already
  consumed. Rather than pretend otherwise, the records read so far are kept, the
  status becomes `PARTIAL`, and the fault is reported with a content-free reason.
  Collapsing that into `SUCCESS` would overstate how much was read; into `FAILED`
  would discard good rows.
- **Three separate counts.** `totalRowCount()` is
  `accepted + rejected + skipped`, and skipped covers positions that genuinely
  carried no data — blank lines, styled-but-empty spreadsheet rows, preamble
  lines above the header. A run that quietly ignored 400 rows is a run whose row
  numbering nobody can reproduce, so skips are counted and, where they hide
  something (a preamble), reported.
- **Findings derived from rejections.** `ValidationResult.Builder.reject` records
  the rejection *and* derives its finding in one step, so a rejected row with no
  matching finding cannot let a caller report a clean run.
- **Status derived from evidence.** `IngestionProcessingResult.Builder.build`
  computes the terminal status from what was accumulated, and `IngestionRun`
  independently maps any non-zero rejection count to
  `COMPLETED_WITH_REJECTIONS`. Neither a caller nor a job can label a lossy run
  `COMPLETED`.

##### Design decisions and why key lines exist

**The security gate is separate from parsing, and `allowsParsing()` is the only
bridge.** Once a file reaches a parser it has already been proven to be a
readable tabular file. `FileSecurityResult` carries one boolean that is false for
everything except a clean pass, which is the whole reason the gates are split.

**Parser selection is by enumeration, not `instanceof`.** `IngestionOrchestrator`
keeps an `EnumMap<FileType, FileParser>` and rejects two parsers claiming the
same type at construction. No parser can ever be reached for a type it did not
claim, and adding a format is a new parser on the constructor and nothing else.
The orchestrator's contract is that it always returns an outcome: a type with no
reader, or a reader that throws unexpectedly, becomes a stated refusal whose
reason names the exception class — a diagnostic, not file content.

**The CSV reader tokenises without registering a header.** This is the single
most consequential line in the file. Because no header is given to Commons CSV, a
record of the wrong width comes back as a plain list instead of throwing — which
is what makes ragged rows recoverable instead of fatal, and why one bad row
cannot abort a 100k-row file.

**CSV cells arrive typed as `STRING` even when they look numeric.** A delimited
file carries no type information, so typing a cell at read time would bake an
inference into the evidence. `DataTypeValidator` types each column against the
*declared* schema instead, which also lets it report the disagreement.

**Header detection is deliberately conservative.** A candidate row is a header
only if it has ≥2 non-blank cells, at least one matching a letter-initial label
pattern, and at least half its cells label-shaped. Requiring a letter-initial
cell is what stops a headerless numeric export like `INV-1,2024-01-31,100.50` from
having its own first data row promoted to a header and silently lost. A false
negative degrades to positional column names, which is recoverable; a false
positive loses a data row. Where the heuristic is wrong for a given export, the
caller pins it with `HeaderMode` rather than relying on it.

**Duplicate detection canonicalises rather than compares text.** `100` and
`100.00`, `31/01/2024` and `2024-01-31`, and narrations differing only in
repeated spaces are the same row. This targets the more common and more damaging
*false negative*, where the same entry exported twice with different formatting
slips through. Comparison is scoped per sheet, because the same transaction
legitimately appears on two tabs of a location breakdown. The key is joined with
ASCII Unit Separator, which cannot occur in text that survived sanitisation, so
no two genuinely different rows collide into a false rejection.

**Nothing is coerced, defaulted or rounded.** Amounts go through `BigDecimal`
only — no `double` or `float` appears anywhere in the validator package, because
binary floating point cannot represent a rupee exactly. POI's numeric cells are
converted via `new BigDecimal(Double.toString(value))`, the shortest decimal that
round-trips, rather than `new BigDecimal(double)`, which would hand the money
layer `1234.5600000000001`. More than four fraction digits is a *refusal*, not a
rounding, because rounding a source figure during ingestion would change the
audited number with nothing downstream able to tell.

**Ambiguity is refused, never guessed.** `1,234.56` and `1.234,56` are two
different numbers and neither is recoverable from the string alone, so grouping
separators are rejected rather than interpreted. Dates parse with
`ResolverStyle.STRICT` so `2024-02-30` fails instead of becoming 1 March, and only
unambiguous layouts are accepted — `MM/dd/yyyy` is deliberately absent.

**Formula-injection sanitisation distinguishes a signed decimal from a payload.**
Any cell starting with `=`, `+`, `-` or `@` is a formula to Excel, and a leading
tab, CR or LF is a bypass in its own right. But `-1000` and `+4.5` are ordinary
amounts, so a `+`/`-` cell that is not a plain decimal is the only signed case
that gets escaped. Escaping uses the leading apostrophe the spreadsheet
applications themselves understand. Bidi and format characters are removed
outright; embedded newlines are left alone, because a quoted CSV field may
legitimately contain one and silently folding it would change the evidence.

**The clock and randomness are injected.** `asOfDate`, `uploadedAt` and
`ParseRequest`'s timestamp all come from the caller. This is why
`DataQualityValidator` takes `asOfDate` rather than calling `LocalDate.now()`:
an import re-run next month must reach the same verdict about a forward-dated
entry. Similarly, `IngestionStatusService.errorIdFor` derives error ids from the
run id and the finding's position via `UUID.nameUUIDFromBytes` rather than
generating random UUIDs, so re-projecting the same run yields the same
`ingestion_errors.id` values — which is what will make idempotent re-ingestion
possible.

**Content never reaches a message or a log (rule 5).** Findings carry a reason
and a content-free explanation. The archive guard's messages state only the limit
and the observed figure, never entry content. `FileRejection` and
`ValidationFinding.toPersistableMessage()` both truncate to the V3 column width,
so a long diagnostic can never fail the insert and lose the error record entirely.

**Why there is no persistence in this module.** Every value here is immutable and
none is a JPA entity, and the three repositories are placeholders. That is what
lets the entire pipeline — gate, archive walk, both parsers, all five validators —
be exercised as a unit test with no database, no network and no Spring context.
`IngestionStatusService` sits in between: it produces the `IngestionRun` and
`IngestionError` values that a repository will insert, but owns no insert, no
version check and no transaction, because those belong to the wiring milestone.

#### Key comments added

Additive only — no existing comment was deleted or reworded, and no executable
statement, import, signature, annotation or indentation was changed. Tab
indentation, `/** */` Javadoc, sentence-case prose.

**Role documentation for the eight placeholder files**, so an unimplemented file
still states its intent rather than only its TODO: `IngestionController` (the
intended HTTP surface and the four DTOs it would use), `IngestionRepository`,
`IngestionErrorRepository`, `SourceFileRepository`, and the four Spring Batch
files `IngestionJobConfiguration`, `IngestionJobLauncher`, `IngestionProcessor`,
`IngestionWriter`. Each records what the type is *for*, which already-implemented
code it would consume, and the constraint a future implementer must not lose — the
tenant boundary on every run lookup, the uniqueness constraint on re-uploads, that
the writer interprets nothing monetary, and that the launcher must refuse anything
the gate did not admit.

**Per-constant documentation for the enum groups.** `RejectionReason` gained a
one-line meaning for all 33 constants across its six existing groups;
`ColumnType`, `FieldType`, `FileType`, `FileSecurityStatus`, `ParseStatus`,
`RowStatus`, `ValidationSeverity`, `IngestionStatus` and `IngestionStage` gained
per-constant entries. The groups and their rationale were already documented and
were left as written.

**Inline comments at the non-obvious security and accounting decisions:**

- `ContentSniffer.isPlausibleText` — why the head-of-file sample is bounded to
  4 KB, why a NUL is an immediate rejection, why tab/LF/CR are the only
  legitimate control characters, and why the control-character tolerance is 1%
  rather than zero.
- `MalwareScanService.looksLikeNulSmuggling` — why only the opening 4 KB is
  sampled, and why *any* NUL counts.
- `FormulaInjectionSanitiser` — why leading tab/CR/LF are triggers in their own
  right; why a signed decimal is data and not a payload; why stripping precedes
  the payload test (a value that only *looked* dangerous because of a hidden
  character is judged on what will actually be stored); and why NUL and bidi
  characters are dropped rather than escaped.
- `DuplicateValidator` — why ASCII Unit Separator is the key delimiter (it cannot
  occur in sanitised text, so two different rows cannot collide into a false
  rejection), and why `Set.add` returning false makes the *first* occurrence the
  winner.
- `HeaderDetector.looksLikeHeader` — what each of the three counted quantities
  contributes, and why the asymmetry is deliberate: a false negative degrades to
  positional names and is recoverable, a false positive silently loses a data row.
- `FileParseResult.Builder.build` — that the status is derived from what was
  accumulated rather than chosen by the caller, and that a failed result must
  always carry a reason.
- `TextDecoding.decode` — why each BOM reports a body offset so the caller never
  strips twice, and why the no-mark path uses the configured default without
  guessing at it.
- `FilenameSanitiser.sanitise` — the step ordering (leaf name, then character
  folding, then separator collapse, then extension split) and why each step can
  assume the previous one held.

---

**Scope note.** This module currently has **no importers outside
`com.fintech.cfo.ingestion`.** All 348 import statements referencing it come from
within the module (plus its own tests). The contract for downstream consumers is
therefore the declared one, not an observed one: the V3 tables
(`source_files`, `ingestion_runs`, `ingestion_errors`, `source_records`) and
`RowCoordinate.toSourceReference(...)`, which projects an ingested row onto
`shared.domain.SourceReference` so downstream financial modules can link a
calculated variance back to the row that produced it without re-deriving
coordinates.


---

## 4. Financial data — master data & transactions

#### Module goal

`com.fintech.cfo.financial` owns the canonical, source-independent financial record: the six V4 tables
`customers`, `products`, `accounting_periods`, `invoices`, `invoice_lines` and `financial_transactions`,
plus the closed sets of codes that describe them and the mapping layer that projects them to an API.
Its job is to take rows that arrived in whatever dialect the upstream accounting system used — Tally
with lakh/crore grouping, an ERP with western grouping, a bank feed with no invoice at all — and turn
them into one shape in which a reported rupee can be traced back to the uploaded row that produced it.

Three commitments run through every file. **Lineage is part of the shape, not an annotation**: every
canonical record except `AccountingPeriod` carries a `SourceReference`, and a canonical amount without
a pointer back to its source row is not evidence. **Raise, never convert**: there is no exchange rate
anywhere in this module, so a mixed-currency invoice produces a `CURRENCY_MISMATCH` reconciliation
outcome rather than a converted total. **Reported and recomputed figures are kept apart**: the three
totals a source claimed are stored as facts, the totals derivable from the lines are derived on demand,
and `ReconciliationStatus` records which comparison held. Nothing in this module overwrites a source
figure to make a total balance, because that would destroy the evidence of the discrepancy.

A structural caveat that shapes everything below: of the 42 files in the slice, **26 carry real code
and 16 are unimplemented placeholders** — every controller, every normalizer, every repository and
every service. The domain, the enums, the DTOs and the mappers are built; the runtime wiring that would
connect them is not. The journeys below therefore describe the contract the built code encodes and the
placeholders now document, not a path that executes today.

#### File inventory

##### `controller/` — HTTP boundary (all three are placeholders)

| File | Goal |
| --- | --- |
| `controller/CustomerController.java` | Intended read entry point for canonical customer master data. Documents why no write endpoint may accept a caller-supplied `sourceSystem`/`externalKey` pair, and why tenant scoping must come from the security principal rather than a request parameter. |
| `controller/InvoiceController.java` | Intended read entry point for the invoice aggregate. Its key rule: an invoice is never served without its `reconciliationStatus`, and no write endpoint accepts caller-computed totals. |
| `controller/ProductController.java` | Intended read entry point for the catalogue. Records that a product's `currency` is a label only and never a conversion authority for a billed line. |

##### `dto/` — wire contracts (all built, all fully Javadoc'd at component level)

| File | Goal |
| --- | --- |
| `dto/CustomerResponse.java` | Flat projection of `Customer`. Currency is an ISO-4217 string rather than an object so the wire shape does not change when currencies gain attributes. |
| `dto/InvoiceLineResponse.java` | Flat projection of one invoice line. Exposes four amounts plus one shared `currency` scalar instead of four `Money` objects, which is what prevents a client assembling a line whose amounts span currencies. |
| `dto/InvoiceResponse.java` | Flat projection of the invoice header, carrying the reported totals, the lines, and the reconciliation status together so a reviewer can compare both sides. Its compact constructor defensively copies the line list. |
| `dto/ProductResponse.java` | Flat projection of a catalogue entry. `sku` and `externalKey` are independently nullable; `currency` is informational. |
| `dto/TransactionResponse.java` | Flat projection of a transaction. Returns the signed amount exactly as stored and exposes only the type *code*, not the cash-flow classification. |

##### `enums/` — closed code sets (all built)

| File | Goal |
| --- | --- |
| `enums/package-info.java` | States the package rule: every type here is a closed set, so adding a member forces every `switch` over it to be revisited at compile time. |
| `enums/AccountingPeriodStatus.java` | The `OPEN`/`CLOSED`/`LOCKED` period lifecycle and the posting gate. Sealed because whether a period still accepts postings is a business decision, not a display string. |
| `enums/InvoiceStatus.java` | Seven invoice lifecycle states with two derived predicates the service layer asks: `requiresSettlement()` and `isTerminal()`. |
| `enums/ReconciliationStatus.java` | The five outcomes of comparing reported totals against recomputed ones, with `isBalanced()`. Each mismatch kind is distinct so a reviewer sees which figure disagreed. |
| `enums/SourceSystem.java` | The five supported upstream systems. A real `enum` (not a sealed interface) because it is a bare persisted code; its constructor asserts the code equals the constant name and fits `VARCHAR(64)`. |
| `enums/SourceSystemFamily.java` | The number-formatting family a source belongs to, and the two digit-grouping styles the amount parser can validate against. |
| `enums/TransactionType.java` | Ten movement classifications with `affectsCashFlow()` and `expectsPositiveAmount()`. Sealed so classification is never inferred from the sign of an amount. |

##### `mapper/` — projection layer (all built)

| File | Goal |
| --- | --- |
| `mapper/FinancialMappingSupport.java` | The `@MapperConfig` holding every conversion between the shared domain value types and the flat DTO shapes. Each method exists because its source type is a hand-written immutable class, not a JavaBean, so MapStruct's implicit accessor strategy would silently drop it. |
| `mapper/CustomerMapper.java` | Projects `Customer` to `CustomerResponse`. Explicitly does **not** project the resolution key or the external key: both are ingestion concerns. |
| `mapper/ProductMapper.java` | Projects `Product` to `ProductResponse`, under the same all-or-nothing qualification contract. |
| `mapper/InvoiceMapper.java` | Projects the invoice aggregate. Takes the `ReconciliationStatus` as a third explicit source parameter because it is not derivable from the header record — it depends on the lines. |
| `mapper/TransactionMapper.java` | Projects `FinancialTransaction`. The stored sign is returned verbatim; a mapper that normalised it would make an inverted-sign audit finding unreproducible. |
| `mapper/SourceSystemMapper.java` | Projects a `SourceSystem` to its `SourceSystemFamily` with an exhaustive `@ValueMapping`, so a newly added source system cannot silently inherit a parsing profile. |

##### `model/` — canonical records (all built)

| File | Goal |
| --- | --- |
| `model/package-info.java` | States that these are immutable records mirroring a V4 table column for column, with no framework types, and that the migration's constraints are enforced in compact constructors. |
| `model/AccountingPeriod.java` | The tenant's reporting calendar. The one canonical record with no lineage, because a period is a decision made in this system rather than a row imported from one. |
| `model/Customer.java` | Canonical customer master row. Nullable `externalKey` mirrors the partial unique index, so `resolutionKey()` returns `null` rather than manufacturing an identity the database would not enforce. |
| `model/Product.java` | Canonical catalogue entry. Deduplicated exactly as `Customer` is; `sku` is deliberately *not* part of the identity. |
| `model/Invoice.java` | Invoice header carrying the three *reported* totals. Explicitly does not enforce `total == subtotal + tax`, because that relationship is the reconciliation verdict rather than an invariant of the stored value. |
| `model/InvoiceLine.java` | Invoice line, and the module's central arithmetic decision: round the gross once, HALF_UP, at the money scale, and expose the residual against the literal V4 CHECK rather than hiding it. |
| `model/FinancialTransaction.java` | Canonical transaction. The optional `invoiceId`/`customerId`/`accountingPeriodId` and the preserved sign are both load-bearing. |

##### `normalization/` — identity and shaping (one built, four placeholders)

| File | Goal |
| --- | --- |
| `normalization/EntityResolutionKey.java` | The `(organizationId, sourceSystem, externalKey)` triple an upstream record is deduplicated under, mirroring the V4 partial unique indexes. All three components are mandatory; there is no "no external key" representation. |
| `normalization/FinancialDataNormalizer.java` | Intended facade composing the per-entity normalizers. Owns source-profile selection, period resolution, and the ordering of identity decisions. |
| `normalization/CustomerNormalizer.java` | Intended raw-row → `Customer` converter. Decides customer identity once and keeps it; raises on a blank name or unknown currency rather than repairing them. |
| `normalization/ProductNormalizer.java` | Intended raw-row → `Product` converter. Resolves on external key only, never on SKU or name, because the schema deliberately keeps two rows sharing a SKU apart. |
| `normalization/InvoiceNormalizer.java` | Intended header + lines → `Invoice` + `InvoiceLine` converter, and the producer of the `ReconciliationStatus` comparing reported against recomputed totals. |

##### `repository/` — persistence boundary (all five are placeholders)

| File | Goal |
| --- | --- |
| `repository/CustomerRepository.java` | Intended access to `customers`. Its one write-shaped query is the resolution-key lookup, shaped to make `ux_customers_org_source_key` usable and to refuse a wildcard for a null key. |
| `repository/ProductRepository.java` | Intended access to `products`, with the same identity rules and SKU exposed only as a convenience lookup. |
| `repository/InvoiceRepository.java` | Intended access to `invoices`. Documents that the persisted natural key `(org, source_system, invoice_number)` is *not* the same thing as `Invoice.resolutionKey()`, and that both lookups are needed. |
| `repository/InvoiceLineRepository.java` | Intended access to `invoice_lines`, written in terms of "all lines of this invoice" because a line's identity is positional (`invoice_id, line_number`) and has no external key. |
| `repository/FinancialTransactionRepository.java` | Intended access to `financial_transactions`, the highest-volume table. Every documented query is index-shaped, and the period gate is a precondition of the write. |

##### `service/` — orchestration (all four are placeholders)

| File | Goal |
| --- | --- |
| `service/FinancialDataService.java` | Intended module entry point and the only place allowed to move a transaction into an accounting period. Owns the tenant, the transaction boundary, and the period/reconciliation invariants. |
| `service/InvoiceService.java` | Intended invoice orchestration. Header and lines are written or not written together; reconciliation is computed from the lines actually stored. |
| `service/CustomerService.java` | Intended customer orchestration. Resolves the identity key, then updates or inserts inside one transaction, so a duplicate import is idempotent rather than a failed batch. |
| `service/ProductService.java` | Intended catalogue orchestration. Refuses to delete an entry that invoice lines still reference, because the reference is the evidence of what was billed. |

#### Flow of journey

The five stages below are the contract the code encodes. Stages 1, 3 and 4 are implemented; stages 2
and 5 exist only as documented intent.

##### Invoices

1. **Controller** — `InvoiceController` receives a read for a tenant. It resolves no figures of its own
   and returns only through `InvoiceMapper`, which is the single place a canonical record becomes a
   wire shape. A read either asks for lines or does not; "did not ask" is an empty list, never null.
2. **Service** — `InvoiceService` derives the tenant from the security principal, loads the header,
   and (when lines were requested) loads them through `InvoiceLineRepository` **in `lineNumber` order**.
   It then computes the reconciliation outcome from the lines it actually loaded and hands all three to
   the mapper. It owns the transaction boundary, so the header and its line set commit together.
3. **Normalizer** — `FinancialDataNormalizer` resolves the batch's `SourceSystem` and projects it to a
   `SourceSystemFamily` through `SourceSystemMapper`; that family fixes digit grouping and date format
   for every amount and date in the batch. `InvoiceNormalizer` then trims the invoice number (never
   reformatting it, since it is half the natural key), keeps a null due date null, builds each
   `InvoiceLine` through `InvoiceLine` so the HALF_UP rounding happens exactly once, and compares the
   reported subtotal/tax/total against the sums recomputed from the lines.
4. **Mapper** — `InvoiceMapper.toResponse(invoice, lines, reconciliation)` qualifies the tenant, the
   three `Money` amounts, the status code and the four lineage fields through
   `FinancialMappingSupport`, maps `lines` element-wise to the single-argument overload, and takes the
   reconciliation outcome as an explicit third source. It never recomputes a total and never defaults
   the status to `MATCHED`.
5. **Repository** — `InvoiceRepository` resolves the existing header by whichever key the source
   supports: the natural key `(org, source_system, invoice_number)` per `ux_invoices_org_source_number`,
   or the `EntityResolutionKey` when the source supplied an external key and no trustworthy number.
   `InvoiceLineRepository` replaces the invoice's line set as a unit. Both writes are tenant-prefixed in
   the `WHERE` clause.

##### Transactions

1. **Controller** — the read path returns `TransactionResponse` through `TransactionMapper`. There is no
   write endpoint that would let a caller place a transaction into a period of their choosing.
2. **Service** — `FinancialDataService` resolves the accounting period from the transaction date and
   asks `AccountingPeriod.acceptsPostingOn(date)` before anything is written. That is the conjunction of
   two independent questions: is the date inside the window, and does the period still accept postings.
   A date inside a *locked* period is still refused, because the report containing it is signed.
3. **Normalizer** — `FinancialDataNormalizer` supplies the same source profile, then classifies the
   movement into a `TransactionType`. Classification comes from the type, never from the sign. The
   stored sign is carried through verbatim: a source that inverted one is an audit finding, not a
   formatting detail. `financial_transactions.accounting_period_id` stays nullable — a periodless posting
   is legitimate input and is resolved or rejected by the service, not hidden by the model.
4. **Mapper** — `TransactionMapper` unwraps the `Money` to a `BigDecimal` with its sign intact, takes the
   currency from the `Money` itself so the two can never drift, and exposes only the type code so a
   reporting rule can change without a data migration.
5. **Repository** — `FinancialTransactionRepository` resolves the existing row by
   `EntityResolutionKey` per `ux_financial_tx_org_source_key`. When the key is `null` the row is
   outside the index, and the documented rule is explicit: do **not** fall back to a date-and-amount
   match, which would collapse two identical payments on the same day into one.

#### Flow of implementation

**Data model decisions.** Every canonical record is an immutable `record` mirroring one V4 table
column for column — no `@Entity`, no lazy proxies, no framework types. The migration's constraints are
enforced in compact constructors rather than deferred to the database, so an invalid row cannot exist
in memory even before persistence arrives: `ck_accounting_periods_range` becomes a constructor check,
`VARCHAR` widths become named constants such as `InvoiceLine.AMOUNT_SCALE`, and the currency agreement
across a header or a line becomes a private `requireSameCurrency` proof. `created_at`/`updated_at` are
deliberately absent from every record — V4 gives them database defaults, and adding them would mean
calling `now()` inside the domain, which the determinism rule forbids.

Currency is carried *inside* the amounts rather than beside them. `Invoice` has no `currency`
component; its three `Money` values each carry one and the constructor proves they agree, and
`Invoice.currency()` reads it off `subtotalAmount`. `InvoiceLine` does the same across four amounts.
There is therefore no state in which an amount and a currency column could disagree — and no path by
which two currencies could ever be combined, because `Money` itself refuses arithmetic across them.

**Entity-resolution strategy.** Identity is `(organizationId, sourceSystem, externalKey)`, matching the
V4 partial unique indexes `ux_customers_org_source_key` and `ux_products_org_source_key`. The tenant is
part of the key rather than a filter around it, so two tenants may legitimately carry the same external
identifier. `EntityResolutionKey` has no representation for "no external key": its three components are
all mandatory, and `Customer`, `Product` and `FinancialTransaction` return `null` from
`resolutionKey()` in exactly the case where the partial index does not apply. That null is load-bearing
— it is the signal to the ingestion layer that the row must not be matched against anything. Case is
trimmed but never folded, because the source system code is an exact identifier.

`Invoice` is the deliberate exception and the one place the strategy needs care. Its persisted identity
is the V4 natural key `ux_invoices_org_source_number (organization_id, source_system, invoice_number)`,
which is not the same thing as `Invoice.resolutionKey()`, which is built from the nullable
`external_key`. A source that supplies a number and no external key is therefore fully dedupable yet has
no resolution key at all. Both lookups exist and the repository must expose both; using one where the
other was meant either duplicates a re-numbered invoice or merges two invoices that share a number.
`Product.sku` is likewise excluded from identity even though `ix_products_org_sku` exists, because the
schema keeps two rows sharing a SKU apart on purpose.

**Period and reconciliation invariants.** A period is the only canonical record with no lineage, and
the only one whose key is a plain `String` rather than an `EntityResolutionKey` — it is a decision made
in this system. Two questions about a posting date are kept deliberately separate: `contains(date)`
answers the window question from the dates alone, and `AccountingPeriodStatus.acceptsPostingOn(date)`
answers the permission question. `AccountingPeriod.acceptsPostingOn` is their conjunction. Only `Locked`
overrides the date-level answer, and it does so to `false` even for a date inside the window, because
the closed report containing it is already signed.

Reconciliation preserves two independent sets of figures. `Invoice` stores the totals the source
reported and does *not* enforce `total == subtotal + tax` — real sources drift, and overwriting the
reported total with the recomputed one would destroy the evidence of that drift. `ReconciliationStatus`
has five distinct members rather than one generic mismatch, so a reviewer sees which figure disagreed;
`CURRENCY_MISMATCH` is a first-class outcome because a mixed-currency invoice cannot be compared at all
without a rate this system does not have. `InvoiceMapper` takes the outcome as an explicit third
parameter rather than deriving it, so a response can never claim `MATCHED` for money nobody verified.

`InvoiceLine` holds the module's one rounding decision. V4 declares
`CHECK (line_total = (quantity * unit_price) - discount_amount + tax_amount)` with six-decimal
`quantity` and `unit_price` and a four-decimal `line_total`, which the column cannot represent exactly.
The record therefore exposes three related figures: `grossAtSourcePrecision()` (unrounded),
`policyLineTotal()` (round once, HALF_UP, then discount, then tax), and `schemaCheckVariance()` (the
residual against the literal expression, bounded by half the smallest stored unit).
`satisfiesSchemaCheck()` returning `false` is not an error — it is the documented signal that the
persistence pass must relax that CHECK to a rounded comparison. HALF_UP is chosen over HALF_EVEN
because banker's rounding would shave half a unit off every other line in the counterparty's favour,
and this system bills the figure it computes.

**Why the key lines exist.** `FinancialMappingSupport` has ten methods that look like boilerplate and
are not: the domain types are hand-written immutable classes whose accessors (`CurrencyCode.value()`,
`Money.amount()`, the five `SourceReference` accessors) are neither `getX()` nor record components, so
MapStruct's implicit accessor strategy finds nothing and produces `null` without failing. Declaring
each conversion is what stops a silently dropped amount from reaching a report. The four
`SourceReference` methods are `@Named` because four methods converting the same type to the same type
would otherwise be ambiguous. The pom compiles with `-Amapstruct.unmappedTargetPolicy=ERROR` for the
same reason: an unmapped target must break the build, not ship as a null.

`TransactionType.affectsCashFlow()` exists so that cash-flow totals can exclude the
`OPENING_BALANCE`/`CLOSING_BALANCE` snapshots structurally, rather than relying on every caller to
remember the filter; counting a period's own opening and closing balances as movement double-counts
the period. `TransactionType.expectsPositiveAmount()` is a statement of expectation only — the stored
sign is never rewritten to match it. Every `fromCode` in the enums is an exhaustive `switch` on the
normalised string with no default branch, so adding a constant without a case is a compile error rather
than a period silently defaulting to `OPEN` or a transaction to `ADJUSTMENT`. All of them normalise with
`Locale.ROOT`, because under a Turkish default locale `toUpperCase()` maps `i` to `İ` and a lowercase
code would silently fail to match. `SourceSystem.fromCode` never falls back to `GENERIC` either:
`GENERIC` is an explicit assertion that a profile was *assumed*, recorded in the `SourceReference`,
whereas an unrecognised code is a data error.

#### Key comments added

**Enums.** Per-constant meaning for all three `AccountingPeriodStatus` states and all ten
`TransactionType` classifications, which previously carried no Javadoc of their own; `@param`,
`@return` and `@throws` on every `fromCode`. Inline notes on the three decisions each one encodes: why
`CLOSED` and `LOCKED` differ even though both refuse postings; why the switch is exhaustive rather
than a map lookup; why `Locale.ROOT` is a correctness requirement and not a style choice.

**`SourceSystem`.** The constructor's two class-init assertions explained — the code-equals-name check
keeps `code()` and `fromCode` from ever disagreeing, and the width check matters because the code is
part of every `EntityResolutionKey` and would be truncated by the database. `fromCode` documents why an
unknown code is never mapped to `GENERIC`, with the concrete failure it prevents.

**`Invoice`.** The compact constructor annotated line by line, most importantly *what is not
enforced*: no `total == subtotal + tax` identity, because overwriting the reported figure is the one
thing reconciliation exists to prevent. Notes on why `invoiceNumber` is trimmed but never reformatted
(half the natural key), why a null `dueDate` must stay null (defaulting invents a collection deadline),
and why the currency proof is pairwise. `resolutionKey()` carries the warning that invoices have two
identities and the repository must expose both.

**`InvoiceLine`.** Every arithmetic method given `@param`/`@return` plus the reasoning behind it:
why `grossAtSourcePrecision()` is deliberately unrounded, why rounding happens exactly once and only
on the gross, and why `satisfiesSchemaCheck()` returning `false` is a documented state rather than an
error. The rounding-mode constant explains the HALF_UP choice in terms of the counterparty's favour.

**`AccountingPeriod`.** `contains` versus `acceptsPostingOn` split out explicitly, the inclusive-end
reading of the window (an exclusive one would silently drop every transaction on the final day), and
why a period's resolution key is a `String` rather than an `EntityResolutionKey`.

**`Customer`, `Product`, `FinancialTransaction`.** Compact constructors annotated, with the
nullable-versus-required decisions spelled out — in particular why an unmapped `productId` and an
unlinked `invoiceId` must both be storable, because dropping either loses a real charge. `resolutionKey()`
annotated on each with why the `null` branch is the load-bearing case.

**Mappers.** `FinancialMappingSupport` documented method by method with `@param`/`@return`, including
why the `SourceReference` methods must be `@Named` and why `amount` deliberately drops the currency.
`InvoiceMapper` carries the mapping contract in annotated form: every target qualified because no
domain accessor is a JavaBean getter, the reconciliation outcome injected rather than derived, an empty
line list meaning "not requested", and the absence of a per-line reconciliation field. `CustomerMapper`
and `ProductMapper` record what is deliberately *not* projected — the resolution key and the external
key. `TransactionMapper` records that the sign is never normalised and that the type is exposed as a
code only. `SourceSystemMapper` notes that it is the module's only Spring-component mapper and why a
family is not one-to-one with a system.

**`EntityResolutionKey`.** Why there is deliberately no "no external key" representation, why components
are trimmed but never case-folded, and why a blank external key is rejected — every unkeyed record
would otherwise collapse into one under the index.

**DTOs.** Short notes on the design each shape enforces: the `List.copyOf` in `InvoiceResponse` and why
an empty list is legitimate, the absence of a per-line reconciliation field and of `sourceFileId` on
the line, the informational-only catalogue currency, and the preserved transaction sign.

**Placeholders.** All sixteen stub files — every controller, normalizer, repository and service — now
carry a class-level Javadoc stating the type's role, its key design decision, and the specific
invariants it must honour when implemented: index-shaped and tenant-prefixed repository queries,
header-and-lines-atomic writes, tenant derived from the principal rather than a parameter, and no
currency arithmetic or clock access in any service. The existing `TODO` comments were left in place
below them.

---

## 5. Financial truth engine — expected vs actual

#### Module goal

`com.fintech.cfo.financialtruth` answers one question for every invoice line: *what should
this customer have been charged, what were they actually charged, and where does the
difference come from?* It is the deterministic core of the product. Given a frozen
snapshot of an invoice and the contract terms in force on a stated business date, it
produces an expected amount, an actual amount, a signed variance, a classification of
which component that variance belongs to, and a per-currency monetary impact — all of it
reproducible months later from the inputs the run itself recorded.

Three commitments run through every file.

**It never invents a figure.** This is the module's defining property and it is enforced
structurally, not by convention. A rule that cannot read its inputs reports
`INCOMPLETE_INPUTS` and carries no amount at all — not a zero. A rule that cannot proceed
because the *contract* does not support the conclusion raises `BusinessRuleException`
rather than returning a number nobody agreed to. Three separate constructors
(`RuleEvaluationResult`, `CalculationResult`, and `RuleStatus.carriesMonetaryClaim()`
consulted by both) refuse to build an object whose status says "no claim" while its
amounts say otherwise. The engine substitutes no default anywhere, and
`ImpactAggregator` rejects component rows outright rather than trusting the caller to
have filtered them.

**It is reproducible by construction, not by discipline.** The engine has no repository,
no HTTP client and no ambient configuration; it is handed a `CalculationInput` and a
`Clock`. The as-of date is mandatory and travels with the input. Rules are sorted and
de-duplicated by code, so the rule-set fingerprint is a property of the rules rather than
of injection order. Terms are selected by an explicit total ordering. Money is rounded
once, per component, under one policy. And the run id is supplied by the caller, never
generated — generating it would inject entropy into the very record whose purpose is to
be reproduced.

**It never crosses currencies.** There is no FX component anywhere in this module. A
pricing term in a currency the line is not denominated in raises. A fixed-amount discount
in another currency raises. An invoice set spanning currencies yields one impact per
currency in a fixed currency-code order, and `CalculationService.netVarianceIn` raises
rather than collapse two currencies into one total.

A structural caveat that shapes everything below: of the 54 files in this slice, **46 carry
real code and 8 are unimplemented placeholders** — both controllers, both repositories
and the entire `processing/financialtruth` batch job. The engine, the calculators, the
rules, the model, the enums, the DTOs and the services are built; the HTTP boundary, the
persistence layer and the batch path are not. No other module in the repository currently
imports `financialtruth` (only four `CodedEnum` copies and one exception class mention it
in prose), so this is a self-contained slice awaiting its composition root. The journeys
below therefore describe the contract the built code encodes and the placeholders now
document, not a path that executes end to end today.

#### File inventory

##### `calculator/` — the deterministic arithmetic (all built)

| File | Goal |
| --- | --- |
| `calculator/FinancialTruthEngine.java` | The composition root of the module's logic: sorts and validates the rule set, pins the rule-set version, builds a closed `RuleContext` per line, records one result row per rule, composes the authoritative combined net row per line, and aggregates impacts per currency. Owns the net-vs-components reconciliation check and the rule-status-to-confidence mapping. |
| `calculator/ExpectedAmountCalculator.java` | Turns contract terms into the amount that should have been charged: contracted gross, per-term discount entitlements, and the net. Owns one rounding policy for the whole expected side and refuses to invent a price, default a missing term to zero, or convert currencies. |
| `calculator/ActualAmountCalculator.java` | Reads what was actually charged off an invoice line, mirroring the expected-side normalisation and single rounding step exactly so the two sides of a comparison differ only in figures, never in method. |
| `calculator/VarianceCalculator.java` | Produces variances under the module's single sign convention and asserts the internal consistency guard: a reported net variance must equal the algebraic combination of its disjoint components, or the run fails. |
| `calculator/ImpactAggregator.java` | Rolls per-line combined variances up into one impact per currency. Accepts combined rows exclusively (rejecting component rows), never crosses currencies, sorts rows before summing, and downgrades a total's confidence when it omitted lines. |
| `calculator/package-info.java` | States the package rule: the two amount calculators together own the rounding policy, the variance calculator owns decomposition, and the aggregator owns honest totals. |

##### `model/` — the input snapshot and every result carrier (all built)

| File | Goal |
| --- | --- |
| `model/CalculationInput.java` | The frozen snapshot the engine is allowed to read, and the owner of the canonical form hashed into `calculation_runs.input_checksum`. Selects effective pricing and discount terms by explicit total orderings, and hashes term *values* as well as term identities so an in-place contract amendment changes the digest. |
| `model/InvoiceLineInput.java` | One normalised invoice line — the actual side of a comparison. Rejects mixed currencies at construction and distinguishes a missing quantity (unusable) from a missing discount or tax (zero). |
| `model/PricingTerm.java` | A versioned contract pricing term with an inclusive effective window, and the authority a calculation cites for an expected price. Carries its own declared bounds and refuses to be used when it contradicts them. |
| `model/DiscountTerm.java` | A versioned contract discount term. The currency is required only for `FIXED_AMOUNT` and for any capped term; a percentage inherits the currency of the gross it reduces. A negative value or a percentage above 100 is rejected at construction. |
| `model/RoundingPolicy.java` | The module's single rounding and scale policy: 4dp money (`NUMERIC(20,4)`), 6dp quantity and unit price, `HALF_UP`, 10 extra digits for percentage-rate expansion. Also owns the scale-insensitive canonical forms that make checksums stable across a database round trip. |
| `model/Variance.java` | The signed difference, `actual - expected`, with the type attached. A final class rather than a record because it computes its amount once in `Money`, so no caller can present an amount that disagrees with its own expected and actual figures. |
| `model/ExpectedValue.java` | The expected amount together with its derivation and the contract versions consulted — a figure that can be re-derived rather than merely recomputed. |
| `model/ActualValue.java` | The actual amount together with the source invoice rows it came from. Lineage is mandatory: an amount that cannot be walked back to its source row is not shippable. |
| `model/TermEvaluation.java` | The lineage record for one consulted term: id, type, version and effective window. Enters the input checksum, so its format is part of the reproducibility contract. |
| `model/CalculationResult.java` | One persisted-shaped finding, mapping column-for-column onto `calculation_results`. Enforces the central invariant in its compact constructor: a status that carries no monetary claim must arrive with no amounts. |
| `model/CalculationRun.java` | The run record that makes a result defensible: input checksum, rule version, lifecycle timestamps, and the `deterministicFingerprint()` that is the proof two executions agree. Requires a caller-supplied run id and validates that COMPLETED runs have a completion instant and FAILED runs have a reason. |
| `model/FinancialImpact.java` | A total in one currency with a direction, a confidence, the evaluated and unevaluated line counts, and a rationale. Always carries its currency — a total without one is the failure this module exists to prevent. |
| `model/package-info.java` | States that every type is a record except those owning a derived invariant a record cannot express, and that no `double` or `float` appears anywhere in the package. |

##### `rules/` — the pluggable variance checks (all built)

| File | Goal |
| --- | --- |
| `rules/FinancialRule.java` | The interface every commercial check implements. Deliberately free of framework annotations: a rule is a plain object with constructor-injected collaborators, so it can be exercised in a unit test with no container, database or clock. |
| `rules/RuleContext.java` | Everything one rule may see about one line: the line, an explicit as-of date, the currency, and the terms in force on that date. A closed view — a rule cannot reach another line, query contracts, or read a clock. |
| `rules/RuleEvaluationResult.java` | What one rule concluded, enforcing that the three findings — evaluated, not-applicable, incomplete-inputs — stay distinguishable and that the two latter carry no monetary figures at all. |
| `rules/PricingVarianceRule.java` (`PRICING_VARIANCE@1.0.0`) | Compares the invoiced unit price against the contracted unit price on the gross amount only. Raises when no contract price was in force or when currencies disagree; reports incomplete inputs for an unevaluable pricing type or an unusable quantity. |
| `rules/DiscountVarianceRule.java` (`DISCOUNT_VARIANCE@1.0.0`) | Compares the discount granted against the entitlement derived from the contracted gross, applying every effective term in the fixed order and clamping by each term's cap and by the gross. Disjoint from the pricing rule so no deviation is counted twice. |
| `rules/package-info.java` | States that rules are pure, that both shipped rules refuse to produce a figure they cannot justify, and why `RuleContext` and `RuleEvaluationResult` are final classes rather than records. |

##### `enums/` — the closed value sets (all built)

| File | Goal |
| --- | --- |
| `enums/VarianceType.java` | The three stored variance categories — `PRICING`, `DISCOUNT`, `COMBINED` — as a sealed interface of records so every `switch` is exhaustiveness-checked. Documented as deliberately having no `NONE` variant: a documented constant no code path can reach is worse than none. |
| `enums/RuleStatus.java` | `EVALUATED` / `NOT_APPLICABLE` / `INCOMPLETE_INPUTS`, sealed. The distinction between the latter two is the whole point of the type: "the contract entitles nothing" and "I could not read the contract" are different findings. Carries the schema note that V6 has no column for this status. |
| `enums/CalculationStatus.java` | `PENDING` / `RUNNING` / `COMPLETED` / `FAILED`, sealed, with `isAuthoritative()` true only for `COMPLETED`. A run that failed produced no authoritative result. |
| `enums/CalculationType.java` | Which calculation a result row belongs to. `COMBINED_VARIANCE` is the only type the impact aggregator accepts, which is what prevents double counting. |
| `enums/CalculationConfidence.java` | `HIGH` / `MEDIUM` / `LOW` as a statement about evidence completeness, never about sign. Ordered by an explicit `isLessConfidentThan` relation rather than by `ordinal()`. |
| `enums/PricingType.java` | The three contract pricing shapes. Only `FIXED_UNIT_PRICE` is evaluable today; the other two are modelled explicitly so an unsupported term is reported rather than silently read as a fixed price. |
| `enums/DiscountType.java` | `PERCENTAGE` and `FIXED_AMOUNT`, and the rule that only the latter carries a currency. |
| `enums/ImpactDirection.java` | `CUSTOMER_OVERPAY` / `CUSTOMER_UNDERPAY` / `NEUTRAL`, derived from the sign of a variance and never supplied, so a report need not remember the sign convention. |
| `enums/CodedEnum.java` | The shared contract for the closed sets whose variant is also the persisted `VARCHAR` value: one stable code per variant, strict `fromCode` resolution that fails loudly on an unknown code, and a column-width guard so an over-long variant is refused at the boundary. |
| `enums/package-info.java` | States why some sets are sealed interfaces and others remain enums, and that each `code()` is both the persisted string and the hashed string. |

##### `service/` — lifecycle and reproducibility (all built)

| File | Goal |
| --- | --- |
| `service/CalculationService.java` | The application-facing entry point: run the engine, expose the checksum and rule-set version, and read back the authoritative combined rows and per-currency impacts. Infrastructure-free — the audit wiring it would need requires a JPA repository, so it is left to the composition root. |
| `service/CalculationRunService.java` | Owns the run lifecycle: `start` records checksum, rule version and period before any figure is produced; `complete` refuses to close a run with results from a different run id or different inputs; `fail` preserves the checksum and truncates the reason to the `VARCHAR(2000)` column. |
| `service/ReproducibilityService.java` | Turns "we believe it is reproducible" into "we have checked". Replays a stored snapshot under the original run id and compares checksum, rule version, result count, every result's canonical form and the run fingerprint — collecting all differences rather than short-circuiting. |
| `service/package-info.java` | States that every service takes its collaborators and a `Clock` where it needs time, and that no Spring or I/O appears in this package. |

##### `dto/` — the wire shape (all built)

| File | Goal |
| --- | --- |
| `dto/RunCalculationRequest.java` | The client request, with money modelled as decimal strings and parsed with `new BigDecimal(...)`. Deliberately carries no organization id: tenant scope must come from the authenticated principal, never from a request body. Contains the nested line, pricing-term and discount-term request records and their converters. |
| `dto/CalculationResponse.java` | One result row as reported, carrying its rule code and version because a figure without the version that produced it cannot be defended later. Nullable amounts are explicit, not incidental. |
| `dto/CalculationRunResponse.java` | A run as reported, exposing `inputChecksum`, `ruleVersion` and the deterministic fingerprint on the wire: a client that cannot see them cannot reproduce anything. |
| `dto/VarianceResponse.java` | A variance plus a derived `direction` so consumers need not re-derive the sign convention, and a `percentageOfExpected` that is omitted rather than sent as zero when the expected amount is zero. |
| `dto/FinancialImpactResponse.java` | The per-currency impact with its evaluated/unevaluated counts and its mandatory rationale, because a total whose limits are not stated cannot be defended. |
| `dto/AmountResponse.java` | An amount and its currency as two fields, matching the V6 `NUMERIC`/`CHAR(3)` column pairs and refusing to emit a non-null amount with a null currency. |
| `dto/package-info.java` | States that these are transport shape only and never compute, and that every optional field is explicitly `@Nullable` because "no expected amount was established" is a real answer. |

##### `repository/` — persistence (both are placeholders)

| File | Goal |
| --- | --- |
| `repository/CalculationRunRepository.java` | Intended home of the `calculation_runs` row: the input checksum, rule version and lifecycle timestamps. Documents the tenant-scoped lookup, the opaque-checksum rule, and the 2000-character failure-reason bound shared with `CalculationRunService`. |
| `repository/CalculationResultRepository.java` | Intended home of the per-run result rows. Documents the tenant-scoped, line-ordered read whose ordering the positional reproducibility comparison depends on, the null-means-unevaluated column rule, and append-only semantics so a re-run writes a new run rather than overwriting an audit trail. |

##### `controller/` — the HTTP boundary (both are placeholders)

| File | Goal |
| --- | --- |
| `controller/CalculationController.java` | Intended synchronous single-invoice entry point, the interactive counterpart to the batch job. Documents that tenant scope comes only from the principal, money arrives as decimal strings, no silent zero is rendered for an unevaluable line, and a missing contract price surfaces as a typed failure. |
| `controller/CalculationRunController.java` | Intended audit-facing read and reproduction endpoint. Documents that the response must carry its own proof, that reproducibility is returned as a reviewable verdict rather than thrown, that only authoritative runs are served, and that stored figures are never recomputed in place. |

##### `processing/financialtruth/` — the batch job (all four are placeholders)

| File | Goal |
| --- | --- |
| `processing/financialtruth/CalculationJobConfiguration.java` | Intended Spring Batch job and step definition. Documents that chunk size is the transaction boundary, that job parameters carry the as-of date and tenant filter so a re-run selects the same invoices, and that a restart must be idempotent by run id. |
| `processing/financialtruth/CalculationProcessor.java` | Intended per-item bridge from a batch item to the engine: assemble a `CalculationInput`, invoke the engine, return the completed run. Documents stable run ids, a job-parameter as-of date rather than `today()`, one item per invoice so no total spans a chunk boundary, and per-item failure rather than a fatal abort. |
| `processing/financialtruth/CalculationWriter.java` | Intended persistence of each run. Documents one transaction per run, fixed row order, null-preserving money columns, idempotence by run id, and the rule that a wrong stored figure is a finding to report rather than a value to correct in place. |
| `processing/financialtruth/CalculationJobLauncher.java` | Intended programmatic job entry point for operators and month-end orchestrators. Documents that parameters must be explicit and complete, that no business logic belongs here, and that relaunching with identical parameters is the supported resume path. |

#### Flow of journey

The synchronous path a calculation request takes, as the built code defines it:

1. **Request arrives.** `CalculationController` (placeholder) receives a
   `RunCalculationRequest`. The compact constructor has already refused a blank invoice
   number, a missing as-of date, or an empty line list — "an invoice with no lines cannot
   be reconciled". Tenant scope is read from the authenticated principal, not the body.
2. **Wire shape becomes a snapshot.** `RunCalculationRequest.toInput(organizationId,
   triggeredBy)` parses every decimal string with `new BigDecimal(...)`, resolves the
   currency first and uses it as the reference for the discount and tax amounts, and
   builds a `CalculationInput`. Absent quantity stays null (unusable); absent discount or
   tax becomes an explicit zero in the line currency.
3. **Snapshot validated and frozen.** `CalculationInput`'s compact constructor requires an
   as-of date, rejects one earlier than the invoice date, defensively copies all three
   lists, and rejects an empty line list. The checksum is computed once here over the
   canonical form — version-prefixed, terms sorted by id then version, term *values*
   included so an in-place amendment changes the digest.
4. **Run opened (optional but intended).** `CalculationRunService.start` records the run
   as RUNNING with the checksum, the rule-set version and the period, before any figure
   exists.
5. **Engine invoked.** `CalculationService.calculate` delegates to
   `FinancialTruthEngine.calculate(input, runId)`, passing the caller's run id through
   unchanged. The engine reads its injected `Clock` exactly once, so under a fixed clock
   every row in the run carries one `evaluatedAt`.
6. **Rules pinned.** The constructor sorted the rule list by code then version and rejected
   duplicate codes — two rules sharing a code would silently overwrite each other's rows in
   the per-calculation map. `ruleSetVersion()` joins `code@version` with `+`, giving the
   value stored in `calculation_runs.rule_version`.
7. **Per-line context built.** For each `InvoiceLineInput`, a `RuleContext` is assembled
   from the line, the as-of date, the line currency, the effective pricing term (winner of
   `effectivePricingTerm`, ranked by version desc → effectiveFrom desc → termId) and the
   effective discount terms in their fixed application order.
8. **Rules dispatched.** Each rule is asked `appliesTo` first; a rule that declines yields
   `NOT_APPLICABLE` naming the line. Otherwise `evaluate` runs and either produces an
   `EVALUATED` finding or returns `INCOMPLETE_INPUTS` with a reason. Every finding is
   recorded as its own result row tagged with rule code and version, and mapped to a
   confidence by an exhaustive `switch` over `RuleStatus` — HIGH for evaluated, LOW for the
   two claim-free statuses.
9. **Expected side measured.** `PricingVarianceRule` normalises price and quantity to 6dp
   and rounds once to 4dp `HALF_UP`, after asserting the term is within its own declared
   bounds. `DiscountVarianceRule` takes the *contracted* gross as the base (never the
   invoiced one — that would compare the invoice against itself), converts each percentage
   rate to a 10dp multiplier before applying it, clamps each term by its own cap and then
   by the gross, and folds the stack in the recorded order.
10. **Combined row composed.** The engine builds `expectedNet = expectedGross −
    expectedDiscount + tax` and `actualNet = actualGross − actualDiscount + tax`. Tax is
    contractual pass-through, so it is identical on both sides and contributes nothing to
    the variance while remaining part of the payable. The component sum
    `pricingVariance − discountVariance` is built *from zero* rather than from the net
    figure, so the reconciliation assertion is a real comparison and not a tautology.
11. **Reconciliation asserted, or the gap disclosed.** Where the decomposition is provable
    — the discount entitlement was measured, or the invoice granted no discount —
    `VarianceCalculator.assertReconciled` compares by value (scale-insensitive) and raises
    on any mismatch. Where an invoice granted a discount no entitlement covers, the total
    is still reported, but at MEDIUM confidence with the gap stated in the explanation:
    no component row exists that could explain it.
12. **Impact rolled up.** Only combined rows reach `ImpactAggregator`, which rejects
    anything else outright. Rows are sorted by line number, summed from already-rounded
    values, and each currency total measures its coverage against only the lines
    denominated in its own currency. Confidence is inherited as the worst of its rows and
    downgraded once more if any line in that currency was not evaluated. The rationale
    states the evaluated and unevaluated counts in plain words.
13. **Run closed.** The engine returns a `CalculationRun` marked COMPLETED, with the same
    instant for `startedAt` and `completedAt`. `CalculationRunService.complete` re-checks
    the status, the run id and the input checksum before accepting the close, and its
    compact constructor requires both a completion instant and, had anything failed, a
    reason.
14. **Reported.** `CalculationRunResponse.from` exposes the results, the impacts, and the
    three pieces of proof — `ruleVersion`, `inputChecksum` and
    `deterministicFingerprint()`, computed on demand so it can never drift from the results
    it describes.
15. **Reproducibility checked.** `ReproducibilityService.verify` replays a freshly reloaded
    snapshot under the *original* run id and compares six things, collecting every
    difference. `requireReproducible` is the strict form: it raises naming all differences
    and then re-checks at the lifecycle level via `CalculationRunService`.

The batch path, as the four placeholders now document it:

1. **Launch.** `CalculationJobLauncher` builds explicit `JobParameters` — the as-of date
   and tenant filter — and starts the job from `CalculationJobConfiguration`. Without the
   as-of date, terms could not be selected deterministically.
2. **Read.** The step's reader yields one item per invoice: an invoice snapshot plus a run
   id derived from the job parameters and the invoice, so a restart re-derives the same id
   rather than generating fresh entropy.
3. **Process.** `CalculationProcessor` assembles the `CalculationInput`, calls the engine,
   and returns the completed run. A missing contract price is recorded as a FAILED run
   with a reason and the chunk continues — aborting would discard every already-computed
   finding behind one bad invoice. One item is one invoice, so a total never spans a chunk
   boundary.
4. **Write.** `CalculationWriter` persists the run row and all its result rows in one
   transaction per run, in fixed order, with null money columns preserved and idempotence
   keyed on run id. A chunk commits atomically, so a failure leaves whole runs either
   written or absent — never a COMPLETED run with half its lines.
5. **Resume.** Relaunching with identical parameters resumes rather than duplicating,
   because both the processor's run ids and the writer's persistence are derived from them.

#### Flow of implementation

**Determinism and reproducibility invariants.** Five mechanisms carry this, and each is
implemented as a structural refusal rather than a convention. First, the engine holds no
repository and no HTTP client; `CalculationInput` is the only data source and the injected
`Clock` the only source of time, read once per run so a fixed clock makes a run
byte-identical on replay. Second, rules are sorted by code then version and duplicate codes
are rejected at construction — without the duplicate check, two rules sharing a code would
silently overwrite each other's results in the per-calculation map and one finding would
vanish from the run. Third, term selection uses explicit total orderings: pricing falls back
through version desc → effectiveFrom desc → termId, discounts apply through
effectiveFrom asc → version asc → termId, so an ambiguous contract cannot make the engine
non-reproducible. Fourth, checksums are content-addressed over canonical strings that sort
their inputs — `CalculationInput` sorts terms by id and version before hashing because the
caller's load order carries no financial meaning, and `RoundingPolicy.canonicalNumber`
strips trailing zeros so a value re-read as `920.0` rather than `920.00` does not report a
spurious input change. Fifth, the run id is caller-supplied: `FinancialTruthEngine` rejects
a null one with "a generated id would defeat reproducibility", and the reproducibility
service replays under the original id rather than a fresh one.

`CalculationRun.deterministicFingerprint()` is the proof, and it includes the run id, the
rule version, the input checksum, both lifecycle instants and every result and impact
canonical form, in the engine's own evaluation order. Results are appended in list order and
never re-sorted, so an ordering regression shows up as a fingerprint change instead of
hiding.

**Rounding discipline.** One policy, declared once in `RoundingPolicy` and applied
identically on both sides of every comparison. Money is 4 decimal places to match
`NUMERIC(20,4)`; quantity and unit price are 6 to match `NUMERIC(20,6)`; the mode is
`HALF_UP`, chosen because it is symmetric about zero and independent of magnitude, so it
introduces neither the upward drift of `CEILING` nor the downward drift of `DOWN`. Every
*line component* is rounded exactly once, at the point it is produced; totals are the plain
sum of already-rounded components and are never re-rounded, because re-rounding an
already-rounded sum would make a total depend on line order. The policy is sound precisely
because rounding per component makes the arithmetic associative. Percentage rates are
converted to multipliers at `DISCOUNT_RATE_SCALE` (10 extra digits) *before* the single
monetary rounding step, so the recurring expansion of a rate such as one third of a percent
cannot decide the answer. The two amount calculators normalise identically and round
identically — that symmetry is the reason a variance isolates the commercial deviation
rather than a difference in arithmetic.

**Variance taxonomy.** One convention module-wide: `variance = actual − expected`, so a
positive figure always means the customer was charged more than the contract entitles. The
taxonomy is three disjoint components plus one composed total. `PRICING` measures the gross
(`quantity × unit price`) and is disjoint from `DISCOUNT`, which measures the discount
amount itself — so a positive discount component means the customer received *more*
discount than entitled, and it works against the supplier. That is the single sign flip in
the module: the net payable subtracts the discount, so `net = pricingVariance −
discountVariance`. `COMBINED` is the authoritative figure an auditor reports, and the
components exist only to explain it. The type is carried on every `Variance` precisely so a
reader cannot apply the customer-facing reading to a component. `ImpactDirection` is derived
from the sign rather than supplied, so no report has to remember the convention.

`RuleStatus` supplies the other half of the taxonomy, and the distinction is the point of
the type. `EVALUATED` carries a number. `NOT_APPLICABLE` is a true statement — the contract
entitles nothing — and the engine may then treat the entitlement as zero, because an absent
entitlement is not a fabricated one. `INCOMPLETE_INPUTS` means the rule could not read the
contract, and the engine refuses the line's combined row entirely: "a discount entitlement
we could not read is not an entitlement of zero." Both claim-free statuses are enforced to
carry no amounts by construction.

**Design decisions and why the key lines exist.** The engine composes one combined row per
line and asserts it reconciles exactly with `pricingVariance − discountVariance`. The
component sum is built from zero rather than from the net figure so the assertion is a
genuine comparison rather than a tautology — the check that would otherwise let a discount
be counted twice. That assertion is deliberately *not* made where the line carries a
discount no entitlement covers: there is no component row that could produce the total, so
asserting it would compare a figure against something that cannot reach it. The total is
still reported, at MEDIUM confidence, with the gap spelled out in the explanation.

`ImpactAggregator` accepts combined rows exclusively and throws on anything else, rather
than trusting the caller to have filtered. This is the second guard against double counting,
and it is why `CalculationRun.combinedResults()` is the single place component rows are
excluded. Each per-currency total measures its coverage against only the lines denominated
in its own currency, because dividing by a currency-blind count would make every total on a
multi-currency invoice claim that the *other* currency's lines had been excluded from it —
a false statement in a document a finance team acts on. And a total that omits lines is
never presented at HIGH confidence, however exact the figures it does contain are.

Clamping rather than failing is a deliberate choice in `ExpectedAmountCalculator.clamp`:
contracts routinely promise a credit greater than a particular line, and the payable still
has to be arithmetically sound. The term's own cap is applied first, then the hard ceiling
at the gross, and `DiscountVarianceRule` discloses the clamp in its explanation rather than
passing over it in silence. That disclosure is guarded on non-zero so a genuine zero
entitlement is not described as a cap.

Confidence is a statement about evidence completeness, never about sign, and it is derived
rather than supplied. `confidenceFor` is an exhaustive `switch` over the closed
`RuleStatus` set, so adding a variant is a compile error rather than a silent fall-through
to a wrong confidence. `NOT_APPLICABLE` is LOW on purpose: the rule made no claim, and a row
carrying no figure must not look like one that does. A total inherits the *worst* confidence
of its rows — one unreadable line among twenty makes the twenty-line total a statement about
nineteen lines — and is downgraded once more if any line in that currency was unevaluated.

Currency handling is uniform: never convert, never mix, always raise. `Money.subtract`
raises on a currency mismatch, which is exactly right, and the calculators add explicit
guards with the same rule for terms that name a currency rather than carrying an amount. A
contract term that contradicts itself — a price outside its own declared bounds, a negative
maximum discount — cannot be used as an authority and raises.

Finally, the module distinguishes raising from reporting. Where the *contract* does not
support a conclusion — no pricing term in force, currencies disagree — the rule raises
`BusinessRuleException`, because there is no defensible expected amount and returning zero
would report an invoice as clean when in fact nothing was ever checked against anything.
Where an input exists but is unusable — a `LIST_PRICE` term, a missing quantity, a discount
above 100% — the rule returns `INCOMPLETE_INPUTS` with a reason, because the contract may be
perfectly valid and simply outside the implemented basis. Conflating these two would either
crash on valid contracts or silently under-report on broken ones.

#### Key comments added

**`FinancialTruthEngine`** — why the `Clock` is read once (`// Read once. A fixed clock
therefore stamps an entire run with one instant.`); why the per-calculation map is a
`LinkedHashMap` (two rules claiming one type would displace each other silently); why an
absent discount entitlement may be zero while an unreadable one may not; why tax is carried
identically on both sides; why the component sum is built from zero rather than from the
net figure, making the reconciliation check a real comparison; why the assertion is skipped
for an unauthorised discount and the total still reported at reduced confidence; why both
lifecycle timestamps carry the same instant.

**`ExpectedAmountCalculator`** — that operands are normalised to storage scales *before*
multiplying, so the arithmetic cannot depend on how many decimals a source happened to
supply; that the single rounding step happens at the point the amount is produced so no
caller can round differently; that a rate is divided to `DISCOUNT_RATE_SCALE` rather than to
monetary scale, so the rounding of a recurring expansion cannot decide the rounding of the
money; that the hard ceiling exists because a discount exceeding the gross would make the
net payable negative.

**`ActualAmountCalculator`** — that its normalisation and single rounding step are identical
to the expected side's *on purpose*, so the two sides differ in figures and never in method;
that the discount is read from the invoice row rather than re-derived, since re-deriving it
would compare the contract with itself.

**`VarianceCalculator`** — the sign flip on the discount component, marked as the single
sign flip in the module; that the fold rounds once *after* summing already-rounded
components; that the currency check precedes the amount comparison, and that the comparison
uses `compareTo` because `100.0000` and `100` are the same money.

**`ImpactAggregator`** — that the `TreeMap` under an explicit comparator exists so
per-currency impacts come back in a reproducible order; that a total inherits the weakest
confidence of its rows rather than the strongest; that the unevaluated count is floored at
zero so a disclosure can never state a negative exclusion; that `requireCombinedOnly` skips
nulls but *rejects* component rows, and that only rows actually carrying a figure
contribute.

**`RoundingPolicy`** — why the absent-value marker is a non-numeric dash (it can never
collide with a numeric field) and why `toPlainString` is used instead of `toString` (exponent
notation must never reach a checksum); why money is emitted as `amount currency` in that
fixed order.

**`CalculationInput`** — that the defensive copies matter because the checksum is hashed once
and compared against a replay months later; that an empty line list is rejected because a
total of zero from no lines is indistinguishable from a clean invoice; that `effectivePricingTerm`
sorts rather than takes a `max` (the comparator is total, so the winner does not depend on
stream order); that the canonical form is version-prefixed so a future layout change cannot
collide with existing digests; why invoice numbers are trimmed before hashing.

**`Variance`** — that the amount is computed once and never reassigned so it cannot drift from
its own components; that the percentage is absent rather than zero when expected is zero;
that `equals` compares all four fields so two variances agreeing numerically but disagreeing
on their split are not the same finding.

**`CalculationResult` / `CalculationRun`** — that both cross-field invariant blocks are
enforced at construction so no factory or caller can bypass them; that `combined` reads
confidence from the impact rather than accepting it separately, to avoid two sources of
truth; that a null impact on a component row is deliberate; that the fingerprint includes
the run id so two independent runs of one invoice are correctly reported as different
records; that results are appended in list order and never re-sorted, so an ordering
regression surfaces as a fingerprint change.

**`PricingVarianceRule` / `DiscountVarianceRule`** — the guard *order* in each rule (input
usability → contract authority → contract shape), why each early return names its specific
reason; why no pricing term raises but an unevaluable pricing type returns incomplete
inputs; that the discount base is the *contracted* gross because a percentage of the
invoiced gross would compare the invoice against itself; that every term is validated before
any is applied, so a partial stack is never produced; that the clamp disclosure is guarded
on non-zero.

**`RuleContext` / `RuleEvaluationResult`** — that `Objects.requireNonNull` is used for
structural fields while `ValidationException` is used where there is a business reason; that
the discount list is copied so a rule cannot mutate or reorder the caller's snapshot; that
the two claim-free accessors return null so every caller must branch on `isEvaluated()`.

**`CalculationRunService`** — why `complete` runs three guards before accepting a close; why
`fail` drops results rather than partially retaining them (a FAILED run with half its rows
invites a reader to sum figures the engine never finished); why the reproducibility checks
run in checksum → rule version → fingerprint order (diagnostic: a changed checksum explains
itself); why the failure reason is truncated rather than rejected, and why a blank message is
replaced rather than stored.

**`ReproducibilityService`** — that the replay reuses the original run id so the two runs
are comparable; that every difference is collected rather than short-circuited; that the
input checksum is compared first because it is the cheapest and most explanatory; that
result comparison is positional over the shorter length with the size mismatch reported
separately, so nothing is silently dropped; that comparison uses the canonical form because
it includes lineage and the instant, not just the amounts.

**Placeholder files** — each of the ten now carries the boundaries its implementation must
hold: tenant scope from the principal only; money as decimal strings; no silent zeros; null
money columns meaning unevaluated rather than zero-variance; fixed result ordering because
the reproducibility comparison is positional; one transaction per run; append-only
persistence; chunk size as the transaction boundary; per-item failure rather than a fatal
abort; and the rule that a wrong stored figure is a finding to report, never a value to
quietly correct.


---

## 6. Contracts & commercial terms

#### Module goal

`com.fintech.cfo.contract` owns the commercial position of a tenant: what a product is worth, what
discount applies, what obligations a contract carries, and which machine-checkable rules were in force
on a given day. It is the only module that answers **as-of questions about money** — for a contract, a
product, a customer and a date, which terms were in force, and therefore what must be charged and
which rules apply. It reads the five V5 tables `contracts`, `contract_terms`, `pricing_terms`,
`discount_terms` and `commercial_rules`.

Four commitments run through every file, and each one is a deliberate refusal rather than an
omission. **The as-of date is always a parameter.** No service in this slice reads a clock; a
calculation performed eighteen months ago re-derives byte-identically, and that property is the only
reason `term_version` exists in the schema. **Absence is reported, never defaulted.** A missing
discount is a legitimate answer (a discount of zero, with the reason recorded); a missing price is a
hard `BusinessRuleException`, because there is no defensible default for a price — zero understates a
revenue position and a remembered last price applies terms from a date nobody asked about. **Money is
never converted.** There is no FX component anywhere in the module; a cross-currency amount is refused
at the boundary, because an invented rate would put an unreproducible number into a monetary result
that still looks like money. **"Not applicable" is never "passed."** A rule whose inputs the
transaction does not carry is recorded as a third outcome beside satisfied and violated, because a
system that reports an assurance gap as an attestation is worse than one that reports neither.

A structural caveat that shapes everything below: of the 55 files in the slice, **50 carry real code
and 5 are reserved stubs** — both controllers and all three repositories, left byte-identical under
rule §11 because persistence and HTTP are a later pass. The domain, the closed sets, the value types
and the whole resolution engine are built; the boundaries that would expose them over HTTP or against
a database are not. The journeys below therefore describe the contract the built code encodes, and the
stub files are documented rather than annotated.

The engine is deliberately pure: no Spring stereotypes, no repository, no outbound connection. Every
service takes its term collections as arguments and its collaborators by constructor injection, which
is why all of it is unit-testable with no infrastructure at all — and why the term set that produced a
stored figure can be handed back to be re-resolved and compared by checksum.

#### File inventory

##### `controller/` — HTTP boundary (2 files, both reserved stubs)

| File | Goal |
| --- | --- |
| `controller/ContractController.java` | Reserved stub, HTTP pass pending. Intended entry point for asking what a contract says on a given date. Its governing constraints are already fixed by the engine: the as-of date must be caller-supplied rather than defaulted, and the tenant must come from the security principal rather than a request parameter. |
| `controller/ContractTermController.java` | Reserved stub, HTTP pass pending. Intended entry point for narrative clauses, and the place that must keep the two clause journeys apart — reading a stored clause as of a date versus returning a document-extraction *proposal* that has not been reviewed. |

##### `dto/` — wire shapes and resolution results (14 files, all built)

| File | Goal |
| --- | --- |
| `dto/CommercialEvaluationContext.java` | The transaction facts a rule set is evaluated against, for one date. Carries no FX rate and performs no conversion; every input except the date and currency is optional, because that optionality is exactly what produces the not-applicable outcome. |
| `dto/CommercialRuleEvaluation.java` | The whole rule set evaluated against one transaction, held in a fixed order so two runs produce an identical list. `isSatisfied()` is defined as "no applicable rule was breached" rather than "no rule was violated", which keeps an unevaluable check visible in `results()`. |
| `dto/CommercialRuleResponse.java` | Wire form of a commercial rule, serving the authored `expression` verbatim so a reviewer can read the rule as drafted. Derives `requiredParameter` and `parameterKind` from the rule type so a client need not hard-code the mapping. |
| `dto/ContractResponse.java` | Wire form of a contract header. Carries no amounts, because `contracts` holds none; exposes the window as two raw dates plus `openEnded` so a client need not infer the third from a null. |
| `dto/ContractTermResponse.java` | Wire form of a narrative clause, with `termVersion` on the wire because the point of versioning a clause is that a reader can tell which generation a stored calculation used. |
| `dto/DiscountCapReason.java` | Why a discount was reduced below what its own terms compute. Keeps a contractual ceiling (`MAX_DISCOUNT_AMOUNT`) distinct from an arithmetic property (`GROSS_AMOUNT_LIMIT`), because a reviewer investigating an over-generous discount has to tell them apart. |
| `dto/DiscountEvaluation.java` | The outcome of applying the discount in force to an amount. Makes the absence of a discount a *result* rather than an omission, and states the one-term-never-compounded policy on the type itself. |
| `dto/DiscountTermResponse.java` | Wire form of a discount line, exposing `discountValue` as the raw stored decimal rather than pre-multiplying it, so the correspondence with the stored row that an audit re-read depends on survives. |
| `dto/EffectiveTerms.java` | The reproducibility record: the whole in-force set, not only the winners, plus a checksum over all of it. Holds the losers because they are the evidence of how the winners were chosen. |
| `dto/EffectiveTermsQuery.java` | The question as one value — this contract, this product, this customer, this date, with the candidate collections already narrowed to the contract. Bundled so the question, and therefore the input checksum, has one unambiguous shape. |
| `dto/package-info.java` | Package rule: every monetary value is a `Money` with its currency, and every result carries the term version and effective window it came from. |
| `dto/PricingTermResponse.java` | Wire form of a price line, shipping the minimum and maximum bounds alongside the price so a client can render whether a price was clamped without a second round trip. |
| `dto/ResolvedPrice.java` | The price to charge next to the price the contract declares, plus a `clamped` flag. Keeping both is what lets an investigator see that the agreed price and the billed price diverged, and by how much. |
| `dto/RuleEvaluationResult.java` | One rule's outcome, with the three-state `applicable`/`satisfied` pair whose illegal combination the constructor refuses. Derives its own input checksum from the fields it sits beside, so the digest cannot drift from what it attests to. |

##### `enums/` — closed code sets mirroring the V5 VARCHAR columns (8 files, all built)

| File | Goal |
| --- | --- |
| `enums/CodedEnum.java` | Shared contract for every set whose variant name is also the stored value: one code per variant, resolution without reflection, loud failure on an unrecognised code, and a column-width guard so an over-long variant is refused at the boundary rather than as a database truncation. |
| `enums/CommercialRuleType.java` | The eight machine-checkable rule kinds, each naming the single `parameters` key it cannot be evaluated without and how that key must be read. Putting both in the type contract is what stops a stored rule from being silently unevaluable. |
| `enums/ContractStatus.java` | The eight-state contract lifecycle plus `suppliesTerms()`. Includes `EXPIRED` deliberately: an expired contract's terms genuinely did apply, and excluding it would make past figures unreproducible the moment a contract lapsed. |
| `enums/ContractTermType.java` | The eight kinds of narrative clause. These carry obligations rather than money, so no variant is ever evaluated arithmetically; `OTHER` exists to keep pre-taxonomy rows readable. |
| `enums/DiscountType.java` | Percentage versus fixed amount, and the reason the type matters more than it looks: both live in one `discount_value NUMERIC(20,6)` column whose unit is decided by this type alone, so reading it without the type is wrong by a factor of one hundred. |
| `enums/package-info.java` | Package rule: every type is a sealed interface of records, so adding a variant forces every `switch` over it to be revisited at compile time. |
| `enums/PricingType.java` | Four price bases, and the two predicates resolution actually needs: `publishesItsOwnPrice()` and `acceptsCandidatePrice()`. They exist because `unit_price` is nullable and only the type can say whether that null means "bounds only" or "incomplete". |
| `enums/ReviewStatus.java` | The approval lifecycle of a proposed term. Only an explicit approval is promotable — treating "nobody objected" as approval is how an unreviewed draft price reaches an invoice. Note it has no V5 column of its own; the decision lives in the audit trail. |

##### `extraction/` — reading clauses out of documents (4 files, all built)

| File | Goal |
| --- | --- |
| `extraction/ContractTermExtractor.java` | The port for pulling clauses out of contract text, declared in the consumer so a deterministic reader and a later document-interpreting adapter are interchangeable. Its output is proposals, never stored terms, because nothing extracted is trusted. |
| `extraction/ContractTermExtractionService.java` | The deterministic heuristic reader: narrow regexes over heading text and three date formats. Stateless and side-effect free, and deliberately capable of returning nothing — an empty list and a guess must not look alike to the reviewer. |
| `extraction/ExtractedContractTerm.java` | A clause candidate before acceptance, allowed to carry a missing description or a missing window because "we could not read this" and "this says nothing" have to be distinguishable. Carries a confidence so an author can triage rather than re-read everything. |
| `extraction/package-info.java` | Package rule: extraction is a pure transformation from text to a candidate type, stores nothing, and produces a value that must still satisfy every term invariant before it can be used. |

##### `model/` — value types mirroring the V5 tables (13 files, all built)

| File | Goal |
| --- | --- |
| `model/CanonicalForm.java` | Canonical text rendering for the input checksum. Now a near-exact duplicate of `CanonicalText` with no remaining callers; retained because this slice's mandate is comments only, and its removal is a code-changing decision for the module owner. |
| `model/CanonicalText.java` | The canonical, package-private renderer every `canonical()` implementation calls. Escapes every separator so two different term rows cannot produce the same string, and keeps money at full stored scale because the scale is part of the evidence. |
| `model/CommercialRule.java` | Immutable mirror of `commercial_rules`. Null `contract_id` means organisation-wide rather than inapplicable, and `compiledExpression()` parses per call rather than caching, because a mutable field would break the value semantics the checksum depends on. |
| `model/CommercialRuleParameters.java` | Typed view over the unstructured `parameters` TEXT column, splitting strictness deliberately: shape is validated at parse time, applicability at evaluation time. Keys are lower-cased and sorted so neither an evaluation nor a checksum can depend on author spelling or hash order. |
| `model/Contract.java` | Immutable mirror of `contracts`, enforcing `ck_contracts_range` in the compact constructor so an invalid window cannot exist in memory either. Exposes the single `canSupplyTermsOn` predicate that callers should use before pricing anything. |
| `model/ContractTerm.java` | Immutable mirror of `contract_terms`. Carries no money; what makes it worth versioning is that it is versioned the same way a price is, so a calculation can record *which generation* of a clause it read. |
| `model/DiscountTerm.java` | Immutable mirror of `discount_terms`, enforcing the invariant the schema cannot: a percentage needs no currency, a fixed amount and any cap both do, and `max_discount_amount` must be in the term's own currency. |
| `model/EffectiveWindow.java` | The shared effective-dating type, separate from `shared.domain.DateRange` because that type rejects a null end and an open-ended term is legal and common. Both ends are inclusive, and the Javadoc argues why the duplicated-day risk beats the dropped-day risk. |
| `model/package-info.java` | Package rule: plain records and immutable types, deliberately not JPA entities, every field mapping one-to-one onto a V5 column so adding JPA later is mechanical rather than a redesign. |
| `model/PricingTerm.java` | Immutable mirror of `pricing_terms`, enforcing three invariants in the constructor that would each silently change a monetary result if they slipped through: single currency, `minimum <= maximum`, and non-negative price and bounds. |
| `model/RuleExpression.java` | The commercial-rule expression grammar and its evaluator. Tokenised and parsed once into a sealed node tree with an exhaustive `switch`, bounded in source length, nesting depth and node count. No `eval`, no reflection, no dispatch on stored text. |
| `model/ScopedTerm.java` | The optional product/customer scope shared by price and discount rows, modelled once because "a null scope column means any" is a business rule rather than a schema detail. |
| `model/VersionedTerm.java` | The shape shared by every row that takes part in as-of resolution, and the statement of the module's central distinction: `term_version` is business history and is the only thing resolution orders on, while `version` is a write counter that must not influence which terms a historical calculation used. |

##### `repository/` — persistence boundary (3 files, all reserved stubs)

| File | Goal |
| --- | --- |
| `repository/CommercialRuleRepository.java` | Reserved stub, persistence pass pending. Intended access to `commercial_rules`; the query has to return organisation-wide and contract-scoped rules together and must not pre-filter by version or type, because the tie-break belongs in reviewable code rather than in SQL. |
| `repository/ContractRepository.java` | Reserved stub, persistence pass pending. Intended access to `contracts`, with tenant scoping in every signature and row mapping routed through the value type's constructor so the window constraint cannot be bypassed in memory. |
| `repository/ContractTermRepository.java` | Reserved stub, persistence pass pending. Intended access to `contract_terms`, narrowed only by tenant and contract. Its date predicate must use the same inclusive endpoint as the engine, or it will silently drop the last day of every clause. |

##### `service/` — the resolution engine (11 files, all built)

| File | Goal |
| --- | --- |
| `service/CommercialRuleService.java` | Evaluates a rule set against a transaction, exhaustively over `CommercialRuleType` so a new variant fails compilation rather than reaching a stored row at runtime. A missing required parameter raises rather than skipping the rule: an incomplete rule is a defect to fix, not a check to omit. |
| `service/ContractService.java` | The façade over the engine for one contract, product, customer and date. Also the place that reconciles the contract's currency with the price line's, skipping an ineligible candidate rather than failing the whole lookup. |
| `service/ContractTermService.java` | Clause resolution by type and date. Narrow in scope by design, and largely superseded by the equivalent method on `ContractService`. |
| `service/DiscountService.java` | Applies the discount in force to an amount. Selects exactly one term and never compounds, because V5 has no stacking or compounding flag; applies the contractual ceiling and then the gross-amount ceiling, reporting which one actually bound. |
| `service/EffectiveTermResolver.java` | Answers "which version of this term was in force on this date" and owns the total precedence order. Stateless and clock-free, and exposed statically so the checksum and the selector apply exactly the same tie-break. |
| `service/EffectiveTermsService.java` | The module's end-to-end answer: the full in-force set, the winners, and a checksum over every *candidate* rather than every result — so a superseding row that was considered and then ignored still changes the digest. |
| `service/InputChecksum.java` | Builds the digest that makes a stored figure verifiable. Sorts rows into canonical order so hash or query order cannot reach the hash, and filters nulls so "not supplied" and "supplied as null" cannot produce two digests for one commercial position. |
| `service/MonetaryScale.java` | The single declaration of scale and rounding policy: `NUMERIC(20,6)` for price columns, `NUMERIC(20,4)` for amount columns, `HALF_UP` at every hand-out, one rounding per value. The class documents why `HALF_EVEN`, `FLOOR`/`CEILING` and `UNNECESSARY` were each rejected. |
| `service/package-info.java` | Package rule: no Spring stereotypes, no repository, no clock; every service is constructor-injected and every as-of date arrives as a parameter. |
| `service/PriceResolutionService.java` | Turns "what is this worth on this date" into one number with the reasoning attached. Distinguishes three clamping cases, refuses a currency mismatch, and re-checks the effective window inside every entry point so a hand-filtered term list cannot reach a different answer. |
| `service/TermSelector.java` | Chooses between rows competing for the same contract, date, product and customer. Two filters run before the version tie-break: eligibility first, then specificity — most specific wins *before* version, or a negotiated customer rate loses to a published default and an agreed bespoke price reverts to list. |

#### Flow of journey

The stages below are the contract the code encodes. Stages 1 and 7 are the reserved stubs, so today the
module is reachable only from tests and from whatever calls `ContractService` directly.

1. **Contract creation** — a contract row is created with its tenant, currency, status and effective
   window. The compact constructor rejects an inverted window and a negative version before anything
   else can see the object, mirroring `ck_contracts_range` in memory. Status is stored separately from
   the window because the two answer different questions: a contract can be date-valid and still not be
   allowed to supply terms.

2. **Document extraction** — `ContractTermExtractor.extract(contractId, documentText)` walks the text
   line by line. A line that wholly matches a heading pattern is resolved against the closed
   `ContractTermType` vocabulary; an unrecognised heading yields *no* candidate. Dates are read in ISO,
   then long-form, then dotted day-first order, and a reversed pair is demoted to an open end rather
   than becoming a window that can never apply. Each result is an `ExtractedContractTerm` carrying a
   confidence and a `needsReview()` flag — a proposal, never a term.

3. **The question** — a caller builds an `EffectiveTermsQuery`: the contract, the candidate collections
   already narrowed to that contract, the product and customer being priced (both null for a
   contract-wide question), and the as-of date. Bundling the question as one value is what gives the
   input checksum a single unambiguous shape.

4. **Term resolution** — `EffectiveTermsService.resolve` first calls
   `requireContractInForce`, which checks the window and the status independently and names which one
   failed. It then collects in-force clauses and rules, and selects a single price line and at most one
   discount line through `TermSelector`. A missing price is fatal; a missing discount is not. The
   result is an `EffectiveTerms` holding the whole in-force set in precedence order plus a SHA-256 over
   every candidate.

5. **Price resolution** — `PriceResolutionService` takes the selected line and produces a
   `ResolvedPrice`. A published `unit_price` outside its own band is clamped to the bound with
   `clamped` set, so the inconsistency surfaces instead of being absorbed; a band-only line clamps an
   offered price and leaves an in-band price alone; a line with neither is rejected rather than resolved
   to zero. The declared price, the bounds, the term id, the term version and the window all travel on
   the result.

6. **Discount** — `DiscountService` computes the grant from the single selected term (a percentage of
   the amount the caller supplied, or the term's fixed amount), then applies the contractual ceiling
   and the gross-amount ceiling in that order, reporting which bound fired and by how much. The net is
   derived from the capped grant, so a discount larger than the invoice can never produce a negative
   net.

7. **Rule evaluation** — `CommercialRuleService` walks the in-force rules in order and dispatches
   exhaustively on the rule type. A rule whose inputs the transaction lacks is recorded as
   not-applicable; a rule whose required parameter is missing raises rather than passing;
   `EXPRESSION_THRESHOLD` parses the rule's own `expression` through the bounded grammar and compares
   on unrounded decimals. Each result carries its own checksum over the inputs that produced it.

8. **Response** — the results are projected by the `*Response` records, which are built from the value
   types and never the reverse. Closed sets travel as V5 code strings, monetary values travel at
   `NUMERIC` scale, and every window is emitted as two raw dates plus `openEnded`. The stored artifact
   is the reproducibility record: term ids and versions, effective windows, and the checksums that let
   a later run re-resolve the same terms and prove it reached the same answer.

#### Flow of implementation

**Term precedence is a total order derived only from stored columns.** `EffectiveTermResolver.PRECEDENCE`
orders by `term_version` descending, then `effective_from` descending, then `effective_to` ascending
with nulls last, then id ascending. Each step exists for a stated reason: a later amendment supersedes
the term it amends; at equal version the more recently started window is the correcting one, which is
what a back-dated restatement looks like; at equal version and start the narrower window is the more
specific statement, and ordering a null end as if it were later keeps the comparator total instead of
bouncing on null; the id is a corruption tie-break that still yields a stable answer, because a
duplicated clause should reach a human as a discrepancy rather than as an outage. The `version`
optimistic-lock column is deliberately **absent**: it is a row write counter, and ordering on it would
let an unrelated concurrent `UPDATE` change which terms a historical calculation used. `precedence()`
is public and static so the selector and the checksum apply the identical rule.

**Specificity is resolved before version, not after.** `pricing_terms` and `discount_terms` rows may be
scoped to a product, a customer, both, or neither, and all four shapes can be valid on the same date.
`TermSelector` therefore filters for *eligibility* first — a row naming a different product or customer
is about someone else's deal and is dropped outright, while a null scope column is a contract-wide
default that stays eligible — and then sorts survivors by specificity, ranked 3 (product and customer)
through 0 (contract-wide default), with the resolver's tie-break inside each rank. The ordering of
these two criteria is the load-bearing decision: comparing version first would let a published default
at `term_version` 3 beat a negotiated customer rate at version 1, which is exactly the mistake that
makes an agreed bespoke price silently revert to list.

**Effective dating is one type with inclusive ends and a real open end.** `EffectiveWindow` exists
because `shared.domain.DateRange` rejects a null end date, and an open-ended term is both legal and
common in V5; a sentinel date would have to be invented, and every invented sentinel eventually gets
compared against a real date in a report. Both endpoints are inclusive. The Javadoc argues the choice
directly: the half-open convention drops the last day of every price, and a silently dropped day is far
more damaging than the duplicated day the inclusive convention can produce, because a duplicate is
visible in the candidate set and decided by the documented tie-break while a dropped day is invisible
and unfixable after the fact. `Contract.canSupplyTermsOn` and the resolver's filter delegate to this
one method so the boundary rule is applied in one place; `ContractTermResponse.covers` restates the same
comparison on the wire fields, since a response is detached from its model.

**Versioning is business history and is kept separate from concurrency.** `VersionedTerm` states the
distinction and `ContractTerm.MIN_TERM_VERSION = 1` enforces it: V5 declares `term_version INT NOT
NULL DEFAULT 1`, so 0 cannot occur in a stored row, and rejecting it here keeps 0 free as the sentinel
that results like `DiscountEvaluation` use to mean "no term was applied". Every term record derives
`effectiveFrom()` from its window rather than declaring it, so a term cannot report a start date that
disagrees with the window the resolver filters on. Immutability is load-bearing for the checksum: a
term's `canonical()` must be reproducible from stored columns alone, which is why
`CommercialRule.compiledExpression()` parses per call instead of caching a tree inside the record.

**The checksum covers candidates, not winners.** `EffectiveTermsService` hashes the query key first,
then every supplied row — including rows for other products and rules the contract does not own — with
each collection sorted into the resolver's precedence order before hashing. Two runs over the same
rows in a different order therefore hash alike, while any change to a row's content, version or
window changes the digest. Hashing only the resolved answer would let a superseding row that happened
to be ignored change nothing about the recorded evidence, which is the exact failure an input checksum
exists to catch. `CanonicalText` escapes every separator so two different term rows cannot render to
the same string, and renders money at full stored scale without `stripTrailingZeros()` because the
scale is part of the evidence. `RuleEvaluationResult` derives its own checksum through its factory and
deliberately excludes the human-readable explanation, so rewording a message cannot change the digest
of a result that computed identically.

**Rule safety is a grammar, not a sandbox.** `commercial_rules.expression` is written by a commercial
reader and is prose for most rules. The one variant that reads it parses the text into a sealed tree of
records and evaluates it with an exhaustive `switch`. There is no `eval`, no reflection, no
string-to-class lookup and no method resolution on a name read from the column; the function dispatch
is a closed `switch` whose default arm refuses. Source length, nesting depth and node count are all
bounded, so neither a hostile nor a mistaken expression becomes unbounded work. Division is the one
place that must supply an explicit scale and rounding mode, which is why it has its own node type.
Evaluation failures raise `BusinessRuleException` and never degrade to zero: a threshold that cannot be
computed must not fail every transaction that reaches it while looking like a real rule.

**Three rule outcomes, and a missing parameter is a failure.** `RuleEvaluationResult` refuses the
`!applicable && !satisfied` combination in its constructor, and `notApplicable(...)` records
not-applicable as satisfied-true so a caller cannot read it as a breach. Separately, a rule whose
required parameter is absent or unusable raises rather than being skipped, because V5 validates no
`parameters` content against `rule_type` — a well-formed rule missing the value its type needs is a
configuration defect to fix, and skipping it would let through the exact transaction it was written to
catch. Monetary thresholds are read from the key the *type* names, never from the rule's own text, and
pinned to the transaction's currency rather than any currency of their own.

**Money has one scale policy and one rounding point.** `MonetaryScale` declares the two V5 scales —
`PRICE_SCALE = 6` for the `NUMERIC(20,6)` price columns, `AMOUNT_SCALE = 4` for amount columns — and
`HALF_UP` at every hand-out, with the rejected alternatives documented rather than merely omitted:
`HALF_EVEN` systematically favours the customer on every half unit, `FLOOR`/`CEILING` bias the discount
path towards over-charging in aggregate, and `UNNECESSARY` throws. Intermediates are held at
`DECIMAL128` working precision and rounded exactly once at hand-out, so a result that round-trips
through this module and back into the database is identical.

**Discount capping is reported, not merely applied.** `DiscountService` applies the contractual
`max_discount_amount` first and the gross amount second, with the second able to override the first,
and uses `>=` so a grant exactly equal to a bound counts as reaching it — otherwise the result would
report `MAX_DISCOUNT_AMOUNT` without ever capping anything. Whichever bound fired is reported as a
`DiscountCapReason` with the binding amount as `capAmount`, and `capAmount` is null when nothing was
capped so "no cap" is never reported as "capped at zero". Composition is deliberately one term, never
compounded: V5 has no stacking or compounding flag and no ordering between discount terms, so applying
all of them would be an invention of this code rather than something the data says.

#### Key comments added

Comments were added across the built portion of the slice, none removed or reworded, and the five stub
files under `controller/` and `repository/` were left byte-identical. The additions concentrate on the
places where a reader's first instinct would be wrong:

- **Effective-window boundaries** — `EffectiveWindow.contains` and `intersects` now say why a date
  before the start is a miss, why a null end covers everything from the start forward, and why the
  inclusive convention is chosen over the half-open one.
- **Precedence and specificity** — each clause of the `PRECEDENCE` comparator is annotated with the
  reason it exists and with why `version` is absent and why the nulls-last ordering is required for
  totality; `TermSelector` explains why specificity is sorted before version, and why a null scope
  column stays eligible.
- **Versioned selection** — `ContractTerm.MIN_TERM_VERSION` and `CommercialRule.compiledExpression()`
  explain why 0 is reserved as a "no term applied" sentinel and why the parse is not cached on the
  record.
- **Canonical normalisation and checksums** — `CanonicalText` documents the shared null sentinel and
  why separator escaping is what makes the rendering injective; `EffectiveTermsService` documents why
  the query key is hashed first and why candidates rather than winners are covered;
  `RuleEvaluationResult` documents why the explanation is excluded from its digest and why the illegal
  `!applicable && !satisfied` combination is refused; `InputChecksum` documents why group labels are
  part of the digest.
- **Rule-expression parsing and evaluation safety** — `RuleExpression` annotates the dispatch switch
  whose default arm refuses, the ASCII-only name test, the single-dot number scan, the reason
  multiplication reuses `Sum` while division gets its own node, and both resource bounds (depth and
  node count).
- **Discount cap clamping** — `DiscountService.applyCaps` documents the two ceilings, their order, why
  `>=` rather than `>`, and why the second reason overwrites the first rather than combining with it.
- **Monetary scale** — `MonetaryScale.WORKING_PRECISION` records why `DECIMAL128` rather than unlimited
  precision, and the `price`/`amount` entry points reference the column each serves.
- **Extraction confidence** — `ContractTermExtractionService.clause` documents the confidence ladder and
  records that `CONFIDENT` is unreachable from this heuristic reader, so every candidate it returns
  carries `needsReview() == true`; the reader also documents the day-first-only dotted date choice.
- **Repository and controller semantics** — the five stub files are byte-identical under rule §11, so
  their intended query and endpoint rules are captured in the inventory above rather than in the files
  themselves: for the repositories, match the engine's inclusive endpoint, never pre-filter by
  `term_version` or `term_type`, return organisation-wide and contract-scoped rules together, and keep
  the winner selection in reviewable code rather than in SQL; for the controllers, take the as-of date
  from the request rather than defaulting it and the tenant from the security principal rather than a
  parameter.
- **Currency refusal** — `PriceResolutionService.requireCurrency`,
  `CommercialEvaluationContext.isInCurrency` and `DiscountService` all state that no conversion exists
  and that a mismatch is refused rather than resolved.

---

## 7. Evidence & data lineage

#### Module goal

`com.fintech.cfo.evidence` is the module that makes every number in this system
*arguable*. It owns three things and nothing else: the record of a **proof**
(`evidences`), the frozen **renderings** of a subject at the moment a claim was made
(`evidence_snapshots`), and the **graph** that connects a computed figure back to the
uploaded row it was read from (`lineage_nodes`, `lineage_edges`,
`evidence_references`). Its scope is deliberately narrow. It does not compute figures,
it does not own the source documents, and it does not own the subjects that figures are
about. It owns the *provenance* of both.

The module exists because of rule §5 of the implementation rules: a reported amount
with no way back to its source row is not shippable. Everything here is in service of
one chain that V7's own header comment names —

```
Calculation -> affected transaction -> canonical record -> source row -> source file
```

— and of the reverse journey: given any figure this system ever reported, name the
file and the row inside it that a reviewer should open.

Three commitments run through the built code.

**It never holds the bytes.** An `Evidence` row carries a `SourceLocation` (file id and
row number) and an `EvidenceArtifact` (a storage key and a content hash). It never
carries a document. An inline copy could not be independently re-read against the
source system, and could not be independently deleted when retention expired — it would
be simultaneously unverifiable and undeletable, which is worse than either. The only
inline content in the module is `evidence_snapshots.content`, and that is a *derived*
rendering of a subject, not the source document it was derived from.

**It refuses a proof it cannot check.** This is the module's defining property and it is
structural, not conventional. The type of an evidence row *decides* what the row must
point at: `EvidenceType.requiresSourceFile()` and `requiresSourceRow()` are consulted
inside `Evidence`'s compact constructor, so a `SOURCE_ROW` row naming no file and no
row cannot exist in memory. An unverifiable claim stored as evidence is worse than a
missing claim, because a reviewer will trust it.

**It separates "is derived" from "is verifiable".** `EvidenceType` keeps two axes apart
that are easy to conflate. `isDerived()` marks the computed types
(`CALCULATION_RESULT`, `CALCULATION_RUN`, `SNAPSHOT`), which are admissible only
alongside the source-bearing evidence beneath them. `isIndependentlyVerifiable()` marks
the types a third party can re-check, and it is *not* the complement: a
`REVIEW_NOTE` is neither derived nor verifiable (it is an opinion), while an
`INTEGRITY_ATTESTATION` is derived in spirit yet fully machine-checkable, because a
reviewer can re-hash the stored object and compare.

A structural caveat that shapes everything below: of the **26 files** in this slice,
**11 carry real code** (6 in `model/` — five value types plus the package contract — and
5 in `enums/` — three sets, the shared `CodedEnum`, and the package contract) and **15
are unimplemented placeholders** — all three services, all three
repositories, both controllers, all three DTOs, and the three graph/snapshot model types
(`LineageNode`, `LineageEdge`, `EvidenceSnapshot`, `EvidenceReference`). The vocabulary
and the value types are built; the traversal, the persistence and the HTTP boundary are
not. **No module in the repository currently imports `evidence`** — the boundary rule
is respected in both directions — so this is a self-contained slice awaiting its
composition root. The journeys below therefore describe the contract the built code
encodes and the placeholders now document, not a path that executes end to end today.

#### File inventory

##### `enums/` — the vocabulary of provenance (all built)

| File | Goal |
| --- | --- |
| `enums/CodedEnum.java` | The shared contract for the closed sets whose variant name *is* the persisted `VARCHAR` value: one stable `code()` per variant, normalisation that fails loudly on a blank or unknown code, and a column-width guard so an over-long variant is refused at the boundary rather than silently truncated by the database. Deliberately a local copy of the identical contract in `contract.enums` and `financialtruth.enums`, because a business module must not depend on another business module. |
| `enums/EvidenceType.java` | What kind of proof an evidence row carries, over the eleven `VARCHAR(32)` values. Its four exhaustive `switch` predicates — `requiresSourceFile`, `requiresSourceRow`, `isDerived`, `isIndependentlyVerifiable` — are the module's rule engine: they are read by `Evidence`'s constructor, so a new constant that nobody classified is a compile error rather than a silently permissive `false`. |
| `enums/SourceType.java` | The kind of thing a piece of evidence or a lineage node is *about*, persisted as free `VARCHAR(64)` text with no lookup table. A final class rather than an enum precisely so subjects owned by other modules (`OPPORTUNITY`, `INVESTIGATION`) can be referenced without importing them. Fixes the chain hops as interned named constants and accepts any other well-formed code. |
| `enums/LineageRelationType.java` | The meaning of one edge, persisted as `VARCHAR(48)`, read as `from <relation> to`. Each constant declares both the `SourceType` it may point at and a `Direction`, which is what turns the traceability chain from a convention into something the compiler and the walk can both check — and what stops a trace from wandering into a cycle. |
| `enums/package-info.java` | States why two of the three sets are enums and one is a class, and — newly added — what the sets can and cannot verify on their own: a code can be well formed without the row it names existing or belonging to the caller's tenant. |

##### `model/` — the immutable shape of a proof (partly built)

| File | Goal |
| --- | --- |
| `model/ContentHash.java` | A SHA-256 digest as a validated 64-character value type rather than a bare `String`, because the columns that hold it are `CHAR(64) NOT NULL` and `CHAR` silently pads — a 60-character digest would look valid and deduplicate nothing. Owns the algorithm choice, the normalisation and the hash comparison in one place. |
| `model/Evidence.java` | One proof, mirroring a row of `evidences` column for column. The compact constructor is the module's front door: it refuses a row whose `EvidenceType` demands a source it does not carry, a non-positive row number, a blank title, a missing tenant and a missing digest. The three `with*` methods define what "immutable once stored" permits, and each advances `version`. |
| `model/EvidenceArtifact.java` | The pointer to an object stored outside the database: a storage key, the digest of what was written there, and the instant it was written. The digest is of the *bytes*, never of the key, so a reviewer can fetch the object and prove it is the same one the evidence was registered against. `pointsAtSameObject` compares keys alone, so a re-upload of identical bytes is idempotent rather than a conflict. |
| `model/SourceLocation.java` | The two source columns V7 repeats on `evidences` — `source_file_id` and `source_row_number` — and deliberately nothing more. Refuses a non-positive row number because parser numbering is 1-based and an off-by-one would send a reviewer to the wrong line while looking correct. `matches(SourceReference)` is the check that closes the gap between a locator and a canonical record's own provenance. |
| `model/SubjectRef.java` | A typed pointer at any subject, wherever it is owned: the pair V7 stores as `from_type`/`from_id` and as `subject_type`/`subject_id`. Wrapping the type and the id together is what makes it impossible to look up "some evidence by id" when the caller meant "the evidence for this invoice". Carries no tenancy, because a bare reference names nothing until it is read through a scoped query. |
| `model/LineageNode.java` | **Placeholder.** Intended: one vertex of the provenance graph, mapping to a row of `lineage_nodes`, carrying `node_type`, `node_id`, an optional label, and — when its `SourceType.bearsSourceCoordinates()` — a `SourceReference`. |
| `model/LineageEdge.java` | **Placeholder.** Intended: one directed relation between two nodes, mapping to a row of `lineage_edges`, carrying `relation_type` and validating the `to` side against `LineageRelationType.accepts(...)`. |
| `model/EvidenceSnapshot.java` | **Placeholder.** Intended: a frozen, content-hashed rendering of a subject at the instant a claim was made. Immutable after capture, deduplicated on `(organization_id, subject_type, subject_id, content_hash)` exactly as V7's unique index declares. |
| `model/EvidenceReference.java` | **Placeholder.** Intended: the typed from/to edge that binds an `evidences` row to two arbitrary subjects, matching V7's `evidence_references` and its unique index on the edge itself. |
| `model/package-info.java` | States the package rule — every type mirrors a V7 column, no `@Entity`, no framework types — and, newly added, which of the two stated invariants are *enforced today* (`Evidence` against `EvidenceType`) versus merely stated (`LineageNode` carrying a `SourceReference`, which no code can yet check). Also records why `evidence_snapshots.content` is a deliberate exception to the no-bytes rule rather than a contradiction of it. |

##### `service/` — the runtime behaviour (all placeholders)

| File | Goal |
| --- | --- |
| `service/EvidenceService.java` | **Placeholder.** Intended: registration, artifact attachment, hash re-verification and tenant-scoped retrieval. Referenced by name in `EvidenceType.isDerived()` as the component that must enforce `requireTraceableToSource`. |
| `service/EvidenceSnapshotService.java` | **Placeholder.** Intended: capture of a subject rendering, deduplicated by content hash, and retrieval of the snapshot a given claim was asserted against. |
| `service/LineageService.java` | **Placeholder.** Intended: the graph walk. Builds edges, enforces the four-hop `traceabilityChain()`, follows only `isTowardSource()` relations, and owns `requireTraceableToSource` — the check that refuses a derived claim whose chain does not reach a source-level node. |

##### `repository/` — persistence ports (all placeholders, left byte-identical per §11)

| File | Goal |
| --- | --- |
| `repository/EvidenceRepository.java` | **Placeholder.** Intended: a narrow port for reading and writing `evidences` rows, returning domain records rather than entities, with every query filtered on `organization_id`. |
| `repository/LineageRepository.java` | **Placeholder.** Intended: the port for `lineage_nodes` and `lineage_edges`, including the neighbour queries a breadth-first walk needs and the cycle guard that makes repeated visits detectable. |
| `repository/EvidenceReferenceRepository.java` | **Placeholder.** Intended: the port for `evidence_references`, including the reverse lookup V7 indexes on `(to_type, to_id)`. |

##### `controller/` — the HTTP boundary (both placeholders, left byte-identical per §11)

| File | Goal |
| --- | --- |
| `controller/EvidenceController.java` | **Placeholder.** Intended: register evidence, attach an artifact, list and read evidence for the caller's tenant, all returning `ApiResponse<T>` over a mapped DTO with tenant scope taken from the authenticated principal. |
| `controller/LineageController.java` | **Placeholder.** Intended: answer "where did this number come from?" for a claim-level subject, rendering the walked chain and marking any hop that could not be resolved. |

##### `dto/` — the wire shape (all placeholders)

| File | Goal |
| --- | --- |
| `dto/EvidenceResponse.java` | **Placeholder.** Intended: the summary projection of an evidence row — identifiers, type, title, version and whether it is independently verifiable. No description text, no document content. |
| `dto/EvidenceDetailResponse.java` | **Placeholder.** Intended: the full projection, adding the source locator and the artifact pointer plus its digest. |
| `dto/LineageResponse.java` | **Placeholder.** Intended: one hop of a walked chain — subject type and id, relation, and the level reached — so a UI can render a trace without inferring direction from relation names. |

#### Flow of journey

Steps marked **[built]** execute in code today. Steps marked **[planned]** describe the
contract the placeholders now document and the built types already constrain; they have
no implementation yet.

**Registering a piece of evidence**

1. **[planned]** A caller (ingestion, financial, opportunity, or an audit action)
   asks `EvidenceService` to register a proof. The organization id comes from the
   authenticated `SecurityPrincipal`, never from the request body — rule §6.
2. **[built]** `EvidenceType` is chosen first, because the type *is* the requirement.
   `requiresSourceFile()` and `requiresSourceRow()` decide what the rest of the call
   must supply; a `SOURCE_ROW` registration arrives with a file id and a 1-based row
   number, a `CALCULATION_RESULT` arrives with neither.
3. **[built]** The content is hashed. `ContentHash.ofBytes` for an uploaded document's
   exact bytes, `ContentHash.ofText` for a canonical rendering of a derived claim.
   Hashing the bytes as written — never a re-encoding — is what makes the later
   comparison against a fetched object meaningful.
4. **[built]** `Evidence.register(...)` builds the row at `version` 0 with no storage
   key. Its compact constructor refuses a null tenant, a blank or over-long title, a
   missing digest, a non-positive row number, and any type-driven source requirement
   the caller failed to satisfy.
5. **[planned]** `EvidenceRepository` persists the row. Because the type-driven
   invariants are already enforced in memory, the repository has nothing to re-check
   and the write cannot introduce a row the model would have rejected.
6. **[built, second transition]** If the proof is a document, it is written to object
   storage through `platform.storage.ObjectStoragePort`, and the resulting pointer
   comes back as `EvidenceArtifact(storageKey, contentHash, storedAt)`. `storedAt` is
   the caller's instant, not `Instant.now()`.
7. **[built]** `evidence.withArtifact(artifact, now)` attaches it. If the row already
   points at a *different* object this raises — evidence is immutable once stored.
   Re-attaching the *same* key is permitted, because re-uploading identical bytes under
   one key is the same evidence rather than a new claim. The version advances.

**Taking a snapshot**

8. **[planned]** `EvidenceSnapshotService` is asked to capture a subject. The subject is
   named as a `SubjectRef` — a `SourceType` plus a `UUID` — so a lookup can never mix a
   subject id with the wrong subject type. For a subject owned by another module, the
   type is a free code accepted by `SourceType.of`, which is how this module describes
   an investigation without importing the investigation module.
9. **[planned]** The subject is rendered to text and hashed with `ContentHash.ofText`.
   The rendering is the frozen thing; the hash is its identity.
10. **[planned]** The snapshot is stored with `(organization_id, subject_type,
    subject_id, content_hash)` as the deduplication key — exactly V7's
    `ux_evidence_snapshots_subject_hash`. Capturing the same unchanged subject twice
    yields one row, so "the value at the time of the claim" is a single addressable
    fact rather than a growing pile.
11. **[planned]** Once captured, a snapshot is never edited. Correcting a subject means
    capturing a new snapshot with a new hash, which is why `content` and `content_hash`
    travel together: a reader can always tell which rendering the digest describes.

**Walking lineage from a computed number back to source rows**

12. **[planned]** `LineageService.trace(subject)` is asked where a figure came from,
    with the subject given as a `SubjectRef` whose type satisfies
    `SourceType.isClaimLevel()` — an opportunity, a calculation run, or a calculation
    result. The walk starts at a claim; nothing else is a legitimate starting point.
13. **[planned]** For each hop, only edges whose `LineageRelationType.isTowardSource()`
    is followed. That filter is a property of the *relation*, not of the edge row, so a
    graph mixing evidence-directed edges into the source chain cannot send a trace
    sideways, and — the reason the direction split exists — cannot revisit a node it
    has already proven and loop forever.
14. **[built]** The shape of each hop is already checkable. Each relation declares the
    `SourceType` it may point at, so `DERIVED_FROM` must land on an
    `AFFECTED_TRANSACTION`, `RECONCILED_WITH` on a `CANONICAL_RECORD`,
    `SOURCED_FROM` on a `SOURCE_ROW` and `BELONGS_TO_FILE` on a `SOURCE_FILE`. A chain
    with its middle hops transposed is refused rather than displayed.
15. **[planned]** Deduplication is by `(node_type, node_id)`, not by node id: two node
    types may legitimately share a UUID, and collapsing them would merge an opportunity
    with a file.
16. **[planned]** The walk terminates when it reaches a node whose type satisfies
    `SourceType.isSourceLevel()`. Before accepting that node as proof, the service
    confirms it with `SourceLocation.matches(...)` against the `SourceReference` the
    canonical record already carries — file *and* row, both required. A reference that
    omits either cannot confirm a locator, because "somewhere in that system" is not
    evidence that a specific line was read from a specific upload.
17. **[planned]** The result is `requireTraceableToSource`: if a derived claim's chain
    never reaches a source-level node, the claim is refused. This is the enforcement
    `EvidenceType.isDerived()` already points at and the only thing that stops a
    plausible-looking figure from shipping with no path behind it.
18. **[planned]** The reverse question — "what can I show a reviewer for this claim?" —
    is walked the other way, following only `Direction.TOWARD_EVIDENCE` relations to
    `SUPPORTED_BY_EVIDENCE` and `SUPPORTED_BY_SNAPSHOT`, and rendered through
    `LineageResponse`.

Steps 1, 5, 8, 10–13 and 15–18 have no implementation: the three services and the three
repositories are placeholders. Steps 2–4, 6–7 and 14 are built and constrain whatever
the services will do.

#### Flow of implementation

**The hashing strategy.** One algorithm, chosen once, in `shared.util.HashUtils`:
SHA-256. The choice is dictated by the question the digest answers — "is this the same
content I saw before?" — for ingestion de-duplication, snapshot identity, and proving a
stored artifact was unaltered. All three need a fast, collision-resistant digest that is
reproducible on every machine forever, which is the *opposite* of what password hashing
optimises for. It is deliberately unsalted: a checksum nobody holding the file can
recompute is not a checksum. `ContentHash` then makes the digest a value type, because
`CHAR(64)` pads rather than truncates and a padded value read back would look valid
while deduplicating nothing. Three guarantees follow from that wrapping:

- **Shape is validated once, at the boundary.** `of(String)` accepts 64 hex characters
  in any case, trims `CHAR` padding, lower-cases with `Locale.ROOT`, and rejects
  anything else. `ofText` and `ofBytes` skip re-validation because `HashUtils` produces
  that shape by construction.
- **Determinism is pinned against the platform.** UTF-8 is stated explicitly in
  `ofText`, and `Locale.ROOT` is used in both `ContentHash.of` and
  `SourceType.of`. A default-charset or default-locale dependency would make the same
  snapshot hash differently on a differently-configured server — intermittently, in
  production, and only there.
- **The digest covers content, never identity.** Renaming an artifact leaves the hash
  alone; changing one byte anywhere in the stored object changes it. That is what makes
  it usable as an integrity *claim* rather than as a pointer.

**Immutability and integrity invariants.** `Evidence` is immutable in the sense that
matters for a citation: `evidence_type` and the source locator never change after
registration, so a figure cited from a row can never be silently re-pointed at different
data while keeping its old citation. Three narrow transitions are permitted — attach the
artifact once, clear the pointer once when the object is destroyed under a retention
policy, restate the digest when a stored object is re-verified — and each advances
`version`, which is the V7 optimistic-lock counter, so a lost update is detectable
rather than silently overwriting a storage pointer somebody else has already read.
`withoutStorage` is deliberately idempotent: a second call returns `this` rather than a
copy with a bumped version, so making a no-op look like an edit would train callers to
expect a version conflict where none occurred. The digest is *retained* when the pointer
is cleared, which is what allows a later re-upload of the same bytes to be recognised as
the same content and preserves the record of what the object was.

**Row numbering is the quiet killer.** `sourceRowNumber` must be 1-based and positive,
refused in `Evidence`, in `SourceLocation`, and (in the shared helper)
`Preconditions.requirePositive`. A 0-based coordinate stored beside a 1-based parser
produces an off-by-one that points a reviewer at the wrong line and looks entirely
correct while doing it — the single most expensive failure mode this module has,
because it survives review.

**The graph model.** Nodes are `(node_type, node_id)` pairs, edges are
`(from_node_id, to_node_id, relation_type)` with the relation declaring both its
permitted target type and its direction. Three design decisions carry the whole model:

- **The vocabulary is half-open.** `EvidenceType` and `LineageRelationType` are enums
  because every variant is a bare `VARCHAR` with no behaviour beyond a column width and
  two predicates — and a closed set means adding a state forces every handler to be
  revisited at compile time. `SourceType` is deliberately *not* an enum, because V7
  stores it as free text with no lookup table and subjects owned by other modules
  legitimately appear there. A closed enum would require editing this module every time
  another module grows a subject — exactly the coupling the module boundary exists to
  prevent.
- **Direction is declared, never inferred.** Three directions rather than two, because
  calculation-directed edges run *upward* out of a claim and are not part of the source
  walk. Keeping them in one closed set means every edge has an explicitly declared
  direction and none defaults to "toward source" — which is what makes the traversal's
  cycle guarantee a property of the vocabulary rather than of the walk's bookkeeping.
- **Shape is checkable per edge, completeness is not.** `accepts(SourceType)` validates
  one edge; it cannot verify that a *chain* is complete. That residual is exactly what
  step 17's `requireTraceableToSource` has to cover, and why the two are not collapsed
  into one check.

**Referential integrity across module boundaries.** `Evidence.sourceFileId` is a plain
`UUID`, not a reference to an ingestion type, because `evidences.source_file_id` is a
foreign key onto a table another module owns. The cost is stated rather than hidden:
this type cannot prove the file exists or belongs to the same tenant, and that check
belongs to the persistence pass because it needs the query, not the value. The same
reasoning explains `SubjectRef`'s open `SourceType` and its deliberate lack of a
tenant check. Tenancy is always carried by the enclosing row and enforced by the query,
never by the pointer.

**Why the key lines exist.** The two `switch` blocks in `EvidenceType` list every
constant explicitly rather than using a `default`, so an omitted variant is a compile
error — the omission would otherwise read as "this new type needs no source file",
which is precisely the claim the method exists to force someone to make on purpose.
`SourceType.bearsSourceCoordinates()` and `isSourceLevel()` are byte-identical today and
are kept apart deliberately: one states an obligation at construction, the other
classifies a position in the walk, and merging them would make a future type that is
source-level without bearing coordinates impossible to express. `belongsTo` compares by
code rather than by identity because a hand-built instance from a raw database row has
the same code and a different identity, and `==` there would silently report a source row
as not being source-level. `SourceLocation.matches` returns `false` on *any* missing
component rather than a partial match, because a partial match is the dangerous outcome:
it would confirm a row that was actually read from a different upload of the same file.

#### Known inaccuracies found in the built code, recorded not edited

Per the instruction never to reword existing comments, four statements in this slice are
inaccurate and are recorded here instead:

1. `Evidence`'s class Javadoc links `{@link #withStorageKey}`, a method renamed to
   `withArtifact`. Both describe the same single transition; the javadoc now carries a
   short note pointing this out in place of a silent fix.
2. `model/package-info.java` describes `EvidenceArtifact` as carrying "a storage key, a
   content type, a length and a checksum". The record carries a storage key, a content
   hash and an instant — content type and length live in
   `platform.storage.StorageObject`.
3. `Evidence.register`'s Javadoc says the row has "no source locator yet", but the
   factory accepts `sourceFileId` and `sourceRowNumber` and passes them straight
   through. The added inline comment clarifies that the sentence describes the *artifact*
   side only; a `SOURCE_ROW` can and normally does arrive already pointing at its row.
4. `Evidence` imports `com.fintech.cfo.shared.validation.Preconditions` but calls it
   fully-qualified on all three lines. Harmless, but inconsistent with the rest of the
   codebase; left untouched because it is executable text.

Two `{@link}` targets point at types that do not exist at all, and both are deliberate
forward references to contracts the traversal pass must satisfy: a `LineageStep` that
would shape the chain (`LineageRelationType`'s Javadoc) and
`LineageService#requireTraceableToSource` (`EvidenceType.isDerived`'s Javadoc). The
`enums/package-info.java` now says so explicitly.

#### Key comments added

**`ContentHash`** — why SHA-256 and why unsalted (fast and reproducible forever is the
requirement; a checksum nobody can recompute is not a checksum), and why the private
constructor exists so no caller can assert a digest and pass it off as one this module
computed; why normalisation trims before lower-casing and uses `Locale.ROOT` (`CHAR`
pads, and a locale-dependent digest breaks deduplication intermittently in production);
why the alphabet is checked in `of` but skipped in `ofText`/`ofBytes`; why `ofBytes`
hashes the stored bytes as written and never a decoded-then-re-encoded copy (a digest
taken over a re-encoding would report a mismatch on a file nobody altered).

**`Evidence`** — what "immutable" means precisely, and which three transitions are
permitted; why referential integrity for `sourceFileId` is a query's job and not a value
object's; why `register` starts at version 0 with `updatedAt == createdAt`; why
`withArtifact` refuses a *different* key but permits the same one; why `withoutStorage`
is idempotent and why the digest survives it; why `withContentHash` bumps the version
even when the hash is unchanged; why `auditDetail` is assembled from `sourceLocator()`
so the log line cannot disagree with the domain view of the row.

**`EvidenceArtifact`** — why `storedAt` is the caller's instant (a re-verification months
later must distinguish the original upload from a restore), why the width check measures
the trimmed value, and what `pointsAtSameObject` deliberately ignores: a differing digest
under the same key is a tampering signal to report, not a difference of identity.

**`SourceLocation`** — that the row number stays optional while a non-positive one is
refused; why `matches` requires *both* sides complete and returns `false` rather than a
partial match; why the file id is compared in its string form (the two types disagree on
how to spell it, and this type should not need a parsing dependency to answer a yes/no
question).

**`SubjectRef`** — that there is deliberately no tenancy check, because whether a
subject exists and belongs to the caller is a question only a scoped query can answer;
why `wellKnownType` returns the type or `null` rather than a boolean, so the transition
from unknown to known subject stays visible in the type.

**`EvidenceType`** — why all four predicates use exhaustive switches with every constant
listed and no `default`; why `isDerived` and `isIndependentlyVerifiable` are not
complements (`INTEGRITY_ATTESTATION` is derived yet fully machine-checkable); why
`INVOICE_LINE` is source-bearing rather than derived; that `fromCode` re-checks the
column width on the way *out*, and that an unknown code raises rather than falling back
to a plausible variant.

**`LineageRelationType`** — that `isTowardSource` is a property of the relation, not of
the edge row, which is what keeps a trace from wandering sideways or revisiting a node it
has already proven; why the chain has four hops and why all four point toward source;
why `Direction` has three members rather than two; that `accepts` validates one edge and
cannot validate a complete chain.

**`SourceType`** — why `of` normalises before matching and checks width only in the
non-well-known branch; why `belongsTo` compares codes rather than identities (a value
read straight from a database has the same code and a different identity); why
`bearsSourceCoordinates` and `isSourceLevel` are identical today and are kept apart on
purpose.

**`CodedEnum`** — why normalisation uses `Locale.ROOT` (a Turkish locale maps `i` to
`İ`, so the same stored code would resolve to a different variant on a differently
configured server), and why an unrecognised code fails rather than being guessed at.

**`enums/package-info.java` and `model/package-info.java`** — what these pure types can
and cannot verify (a well-formed code is not an existing, tenant-owned row); which
invariant is *enforced today* and which is merely stated because the graph types do not
exist yet; and why `evidence_snapshots.content` is a consistent exception to the
no-bytes rule rather than a contradiction of it.

---

## 8. Economic opportunity lifecycle

### Module goal

`opportunity/**` is the module where a proved financial deviation stops being a number and
becomes owned work. Everything upstream of it produces truth; everything downstream of it
consumes decisions. This module's job is to hold the single record that connects the two:
an `EconomicOpportunity` that says what the deviation is, how much money it is worth, who
believes it yet, what should be done about it, and what actually happened afterwards - with
enough lineage that a reader can walk from a reported amount back to the invoice line that
caused it.

The design goal is *auditability of a claim*, not detection. Detection of variances belongs to
`financialtruth`, which does it deterministically and can prove the same answer twice. This
module takes that answer, refuses to let it become a monetary claim before anyone has stood
behind it, and then keeps the claim honest as it moves: every state change is one step in a
closed graph, every review carries a written reason, every contribution to the headline figure
is attributable to a transaction row, and every backward step - the interesting part, the part
where a validation that cannot be reopened would be worthless - needs a human to say why.

The organizing idea is that money assertions are cheap to state and expensive to defend, so
the types here spend their complexity on refusing the wrong shape rather than on computing
anything. A status is a sealed hierarchy whose transition table is exhaustive at compile time.
A finding can never carry an amount. An evidence reference can never carry content, only a
locator and a digest. A caller can never choose the tenancy of a row it contributes. The
result is that when a number reaches a screen, the question "where did this come from" has an
answer that was constructed rather than one somebody has to reconstruct.

**State of the slice, stated up front because it changes how this document should be read:**
18 of the 40 files in `opportunity/**` are implemented and are what this document describes in
detail. The other 22 - including `EconomicOpportunity` itself, every service, every DTO,
controller and repository - are generator-produced placeholders that contain no code, and are
inventoried here as placeholders. The lifecycle is therefore fully modelled as a *type* and not
yet executed as a *runtime*. Where this document describes runtime behaviour, it is derived
from the implemented enums and records plus `V8__create_opportunities.sql` and
`docs/code-flow/03-opportunity-value-evidence.md` Â§D, and those derivations are marked.

---

### File inventory

Every file in `src/main/java/com/fintech/cfo/opportunity/`.

#### Enums - `enums/` (10 files, all implemented)

| Path | Goal of this file |
| --- | --- |
| `enums/CodedEnum.java` | Shared contract for every closed set here whose variant is also the stored `VARCHAR` value. Supplies `code()`, `normalise()` and a column-width guard, and explains why an unknown stored code is a loud failure rather than a guess. |
| `enums/OpportunityStatus.java` | The lifecycle itself: an 11-variant sealed hierarchy plus the single authoritative transition table (`legalSuccessors`), terminality, and whether the state may carry a monetary claim. This file is the product. |
| `enums/ValidationStatus.java` | The review axis, kept deliberately separate from lifecycle status: has a human verified the figure (`pending`/`in_review`/`confirmed`/`disputed`/`rejected`). Separating the two is what allows "how much of what we detected is actually verified" to be answered. |
| `enums/OpportunityType.java` | The three deviation classes the deterministic engine can prove (pricing, discount, combined), with `isComponentOfPayableDeviation()` so a component figure is never summed into a payable total and double-counted. |
| `enums/ReviewDecision.java` | What a reviewer concluded (approve, reject, challenge, defer) and the three properties the review service must consult: rationale always required, is it adverse, does it close the record. |
| `enums/FindingType.java` | What a finding explains (variance component, data quality, term gap) and whether that kind of explanation caps the confidence of the figure. |
| `enums/FindingSeverity.java` | How much a finding undermines trust in the number, with an explicit ordering, `blocksValidation()` as the human gate, and `mostSevere()` for aggregation. |
| `enums/OpportunityConfidence.java` | Evidence-completeness scale (`high`/`medium`/`low`) plus `leastOf()`, the worst-wins rule a combined record inherits from the rows it was built from. |
| `enums/OpportunityPriority.java` | Triage scale (`critical`/`high`/`medium`/`low`) plus `moreUrgent()`, explicitly independent of confidence because priority answers "what next", not "can I trust this". |
| `enums/package-info.java` | Package contract: why the sets are sealed, why they are coded, why three of them stay enums, and why no ordinal comparison is used anywhere in the package. |

#### Model - `model/` (12 files, 8 implemented, 4 placeholders)

| Path | Goal of this file |
| --- | --- |
| `model/EconomicOpportunity.java` | **Placeholder.** Intended aggregate root for the whole product object; V8 `opportunities` is its schema. Currently an empty class. |
| `model/OpportunityImpact.java` | One affected transaction and this opportunity's claim against it, mapped to `opportunity_impacts`. Its `from()` factory is the only path from a detector's contribution to a stored row, so identity and tenancy are assigned in-module. |
| `model/AffectedTransactionRef.java` | The detector's *input* form of the same thing: amount, entity and source with no identity or tenancy, so a caller cannot write into a tenant it was not asked about. Documents the requirement that contributions partition the net impact exactly. |
| `model/OpportunityFinding.java` | One explanation of why the record should be believed, mapped to `opportunity_findings`. Never carries money; severity is about trust, not magnitude. |
| `model/FindingDraft.java` | The caller's form of a finding before it is attached to a record - identity, tenant and timestamp withheld until the module assigns them. |
| `model/EvidenceReference.java` | A pointer to an artefact plus a checksum, never the artefact. Requires the digest because a locator says where something was, not what was there. |
| `model/CalculationReference.java` | Pointer to the deterministic calculation that produced the figure, carrying the reproducibility triple: rule code, rule version, input checksum. |
| `model/NextAction.java` | The recommended next step as a structured value, so "does this have a due date" and "does this need someone else's authority" are facts rather than things parsed out of prose. |
| `model/OpportunityReview.java` | **Placeholder.** Intended row for `opportunity_reviews`: reviewer, decision, mandatory rationale, decision time. |
| `model/OpportunityLifecycleEvent.java` | **Placeholder.** Intended domain history for `opportunity_lifecycle_events`: from-status, to-status, actor, note, time. |
| `model/OpportunityAssignment.java` | **Placeholder.** Intended row for `opportunity_assignments`: assignee, assigner, due date, version. |
| `model/package-info.java` | Package contract: why the aggregate is a final class rather than a record (it owns invariants), why the `from()` factories exist, and the money rule. |

#### Service - `service/` (5 files, all placeholders)

| Path | Goal of this file |
| --- | --- |
| `service/OpportunityDetectionService.java` | **Placeholder.** Named throughout the enums as the component that raises `FindingType`s and the three `OpportunityType`s, and that checks contributions partition the net impact. No code. |
| `service/OpportunityValidationService.java` | **Placeholder.** Named as the place duplicate detection, confidence ceilings and the validation gate belong. No code. |
| `service/OpportunityReviewService.java` | **Placeholder.** Named as the place a `ReviewDecision` is turned into a state change plus a written rationale. No code. |
| `service/OpportunityLifecycleService.java` | **Placeholder.** Named as the enforcement point for `legalSuccessors()`, for content preconditions ("a quantified record needs a calculation reference"), and for a required reason on backward moves. No code. |
| `service/OpportunityService.java` | **Placeholder.** Intended facade over the record for callers. No code. |

#### Repository - `repository/` (3 files, all placeholders)

| Path | Goal of this file |
| --- | --- |
| `repository/OpportunityRepository.java` | **Placeholder.** Persistence for `opportunities`; per `module-implementation-rules.md` Â§11 left untouched until the persistence pass. |
| `repository/OpportunityReviewRepository.java` | **Placeholder.** Persistence for `opportunity_reviews`, queried newest-first per opportunity. |
| `repository/OpportunityLifecycleRepository.java` | **Placeholder.** Persistence for `opportunity_lifecycle_events`, the append-only domain history. |

#### DTO - `dto/` (7 files, all placeholders)

| Path | Goal of this file |
| --- | --- |
| `dto/OpportunitySummaryResponse.java` | **Placeholder.** List-view projection: reference, title, type, status, priority, impact, owner. |
| `dto/OpportunityResponse.java` | **Placeholder.** Single-record projection including validation and lifecycle position. |
| `dto/OpportunityDetailResponse.java` | **Placeholder.** Full projection: impacts, findings, evidence, reviews, lifecycle events. |
| `dto/ValidateOpportunityRequest.java` | **Placeholder.** Body for the validate action. |
| `dto/ChallengeOpportunityRequest.java` | **Placeholder.** Body for the challenge action; carries the rationale the schema makes mandatory. |
| `dto/RejectOpportunityRequest.java` | **Placeholder.** Body for the reject action. |
| `dto/AssignOpportunityRequest.java` | **Placeholder.** Body for the assign action; must not accept an organization id. |

#### Controller - `controller/` (3 files, all placeholders)

| Path | Goal of this file |
| --- | --- |
| `controller/OpportunityController.java` | **Placeholder.** Tenant-scoped read endpoints over opportunities. |
| `controller/OpportunityReviewController.java` | **Placeholder.** Validate, challenge, reject endpoints. |
| `controller/OpportunityAssignmentController.java` | **Placeholder.** Assign and reassign endpoints. |

---

### Flow of journey

The runtime path the design is built for. Steps marked **[implemented]** execute in code today;
steps marked **[designed]** are fixed by the enums and the V8 schema but have no executing code
yet, because the services are placeholders.

1. **Variance detection (upstream). [implemented elsewhere]** `financialtruth` compares actual
   invoiced amounts against contractually expected amounts and emits variances with the sign
   convention `variance = actual - expected`, plus a `Confidence`, an `ImpactDirection`, the rule
   that produced them and an input checksum. Nothing in this module computes money; it receives
   results that can be re-derived.

2. **Opportunity draft. [designed]** `OpportunityDetectionService` receives the run's results,
   filters them by magnitude threshold and confidence (`docs/code-flow/03` Â§D: skip a variance
   below threshold or at `LOW` confidence), groups what belongs together, and drafts an
   opportunity: a `type` from `OpportunityType`, an `OpportunityImpact` row per affected
   transaction via `AffectedTransactionRef`, and one or more `FindingDraft`s. The state it lands
   in is `DETECTED`, or `QUANTIFIED` directly when the single-pass shortcut is taken
   (`OpportunityStatus.Detected.legalSuccessors()` allows `detected -> quantified`). Deduplication
   is on `(organization, entity, rule, period)`; a duplicate is a `ConflictException`, not a second
   record.

3. **Partition check. [designed]** Before the draft is accepted, the contributions must sum to the
   net impact exactly. A residual is not permitted, and a rounding residual must be attributed to
   one row rather than left over (`AffectedTransactionRef` class Javadoc). This is the invariant
   that makes `impact_amount` reconstructible from `opportunity_impacts`.

4. **Quantification. [designed]** A `CalculationReference` is attached (run id, rule code, rule
   version, input checksum) and the record moves to `QUANTIFIED`, the first state whose
   `carriesMonetaryClaim()` is true. Confidence is `OpportunityConfidence.leastOf(...)` over the
   contributing results - worst wins, so one weakly-evidenced row caps the whole record.

5. **Review. [designed]** A reviewer opens the record (`ValidationStatus.IN_REVIEW`) and returns a
   `ReviewDecision`, which always requires prose. The effects are:

   | Decision | `closesRecord` | `isAdverse` | Intended effect |
   | --- | --- | --- | --- |
   | `APPROVE` | no | no | `validation_status = CONFIRMED`, record may move to `RECOMMENDED` |
   | `CHALLENGE` | no | yes | `validation_status = DISPUTED`, lifecycle steps back `validated -> quantified` for re-quantification |
   | `REJECT` | yes | yes | lifecycle moves to `REJECTED` (terminal) |
   | `DEFER` | no | no | no state change; more evidence requested |

   An unresolved finding of severity `HIGH` or above blocks validation
   (`FindingSeverity.blocksValidation()`), and a data-quality or term-gap finding caps confidence
   (`FindingType.limitsConfidence()`) even when the figure is confirmed.

6. **Recommendation and approval. [designed]** A `NextAction` is attached and the record moves
   `validated -> recommended -> approved`. The recommendation may be withdrawn first
   (`recommended -> validated`). Once approved there is no edge to `REJECTED`: money has been
   committed, so only forward moves remain.

7. **Assignment. [designed]** `OpportunityAssignment` names an assignee, a due date and who
   assigned it. V8 enforces one assignment row per opportunity
   (`ux_opportunity_assignments_opp`), so reassignment replaces rather than accumulates.

8. **Action and outcome. [designed]** The record moves `approved -> acted`, then
   `acted -> measured -> attributed -> realized` as the effect is observed in the ledger and
   attributed back. `REALIZED` is the only state countable as realised value. Outcomes and
   attribution live in `value` (V9), and the attribution sum must equal the outcome total.

9. **Rejection at any point. [designed]** `REJECTED` is reachable from every state before a
   commitment and from none after it. It is terminal and is a first-class state rather than a
   deletion, so "how much did we look at and throw away, and why" stays answerable.

#### Full status transition table

From `OpportunityStatus.legalSuccessors()`, with the reason each edge exists.

| From | To | Why the edge exists |
| --- | --- | --- |
| `DETECTED` | `EVIDENCED` | Forward spine: evidence attached, no agreed figure. |
| `DETECTED` | `QUANTIFIED` | Deliberate shortcut for a single-pass detection that produced evidence and a figure together. |
| `DETECTED` | `REJECTED` | Pre-commitment rejection. |
| `EVIDENCED` | `QUANTIFIED` | Forward spine: a deterministic calculation produced the figure. |
| `EVIDENCED` | `REJECTED` | Pre-commitment rejection. |
| `QUANTIFIED` | `EVIDENCED` | **Backward.** New evidence invalidated the figure. |
| `QUANTIFIED` | `VALIDATED` | Forward spine: a reviewer independently confirmed figure and basis. |
| `QUANTIFIED` | `REJECTED` | Pre-commitment rejection. |
| `VALIDATED` | `RECOMMENDED` | Forward spine: an action with a named owner and next step exists. |
| `VALIDATED` | `QUANTIFIED` | **Backward.** A challenge forces re-quantification. |
| `VALIDATED` | `REJECTED` | Pre-commitment rejection. |
| `RECOMMENDED` | `APPROVED` | Forward spine: the action was authorised; nothing has been done yet. |
| `RECOMMENDED` | `VALIDATED` | **Backward.** The recommendation is withdrawn before any commitment. |
| `RECOMMENDED` | `REJECTED` | Pre-commitment rejection. |
| `APPROVED` | `ACTED` | Forward spine only. **No rejection edge** - money has been committed. |
| `ACTED` | `MEASURED` | Forward spine only. |
| `MEASURED` | `ATTRIBUTED` | Forward spine only. |
| `ATTRIBUTED` | `REALIZED` | Forward spine only. |
| `REJECTED` | - | Terminal. A decision not to pursue is never quietly reopened. |
| `REALIZED` | - | Terminal. The only state countable as realised value. |

Three backward edges in eleven states, all pre-commitment. `canTransitionTo()` is a membership
test on that same set, so the guard a service reads and the graph documented here cannot drift.

#### Validation status, the other axis

| Validation status | Terminal | Meaning | Gate it drives |
| --- | --- | --- | --- |
| `PENDING` | no | V8 default; nobody has looked | Blocks `VALIDATED` and `APPROVED` |
| `IN_REVIEW` | no | A reviewer has the record open | - |
| `CONFIRMED` | yes | Figure and basis independently verified | **Unlocks** `VALIDATED` and `APPROVED` |
| `DISPUTED` | no | Challenged, back in quantification | Keeps the record in the review queue |
| `REJECTED` | yes | Reviewer looked and refused | The record ends as `REJECTED` |

The two axes are orthogonal by design: a record can be `REJECTED` (lifecycle) with `DISPUTED`
(validation), and can be `QUANTIFIED` (lifecycle) with `CONFIRMED` (validation).

---

### Flow of implementation

#### The record: one object, deliberately split into many

The temptation with a product this size is a single fat `EconomicOpportunity` class with lists of
everything. The slice instead models a claim as a *spine plus evidence around it*, and the
splitting is the design:

- The **spine** is identity, type, status, validation status, priority, confidence, one
  currency, one impact amount, and the pointers that make the amount re-derivable
  (`CalculationReference`).
- The **contributions** (`OpportunityImpact`) say which transactions the amount is made of,
  each with its own signed amount and its own source row.
- The **findings** (`OpportunityFinding`) say what is arithmetic and what rests on an assumption.
- The **evidence** (`EvidenceReference`) says which artefacts were captured and what they hashed
  to at capture time.
- The **review and lifecycle rows** (`OpportunityReview`, `OpportunityLifecycleEvent`) say who
  decided what, and when.

Why split: every one of these has a different cardinality, a different lifecycle, and a different
failure mode. Impacts are replaced wholesale on re-quantification; findings accumulate; evidence
is append-only; reviews and lifecycle events are history and must never be updated. Collapsing
them into one row would force all four to share one update policy, and the cheapest policy for
all four is "overwrite", which destroys the audit trail the product exists to produce.

#### Input form versus stored form: `AffectedTransactionRef` and `from()`

The module defines the same concept twice, on purpose. `AffectedTransactionRef` is what a
detector supplies - amount, entity, optional external reference, optional source - with no
identity, no tenancy and no timestamp. `OpportunityImpact.from(...)` is the only factory that
turns one into a stored row, and it takes `organizationId` and `createdAt` as its own parameters.

That split is the tenancy rule made structural. Since module boundaries forbid importing another
business module, the detector cannot be handed a tenant-scoped builder, so the alternative would
be a constructor accepting `organizationId` from whoever is calling - which is exactly the shape
that lets a detection pass write into a tenant it was not asked about. `FindingDraft` and
`OpportunityFinding` are split for the same reason: `FindingDraft` carries no id, no tenant and
no time, and its Javadoc names `OpportunityFinding#from` as where those get assigned. (That
factory does not exist yet; see *Known gaps*.)

#### State machine invariants

Four invariants, each held by a different mechanism:

1. **The graph is closed and exhaustive.** `OpportunityStatus` is a sealed interface of
   records, and `legalSuccessors()`, `isTerminal()` and `carriesMonetaryClaim()` are exhaustive
   `switch` expressions over it. Adding a state is a compile error in all three methods plus
   every other `switch` in the codebase. The alternative - a guard-clause chain in a service, or
   a boolean per transition - is the shape that produces records which reached `ACTED` without
   ever being `APPROVED`, because nobody remembered to add the new edge.
2. **`canTransitionTo` is derived, not duplicated.** It is `legalSuccessors().contains(target)`.
   There is exactly one table.
3. **Terminal states have empty successor sets.** `REJECTED` and `REALIZED` both refuse every
   edge, so a rejected record cannot be quietly reopened and the rejection stays countable; a
   realized record cannot be re-reported as still open.
4. **A monetary claim requires a state that supports one.** `carriesMonetaryClaim()` is false
   only for `DETECTED` and `EVIDENCED`. The invariant is one-directional on purpose: it
   constrains only the two states that may not hold a figure, so a record reaching a
   claim-capable state from either direction is consistent without the check needing to know how
   it got there. `REJECTED` counts as claim-capable because it is reachable both from `detected`
   (nothing quantified) and from `validated` (something was).

Content preconditions are deliberately *not* in the type. "A quantified record needs a calculation
reference" is about the record's contents, not the shape of the lifecycle, so it belongs in the
service and is reported as a business-rule failure rather than as an illegal transition.

#### Human-in-the-loop gates

Four gates, each expressed as a predicate rather than as prose in a method:

| Gate | Predicate | Blocks |
| --- | --- | --- |
| Severity gate | `FindingSeverity.blocksValidation()` - `HIGH`/`CRITICAL` | Marking a record validated while an unresolved high-severity finding stands |
| Confidence ceiling | `FindingType.limitsConfidence()` | Confirming at `HIGH` a record whose explanation rests on a data-quality or term gap |
| Verification gate | `ValidationStatus.isConfirmed()` | `VALIDATED` and `APPROVED` for a figure nobody checked |
| Rationale gate | `ReviewDecision.requiresWrittenRationale()` | Any review row without prose. True for all four variants today, and stated as an exhaustive `switch` so a new decision cannot slip past it |

And one more that is a design absence rather than a gate: there is no `OTHER` opportunity type and
no generic `NOTE` finding type. Catch-all buckets are the easiest value to add and the most
expensive to keep, because they silently absorb every leakage class discovered later until a
portfolio report is showing a growing share of money under a heading that says nothing about it.

#### Scoring, and why no ordinals

Three orderings are needed: confidence (worst wins when aggregating), priority (more urgent wins)
and severity (most severe wins when aggregating). All three are expressed by explicit `switch`
methods - `isLessConfidentThan`, `isAtLeast`, `mostSevere`/`leastOf` - rather than by
`Enum.ordinal()`.

The reason is not style. `OpportunityConfidence` happens to be correct under ordinal comparison
only because `LOW` is declared last; that is exactly the kind of coincidence that survives a
refactor and silently starts reporting an optimistic total. `leastOf` and `mostSevere` also seed
from the optimistic end and move only downward, and both **throw** on an empty input rather than
returning a default - "no findings" and "no severity" must not collapse into an implicit `INFO`,
and an aggregate with no stated confidence must not become an implicit `HIGH`.

Priority is deliberately independent of confidence. Priority answers "what do we look at next";
confidence answers "can we trust this". Collapsing them would mean a large, weakly-evidenced
finding and a small, well-evidenced one competing on the same axis, and the queue would be sorted
by the wrong question.

#### Money and lineage

- Every amount is `shared.domain.Money`. There is no `double`, no `float`, and no bare
  `BigDecimal` field on any opportunity row. Currency identity lives on the record; a
  contribution carries no currency of its own, so `Money` refuses any mixed-currency arithmetic
  at the first operation rather than silently adding like amounts together.
- Contributions carry a **sign**, and the convention is inherited from `financialtruth`
  (`variance = actual - expected`, so a positive amount is a recoverable overpayment). This is
  recorded inline on `OpportunityImpact.isFavourable()`, which currently selects the negative
  direction; whoever implements detection must confirm the polarity, because a sign flip there
  turns a recoverable overpayment into a claim that money is owed.
- `CalculationReference` requires `ruleCode`, `ruleVersion` and `inputChecksum` together. A
  reference missing any one of the three is indistinguishable from an unreproducible figure, so
  all three are mandatory rather than optional.
- `EvidenceReference` stores a locator and a digest and never content. A locator says where
  something was, not what was there; with the digest a reader can prove the artefact behind a
  reported amount is the artefact that was signed off, and a later re-read that produces a
  different digest is a detectable problem rather than a silent substitution.
- `OpportunityFinding` has no amount field at all. A finding that could state a figure would be a
  second, unaudited money column competing with `impact_amount`.

#### Determinism

Timestamps (`createdAt`, `capturedAt`, `evaluatedAt`) are required constructor parameters rather
than `Instant.now()` calls, so a detection re-run over the same inputs produces the same rows.
`CodedEnum.normalise` upper-cases with `Locale.ROOT`, because case mapping is locale-sensitive and
a record read on a differently configured node must resolve to the same variant it was written as.
`fromCode` fails loudly on an unrecognised code and additionally checks the code against the V8
column width, so an over-long code surfaces at the boundary that owns the schema contract instead
of being truncated at write time and read back as an unknown status.

#### Known gaps

Stated plainly rather than described in comments that imply otherwise:

- `EconomicOpportunity`, the aggregate that owns the invariants above, is an empty class. The
  `model/package-info.java` Javadoc describes it as a `final class` with three invariants; those
  invariants are currently enforced nowhere.
- `FindingDraft`'s Javadoc points at `OpportunityFinding#from`, which does not exist.
- `OpportunityStatus`, `OpportunityType`, `ValidationStatus`, `ReviewDecision` and `FindingType`
  all name `OpportunityDetectionService`, `OpportunityValidationService` and
  `OpportunityLifecycleService` as the enforcement points for the rules described above. All
  three are empty classes, so every gate in this document is currently specified but unenforced.
- `OpportunityType.isComponentOfPayableDeviation()` has no callers, so the rule it exists to
  support - only combined-variance records may be summed into a portfolio total - is not yet
  applied anywhere.
- `docs/code-flow/03-opportunity-value-evidence.md` Â§D still describes the lifecycle as
  `OPEN -> IN_REVIEW -> APPROVED -> ACTIONED -> REALIZED (+ REJECTED/EXPIRED)`. The implemented
  `OpportunityStatus` has 11 states with different names and no `EXPIRED`; expiry is covered by
  the terminal `REJECTED`. The code is the better model and Â§D is stale.
- Nothing in the repository imports `com.fintech.cfo.opportunity.*`. There are no consumers yet,
  so the unimplemented half is not currently breaking anything - but `value` (V9 outcomes and
  attribution), `investigation`, `ai` and `reporting` are all designed to consume this record.

---

### Key comments added

Comments added in this pass, all of them additive - no existing comment was deleted or reworded,
and no executable line was touched.

**Transition table and lifecycle guards (`enums/OpportunityStatus.java`)**

- A "Reading the table" section on `legalSuccessors()` explaining the forward spine, the
  single-pass shortcut, why `rejected` exists before commitment and not after, and why the three
  backward edges are the only way back, plus `@return`.
- A comment on each non-obvious case: the `DETECTED` shortcut; each backward edge with the human
  act it answers (new evidence, challenge, withdrawn recommendation); the absence of a
  `REJECTED` edge from `APPROVED`; terminality of `REJECTED`.
- Full Javadoc with `@param`/`@return` on `canTransitionTo()`, which previously had none, stating
  that it is a derived membership test rather than a second table.
- `@return` on `isTerminal()` and `carriesMonetaryClaim()`, and a note that the claim invariant is
  one-directional by design.

**Human gates (`enums/ValidationStatus.java`, `enums/ReviewDecision.java`, `enums/FindingSeverity.java`, `enums/FindingType.java`)**

- `@return` tags on `isTerminal()`, `isConfirmed()`, `isOpenlyDisputed()`, `closesRecord()`,
  `isAdverse()`, `requiresWrittenRationale()`, `blocksValidation()` and `limitsConfidence()`.
- On `isConfirmed()`: why the comparison is identity against the shared singleton rather than
  `equals()` - two instances of a stateless record could only be a bug, and identity makes that
  bug visible instead of masking it.
- On `isAdverse()`: the `switch` is exhaustive and typed, so a decision added later must declare
  its own direction rather than inherit a default.
- On `isTerminal()`: a confirmation ends the validation loop but not the record, and `DISPUTED` is
  deliberately not terminal - a challenge is work in progress.
- On `blocksValidation()`: stated in terms of the ordering rather than a list of two constants, so
  raising the bar later is one edit.

**Scoring (`enums/OpportunityConfidence.java`, `enums/OpportunityPriority.java`, `enums/FindingSeverity.java`)**

- `@param`/`@return` on `isLessConfidentThan`, `isAtLeast`, `moreUrgent`, `leastOf`, `mostSevere`.
- Inside `leastOf`: confidence only ever degrades in an aggregate, so one `LOW` among `HIGH` rows
  dominates; and a null level is a caller with no view, not a vote, so it is skipped rather than
  silently becoming a `HIGH`.
- Inside `mostSevere`: the seed is the floor severity, and `INFO` never displaces it - correct,
  because it is the floor, not an improvement.
- On `moreUrgent`: both-null resolves to `MEDIUM`, matching the V8 column default.

**Persistence contract (`enums/CodedEnum.java`)**

- On `normalise`: `Locale.ROOT` rather than the default locale, because case mapping is
  locale-sensitive and a record must resolve to the same variant on every node.
- On `requireColumnWidth`: the check happens on resolution, not on write, so an over-long code is
  refused at the boundary that owns the schema contract.
- `@return` on `code()`, marking it stable across releases.

**Record invariants (`model/*.java`)**

- `OpportunityImpact`: why every null is refused at construction (a missing tenancy cannot be
  detected later; a missing amount would silently count as zero in an aggregate); why the entity
  type is trimmed and width-checked against V8; why a blank optional reference is worse than none.
  Plus `@param`/`@return`/`@throws` on `from()`.
- `OpportunityImpact.isFavourable()`: the sign convention stated explicitly (`variance = actual -
  expected`, positive is the recoverable overpayment) and a warning that a sign flip here would
  turn a recoverable overpayment into an undercharge claim.
- `OpportunityImpact.requireText`: trimming is normalisation, not leniency - it means a value
  typed with surrounding whitespace compares equal everywhere.
- `AffectedTransactionRef`: why the entity type is width-checked at description time, and why a
  blank external reference reads as a reference lost in transit. A class-level note that no
  currency is carried here, so mixed-currency detections fail at the first `Money` operation
  rather than adding like amounts.
- `OpportunityFinding`: why identity and tenancy are refused at construction (a finding in the
  wrong tenant is a disclosure, not a data-quality problem); why blank detail is refused; full
  `@return` Javadoc on `blocksValidation()` and `limitsConfidence()`.
- `EvidenceReference`: why the digest is mandatory; why the locator is bounded when it is not even
  persisted here; `@return` on `hasSourceLineage()`.
- `CalculationReference`: why the run id is required even though only the run is a V8 foreign key;
  the reproducibility triple and why dropping any member of it makes a later re-run unprovable;
  `@return` on `isReDerivable()`.
- `NextAction`: why only the prose is validated while the due date and approval requirement are
  facts rather than correctness questions; why the action length is bounded to the V8 column.
- `FindingDraft`: why validation here is deliberately shallow - the column rules belong to
  `OpportunityFinding`, and duplicating them would give two places to change; `@param`/`@return` on
  `of()`.

---

### Verification

The slice compiles clean in isolation, with annotation processing disabled so the unrelated
MapStruct failure in `financial/mapper/**` cannot mask the result:

```powershell
mvn -o dependency:build-classpath -Dmdep.outputFile=target/cp.txt
javac -nowarn -proc:none -d target/slicecheck-op -cp (Get-Content -Raw target\cp.txt) `
  (Get-ChildItem -Recurse -Filter *.java src\main\java\com\fintech\cfo\opportunity).FullName `
  (Get-ChildItem -Recurse -Filter *.java src\main\java\com\fintech\cfo\shared).FullName
```

Result: exit 0, no diagnostics. `mvn compile` for the whole repository is currently red for
reasons outside this slice (MapStruct qualifier resolution in `financial/mapper/**`). No test
covering this module exists yet - the three files under `src/test/java/.../opportunity/` are
generated placeholders - so nothing here is verified by a passing test.

---

## 9. Value realization & outcome tracking

#### Module goal

`com.fintech.cfo.value` closes the loop. Every other module produces a claim: the calculation engine
produces a variance, the opportunity module produces an approved Economic Opportunity Record, the AI
layer produces an explanation. This module is where a claim either becomes money or does not. It owns
five V9 tables — `action_plans`, `action_executions`, `outcomes`, `realized_values` and
`value_attributions` — and the closed status and method sets that govern them.

It exists because a detection engine that cannot be scored is a machine for generating plausible
numbers. The product's core claim is that it finds recoverable money; the only way that claim is ever
tested is by walking the whole path to the bank and comparing what was predicted against what arrived.
So this module records commitment (a plan), attempt (an execution), observation (an outcome), the
counting decision (a realized value), and the reasoning that links that money back to the claim which
predicted it (an attribution). Each step is a separate row, deliberately, and the separations are the
design.

**Measurement and counting are different events.** `outcomes` says what happened and how sure we are;
`realized_values` says how much of it may be counted. An invoice was credited, the credit was issued,
the cash landed — any two without the third. Collapsing them would make a correction to a measurement
silently restate an accounting decision, and would erase the gap between what was measured and what was
recovered, which is itself the finding. **A reversal is a row, never an edit.** `REVERSED` is a status
on a new row with its own amount, because the question an audit of realized value exists to answer is
whether money was counted and later withdrawn — and mutating `amount` in place leaves no trace of
either. **Attribution is explicit and states its own method.** There is no default and no inference,
because an automatically derived link manufactures the evidence the layer is supposed to test.

A structural caveat that shapes everything below: of the 24 files in this slice, **1 carries real code
(`enums/CodedEnum.java`) and 23 are unimplemented placeholders** — every controller, DTO, model,
repository and service. The contracts those placeholders now document are derived from
`V9__create_value_tracking.sql`, from `CodedEnum`'s own list of the columns and codes it guards, and
from the module-boundary rules. The journeys below describe the contract the schema and the one real
file encode, not a path that executes today.

#### File inventory

##### `enums/` — closed code sets (one built, four placeholders)

| File | Goal |
| --- | --- |
| `enums/CodedEnum.java` | **Built.** The shared contract for the closed sets whose variant is also the stored column value: `code()`, `normalise` (trims, upper-cases with `Locale.ROOT`, refuses blank) and `requireColumnWidth`. Resolves strictly — an unknown code throws rather than defaulting, because a status this module does not recognise means schema and code diverged, and guessing would report a realized amount whose provenance cannot be reconstructed. A deliberate local copy of the identical `financialtruth.enums` contract: value must not depend on financial-truth, and the alternative is a dependency this pure module has no reason to carry. |
| `enums/ActionStatus.java` | Intended `PLANNED` / `IN_PROGRESS` / `DONE` / `CANCELLED` lifecycle for `action_plans.status`, separate from `OpportunityStatus` so that "approved but never started" stays reportable. `DONE` and `CANCELLED` are terminal; `CANCELLED` is a first-class state rather than a deleted row so abandoned work remains countable. |
| `enums/AttributionMethod.java` | Intended `DIRECT` / `INCREMENTAL` / `PROPORTIONAL` / `ESTIMATED` set for `value_attributions.attribution_method`. Ordered by `isObservation()` and `requiresStatedBasis()` rather than by ordinal, so a modelled estimate cannot drift into a board-level number by accident of declaration order. |
| `enums/OutcomeStatus.java` | Intended `PENDING` / `CONFIRMED` / `DISPUTED` / `REVERSED` for `outcomes.status`. No schema default, matching the migration: an outcome row must arrive with a deliberate stance because `measured_amount` is `NOT NULL` and will always carry a number. `CONFIRMED` and `REVERSED` are terminal. |
| `enums/RealizationStatus.java` | Intended `REALIZED` / `PARTIALLY_REALIZED` / `NOT_REALIZED` / `REVERSED` for `realized_values.realization_status` — the only status that decides whether a figure may be summed into a realized total. No schema default, for the same reason: a defaulted status would attach a countable figure to an unstated reasoning. |

##### `model/` — the five V9 tables (all placeholders)

| File | Goal |
| --- | --- |
| `model/ActionPlan.java` | The hinge of the module: what will be done, by whom, against which system, by when. Documents why `expected_value` is deliberately *not* `opportunities.impact_amount` — the gap between the analysis's prediction and the doer's expectation is either overclaiming or under-claiming, and collapsing them destroys the ability to see it — and why `owner_id`/`due_at` are nullable so an unowned approved opportunity is visible rather than hidden by a placeholder. |
| `model/ActionExecution.java` | One *attempt* at a plan, which is why this table is separate: remediation really does fail, and overwriting the plan per try would make "we tried twice and recovered nothing" — the finding that changes recovery strategy — unstatable. Documents why `status` has no default while `executed_at` does. |
| `model/Outcome.java` | What actually happened, and how sure the record is of it. Documents the `DATE` vs `TIMESTAMPTZ` split: a recovery lands on a business date and must line up with an accounting period, while the row's own creation is an operational fact with a time of day. Records why `action_execution_id` is nullable — a supplier may issue a credit unprompted, and refusing to record that leaves the money invisible. |
| `model/RealizedValue.java` | The payout row: the only place a figure is asserted to be real money that arrived. Documents why `opportunity_id` is denormalized even though `outcome_id` implies it (the realized totals a tenant sees are always grouped by opportunity, and `ix_realized_values_opp` serves them), and why a reversal is a new row rather than a decrement. |
| `model/ValueAttribution.java` | The reasoned link from counted money back to the claim that predicted it — what converts a measurement into evidence about a model. Documents the reasoning behind V9's only database-level invariant (`ck_value_attributions_non_negative`), and the module's own harder one: the sum of attributions against a realized value must equal it exactly. |

##### `repository/` — query semantics (all placeholders)

| File | Goal |
| --- | --- |
| `repository/ActionRepository.java` | Plans and their executions together, as one aggregate. Documents the duplicate-live-plan guard (two open plans for one opportunity is how a recovery is pursued and counted twice), the `ix_action_plans_owner` prefix argument, and why undated plans must sort as "no date" rather than as the epoch. |
| `repository/OutcomeRepository.java` | Scoped strictly to what was *observed*, so it can never be the thing that answers "may this be counted". Documents the `DESC` index argument, half-open date bounds, and why sums happen in Java rather than in SQL: a SQL `SUM` cannot enforce a currency check or raise this module's business-rule exception. |
| `repository/RealizedValueRepository.java` | Realized values *and* the attributions explaining them, together on purpose — an `attributed_amount` alone is a number with no scale, paired with the realized amount it is a checkable statement. Documents the status filter and currency argument baked into the total signature, and why every method filters `organization_id` even where the index omits it. |

##### `service/` — the invariants (all placeholders)

| File | Goal |
| --- | --- |
| `service/ActionService.java` | Owns `action_plans` + `action_executions` and transitions both together. Documents why there is no generic "update status": a free-form update would let a caller move `PLANNED` → `DONE` with no execution row, producing the completed-looking record that makes a realized figure unreconstructible. Never touches money. |
| `service/OutcomeService.java` | The two checks that keep the middle honest: an outcome needs provenance (a measurement method or a recorded execution) and may not exceed the claim it settles. Documents why clamping is specifically refused — a clamped outcome reports a recovery nobody made, silently, in the one field the product exists to get right. |
| `service/RealizedValueService.java` | The counting gate. Only `CONFIRMED` outcomes may back a realized value; the counted amount may not exceed the measured; amounts are magnitudes with direction carried elsewhere. Documents that it never infers attribution — a counted amount that cannot be traced to a claim is real money that teaches nothing, and inferring the link would manufacture the evidence the module requires. |
| `service/ValueAttributionService.java` | Attribution method selection from strongest to weakest, what each requires, and the no-double-counting reconciliation. The one service where a plausible number with unsound reasoning is worse than no number. |

##### `controller/` — HTTP boundary (both placeholders)

| File | Goal |
| --- | --- |
| `controller/ActionController.java` | Intended workflow boundary exposing transitions as named operations (`/start`, `/complete`, `/cancel`) rather than a generic `PATCH`. Documents why no realized-value or attribution endpoints belong here. |
| `controller/OutcomeController.java` | Intended measurement boundary that deliberately stops short of counting — realizing an amount is a separate accounting decision with its own invariants, and merging them would let one request book a portfolio number. |

##### `dto/` — wire contracts (all placeholders)

| File | Goal |
| --- | --- |
| `dto/CreateActionRequest.java` | Plan creation payload. The absences are the design: no `organizationId`, no `status`, no `version`, no timestamps. |
| `dto/ActionResponse.java` | Work-queue projection. Includes `version` (so a queue is concurrently editable) and a `Money`, never a bare number. Embeds no executions or outcomes. |
| `dto/RecordOutcomeRequest.java` | Measurement payload carrying the bounds the service must enforce, and documenting the asymmetry with `CreateActionRequest`: status cannot be implied by creating a plan, but recording an outcome says nothing about whether it is confirmed. |
| `dto/OutcomeResponse.java` | Outcome projection. Status travels as a stable code, never an ordinal, and inlines no `RealizedValue`. |
| `dto/RealizedValueResponse.java` | Countable-recovery projection. `realizationStatus` is first-class so aggregation is a deliberate filter rather than an assumption; documents that the sum of all rows is *not* the realized value. |

#### Flow of journey

Approved opportunity → action plan → execution → realized value → recorded outcome, with the
attribution reasoning that ties the money back to the claim.

1. **An opportunity reaches `APPROVED`.** Upstream, in the opportunity module: a variance was found,
   quantified, validated and authorised. At this point money exists only as a figure on a record. The
   opportunity sits at `APPROVED` precisely because "authorised" and "started" are different facts,
   and merging them makes the number of approvals that never became work unreportable.

2. **A plan is raised against it.** `ActionController` receives a `CreateActionRequest` — which
   contains no `organizationId` and no `status`, because tenancy comes from the authenticated principal
   and a create implies its own starting state. `ActionService` checks the opportunity is `APPROVED`,
   then checks `ix_action_plans_opp` for an existing live plan and refuses a second one. It writes an
   `action_plans` row with `expected_value` set to what the *doer* expects to recover — a different
   number from `opportunities.impact_amount`, and the gap between them is the useful finding. Status
   defaults to `PLANNED`. An `AuditService` event records the commitment.

3. **An attempt is opened and worked.** `POST /api/actions/{id}/start` transitions the plan to
   `IN_PROGRESS` and opens an `action_executions` row in the same transaction, with `executed_at` taken
   from the injected clock rather than the client — a backdated execution timestamp would silently move
   money into a closed accounting period. A rejected credit note leaves the execution row in place with
   a required note explaining why; the plan moves to `CANCELLED`, which is a state rather than a
   deletion so "how much approved work was abandoned" stays countable.

4. **What happened is measured.** `OutcomeController` takes a `RecordOutcomeRequest`.
   `OutcomeService` enforces the two bounds that matter: the request must carry provenance (a
   `measurement_method`, or an `action_execution_id` tying it to a recorded attempt), and
   `measured_amount` must not exceed the opportunity's impact — an outcome above the claim it settles
   means the measurement is wrong or the claim was under-scoped, never that the larger figure is better
   news. It is refused as a `BusinessRuleException` rather than clamped. The row is written with
   `measured_at` as a business `DATE`, distinct from `created_at`, and `status` stated deliberately
   because the column has no default.

5. **A counting decision is taken — separately.** `RealizedValueService` books an amount only from a
   `CONFIRMED` outcome: `PENDING` and `DISPUTED` cannot be counted, since a pending measurement is a
   claim and a contested one is a disagreement. The counted amount may not exceed the measured amount;
   the excess is the under-recovery finding, so it is refused rather than truncated. The row carries
   `realization_status` and a business `realized_at` date. Totals sum `REALIZED` and
   `PARTIALLY_REALIZED` only, in one currency, in Java.

6. **The money is attributed back to the claim.** `ValueAttributionService` records one or more
   `value_attributions` rows, each naming its `attribution_method` — `DIRECT` when a specific ledger row
   is traceable to an entity the opportunity named, `INCREMENTAL` where only the excess over the
   expected value is attributable, `PROPORTIONAL` where shared money is split on a basis recorded in
   `notes`, `ESTIMATED` where nothing measures it. Confidence is set explicitly rather than left to the
   column's `HIGH` default. Then the reconciliation runs: the `attributed_amount`s pointing at one
   realized value must sum to exactly that value's amount, or the write fails.

7. **A reversal, when it comes.** A clawback or refund is a new `outcomes` row or a new
   `realized_values` row with `REVERSED`, never an edit. Totals subtract it as its own row. The amount
   first booked and the amount it later disappeared by both remain readable.

#### Flow of implementation

**The attribution model.** Five questions are kept apart rather than collapsed, and each table answers
exactly one:

| Question | Table | Status set |
| --- | --- | --- |
| What was committed? | `action_plans` | `ActionStatus` |
| What was attempted? | `action_executions` | `ActionStatus` |
| What happened, and how sure? | `outcomes` | `OutcomeStatus` |
| What may be counted? | `realized_values` | `RealizationStatus` |
| Why does it belong to this claim? | `value_attributions` | `AttributionMethod` + confidence |

The consequence is that `OutcomeStatus` and `RealizationStatus` are separate enums answering separate
questions, and they disagree in exactly the cases worth noticing: a `CONFIRMED` outcome may be only
`PARTIALLY_REALIZED`, and a `REALIZED` amount may rest on a `PENDING` outcome because a settlement
cleared first. One enum for both would force one of the two questions to be answered wrongly.

**Realization invariants.**

- Only a `CONFIRMED` outcome may back a realized value.
- The counted amount may never exceed the measured amount, and the measured amount may never exceed the
  claim. Neither bound is clamped — both are refused, because silent truncation destroys the very
  discrepancy the module exists to surface.
- `realized_values.amount` is a non-negative magnitude; direction lives on the opportunity's impact
  sign. V9 enforces non-negativity *only* on `value_attributions.attributed_amount`
  (`ck_value_attributions_non_negative`), so this column's sign discipline is the module's
  responsibility and is asserted in the service rather than assumed.
- Currencies must agree across the entire chain — opportunity, outcome, realized value, attribution.
  `Money` refuses mixed-currency arithmetic outright, which is the desired behaviour: an unconvertible
  currency means a missing FX component, and inventing one here would produce totals that cannot be
  reconstructed from recorded inputs.
- `REVERSED` subtracts as its own row; totals sum `REALIZED` and `PARTIALLY_REALIZED` only.
- **Attributions reconcile exactly.** The `attributed_amount`s against one realized value must sum to
  its amount. This is the invariant with the most weight in the module: the same money counted twice is
  the error this layer was built to make impossible, V9 constrains nothing about it, so the check is
  entirely the module's. It fails as a `BusinessRuleException`, not a warning — a reconciliation that is
  only logged is one nobody acts on. Exact equality, not a tolerance: amounts are already at
  `NUMERIC(20,4)` and rounded once, so a residual is real drift, not a rounding artefact.

**Design decisions and why the key lines exist.**

- **`action_executions` is a separate table from `action_plans`,** and the outcome's reference points
  *upward* from measurement to attempt (`outcomes.action_execution_id`), never back. Remediation fails
  in reality; one plan needs many attempts, and "recovered on the second attempt" has to be
  representable rather than overwritten.
- **Denormalized `opportunity_id` on `realized_values` and `value_attributions`,** even though
  `outcome_id` implies it. The totals a tenant sees are always grouped by opportunity, and that is what
  the index serves; resolving through the outcome would also silently exclude realizations with no
  outcome row — exactly the ones a completeness check needs to find.
- **`DATE` for money, `TIMESTAMPTZ` for bookkeeping.** `measured_at` and `realized_at` are business
  dates because money is recognized on a date and must line up with accounting periods;
  `attributed_at` is a timestamp because it is a judgement made at a moment, and that ordering
  (`ix_value_attributions_opp ... attributed_at DESC`) is what an audit of a claim's reasoning history
  needs. `created_at` timestamps come from the injected `DateTimeUtils`, never a client, so no row can
  be backdated.
- **Nullable where absence is a real, reportable state:** `owner_id`, `due_at`, `action_execution_id`,
  `outcome_id`, `realized_value_id`. An unowned approved opportunity is alarming and should be
  visible; an unprompted supplier credit is real money; a realization booked from a bank confirmation
  never had an outcome. Each would become invisible if forced to a placeholder.
- **Free text where the set is open-ended** (`action_type`, `target_system`, `outcome_type`) and a
  coded enum where this module owns the set (`status`, `realization_status`,
  `attribution_method`, `confidence`).
- **Strict code resolution, never a default.** `CodedEnum.normalise` and `requireColumnWidth` fail
  loudly: an unrecognised status means schema and code have diverged, and guessing would report a
  realized amount whose provenance cannot be reconstructed — the one failure that cannot be caught
  downstream, because the number still looks like money.
- **Tenant scoping in every query,** including `ix_realized_values_opp`, whose index omits
  `organization_id`. An unfiltered read there is a cross-tenant leak, not merely a slow query. A record
  not found *within* the caller's tenant reports as not-found, never as forbidden.
- **Sums in Java, not in SQL.** A database `SUM` cannot enforce the currency equality check, cannot
  raise this module's business-rule exception, and cannot distinguish a deliberate total from an
  accidental one during an audit. `Money` also makes totals scale-insensitive and order-independent,
  so re-running a report over the same rows gives the same figure.

#### Key comments added

- **`enums/CodedEnum` (already written, preserved verbatim):** the shared `code()` / `normalise` /
  `requireColumnWidth` contract, its list of the nine V9 columns it guards, and why resolution fails
  loudly rather than defaulting.
- **Status and method enums:** the group each set holds with every constant's meaning, the schema
  default (or deliberate absence of one) for its column, which states are terminal and why a finished
  record must not be silently reopened, and why ordering is expressed by an explicit predicate rather
  than `ordinal()`.
- **Models:** per-column rationale for the nullable/no-default columns, the `DATE` vs `TIMESTAMPTZ`
  split, why `expected_value` is not `impact_amount`, why `opportunity_id` is denormalized, why
  reversal is a row, and — on `ValueAttribution` — why the database-level non-negativity check sits on
  the attribution table rather than on realized values.
- **Repositories:** the index each query serves and why it is ordered `DESC`, half-open business-date
  bounds, the tenant predicate required even where the index omits it, the status filter and currency
  argument baked into the total signature, and why sums are computed in Java rather than pushed into
  SQL.
- **Services:** attribution method selection and what each method requires; the provenance and
  ceiling bounds on outcomes; why clamping and silent truncation are refused rather than offered; the
  exact-equality reconciliation and why a residual is drift rather than a rounding artefact; why
  `RealizedValueService` never infers an attribution; and the tenancy, concurrency and audit rules each
  service applies.
- **Controllers and DTOs:** why transitions are named operations rather than a generic status `PATCH`,
  why measurement stops at the controller that records it, and which fields are deliberately absent
  from each request (`organizationId`, `status`, `version`, client timestamps).


---

## 10. AI layer — extraction & explanation

#### Module goal

`com.fintech.cfo.ai` is the only place in the system where a language model is allowed to
run, and it is deliberately given the narrowest job the product can survive: **read
clauses, and write prose**. It does two things. It extracts commercial terms out of
contract documents (`extraction/`, behind `DocumentExtractionPort` and `LlmPort`), and it
explains an already-computed financial result in plain English (`service/`). It never
computes, and it never holds money.

That distinction is the module's whole thesis, and it is enforced structurally rather than
by asking the model nicely:

- **No type in this module carries a `Money`.** An AI artefact holds a `SourceReference`
  and an input checksum, never an amount. There is nowhere for a model-computed figure to
  live.
- **The number fence.** An explanation may only cite figures that appeared in the
  deterministic context it was handed. `AiGuardrailService.findForeignNumbers` diffs the
  numbers in the reply against the numbers in the prompt, and anything left over makes the
  artefact `DISPUTED` rather than `CONFIRMED`.
- **The schema fence.** On the extraction side, `StructuredExtractionValidator` rejects any
  field whose name smells like money (`amount`, `discount_percent`, `variance`, …) and any
  `termType` outside a fixed vocabulary — so a model cannot smuggle a computed quantity in
  as a "clause".
- **Trust is never assumed.** Extracted terms leave the validator as `IN_REVIEW`, never
  `CONFIRMED`. `Confirmed` is a verdict the guardrails reached, not a default.

Two structural facts shape the rest of this document. First, the module depends **outward**
only: it imports `com.fintech.cfo.shared.**` and one `platform` port
(`ObjectStoragePort`). It never imports `contract` or `opportunity`, so both vocabularies
it needs (`ContractTermType`, the coded-enum contract) are hand-copied and documented as
duplicates. Second, no other module in the repository currently imports `ai` — a search of
`src` for `com.fintech.cfo.ai` outside this package returns nothing. The seams for
consumers exist and are named; the composition root that injects them is integration-milestone
work, not this pass.

#### Implementation status

Plainly: the logic is built, the boundary is not.

**Built (30 files).** All of `enums/` (the three state sets plus `CodedEnum` and its
package doc), all of `model/` (six records), all of `extraction/` (the extraction engine,
its validator and the run result), all of `service/` (the explanation orchestrator, the
context builder, the guardrail set and the contract-interpretation use case), all of `dto/`
(four request/response records), the three outbound **port interfaces** in `client/` plus
the client `package-info.java`, and six `package-info.java` files. Everything in this list
is real, exercised-by-contract code: the guardrails are pure functions, the validator is a
pure function over a string, and both orchestrators take their ports by constructor
injection, so all of it is testable with no network and no model.

**Stubbed (7 files).** All three HTTP client adapters — `LlmClient`, `EmbeddingClient`,
`DocumentExtractionClient` — are generated placeholders containing only a `TODO`
comment and an empty class, left byte-identical per rules §11 ("do not write any HTTP/AI
client"; the adapters belong to the transport pass). Both controllers —
`ContractInterpretationController`, `ExplanationController` — are the same kind of
placeholder and are likewise untouched. Both PDF helpers — `pdf/AiPdfService` and
`pdf/PdfExtractionService` — are placeholders too; the local OpenPDF implementation of
`DocumentExtractionPort` that `DocumentExtractionPort`'s Javadoc refers to does not exist
yet, so today there is no working `DocumentExtractionPort` implementation in the tree and
the fetch-and-parse branch of `ContractInterpretationService` is unreachable until the
transport pass supplies one. The inline-`contractText` branch works without it.

**Both prompt templates are empty.** `resources/prompts/contract-term-extraction.txt` and
`resources/prompts/opportunity-explanation.txt` each contain a title line and
`TODO: Add prompt/template content.` That is a real gap, not an oversight of this pass:
`ContractExtractionService.loadTemplate` and `AiContextService.loadTemplate` will both
throw (`ValidationException` / `IllegalStateException`) on a missing or empty template, so
both model-calling paths fail closed until the prompts are authored. They were left
byte-identical deliberately: the renderer performs only `{{TOKEN}}` substitution and does
not strip comments, so a "documentation" comment added to a `.txt` template would be sent
to the model verbatim. The contract those files must satisfy is documented in
`AiTaskType`'s Javadoc and in `Flow of implementation` below.

#### File inventory

##### `client/` — outbound ports and their (stubbed) HTTP adapters

| File | Goal |
| --- | --- |
| `client/LlmPort.java` | The single verb this module is licensed to ask of a model: complete a prompt, capped in output tokens, at a caller-chosen temperature. Orchestrators depend on this interface and never on the HTTP client, so "summarise this" or "compute this" has no route to a transport. |
| `client/EmbeddingPort.java` | Embeds text for relevance ranking only. Justified as safe here because a vector is a position, not a statement — similarity can select which clauses fit the budget but cannot produce or favour a number. |
| `client/DocumentExtractionPort.java` | Turns raw document bytes into `ExtractedDocument` (text + SHA-256 + page count). Intended to have two interchangeable adapters: a local OpenPDF one and a remote one. |
| `client/LlmClient.java` | **Placeholder, untouched.** HTTP adapter for `LlmPort`; written in the transport pass. |
| `client/EmbeddingClient.java` | **Placeholder, untouched.** HTTP adapter for `EmbeddingPort`; written in the transport pass. |
| `client/DocumentExtractionClient.java` | **Placeholder, untouched.** HTTP adapter for `DocumentExtractionPort`; written in the transport pass. |
| `client/package-info.java` | States the package rule: depend on ports, never on clients, so every guardrail and cost rule is unit-testable with no network. |

##### `controller/` — HTTP boundary (both placeholders, untouched)

| File | Goal |
| --- | --- |
| `controller/ContractInterpretationController.java` | **Placeholder, untouched.** Intended endpoint that delegates `InterpretContractRequest` to `ContractInterpretationService`. |
| `controller/ExplanationController.java` | **Placeholder, untouched.** Intended endpoint that delegates `ExplainOpportunityRequest` to `AiExplanationService`. |

##### `dto/` — request and response bodies (built, not yet wired to a controller)

| File | Goal |
| --- | --- |
| `dto/InterpretContractRequest.java` | Addresses a contract by id plus *either* an object-storage key *or* inline text, never bytes. The inline branch exists so the extraction path can be driven in tests without OpenPDF. |
| `dto/ExplainOpportunityRequest.java` | Carries the deterministic context as text — the model's only window onto the numbers — plus an optional focus and optional candidate supporting terms to rank. |
| `dto/ContractInterpretationResponse.java` | Returns the extracted terms, an optional prose summary, the guardrail verdict, the input checksum and the run id, so a returned interpretation is reproducible. |
| `dto/ExplanationResponse.java` | Envelope for an explanation: the opportunity it explains, the `AiExplanation` (prose + verdict), the status and the input checksum. |
| `dto/package-info.java` | Records that these bodies deliberately carry no organization field: tenant scope comes from the authenticated principal, never from a request body. |

##### `enums/` — closed code sets (built)

| File | Goal |
| --- | --- |
| `enums/AiValidationStatus.java` | Sealed interface of five records: `PENDING`, `IN_REVIEW`, `CONFIRMED`, `DISPUTED`, `REJECTED`. Sealed rather than a plain enum so every site that switches on a verdict is compiler-checked for exhaustiveness, and so `DISPUTED` (content a human must review) stays distinct from `REJECTED` (no content at all). |
| `enums/AiProcessingStatus.java` | Lifecycle of a run (`PENDING`…`SKIPPED`). A plain enum because no variant carries behaviour; free-text reasons deliberately live on `ExtractionResult`/`AiAnalysis`, not on the code set. |
| `enums/AiTaskType.java` | The two permitted kinds of AI work, each bound to its prompt resource path. Keeps a template from ever being matched to the wrong task by a string typo. |
| `enums/CodedEnum.java` | The module-local contract for a persisted code: stable string code, case/whitespace normalisation on read, and a column-width assertion so an over-wide code fails loudly instead of truncating silently. |
| `enums/package-info.java` | Documents the sealed-interface-vs-enum convention and why `CodedEnum` is duplicated here rather than shared. |

##### `extraction/` — structured extraction (built)

| File | Goal |
| --- | --- |
| `extraction/ContractExtractionService.java` | The extraction engine: budget-checks the input, renders the prompt with only a safe contract id, calls the model at temperature 0.0, and refuses outright on refusal. It reads clauses; it does not compute money. |
| `extraction/StructuredExtractionValidator.java` | Parses the reply as JSON, enforces the allowed term-type vocabulary, and rejects any monetary field name before it is read. This is the extraction-side expression of the "AI never produces numbers" rule. |
| `extraction/ExtractionResult.java` | The output of one run: terms, both statuses, output tokens for cost, the input checksum, the source reference and an optional reason. Carries no money and no document. |
| `extraction/package-info.java` | States that the engine lives here and the transport does not, so guardrails and validation are testable against `LlmPort` without a network. |

##### `model/` — the immutable carries (built)

| File | Goal |
| --- | --- |
| `model/AiAnalysis.java` | The aggregate artefact handed to the rest of the system: terms, optional explanation, both statuses, input checksum, source reference, timestamp. It is the type-level fence — no `Money` field exists, so no consumer can mistake it for financial truth. |
| `model/AiExplanation.java` | Model prose plus the guardrail's verdict on it. The fence lives in the status, not the text: the text is the model's words and stays untrusted. |
| `model/ExtractedCommercialTerm.java` | One clause as the model reported it: type, text, page number for traceability, and a status that is `IN_REVIEW` on first production. |
| `model/ExtractedDocument.java` | Port DTO for parsed document text with its SHA-256 and page count. Transient by design: only the checksum is ever persisted. |
| `model/LlmMessage.java` | One chat message. Bundling role and content in a record makes a malformed request structurally impossible, which matters because the `SYSTEM` role is where the guardrail instructions live. |
| `model/LlmResponse.java` | The raw model reply plus token count and refusal flag. Untrusted by contract; a refusal must carry a reason so the run is auditable. |
| `model/package-info.java` | Records the module's central type claim: none of these records can hold money. |

##### `pdf/` — document rendering and parsing (both placeholders, untouched)

| File | Goal |
| --- | --- |
| `pdf/PdfExtractionService.java` | **Placeholder, untouched.** Intended local OpenPDF implementation of `DocumentExtractionPort`. |
| `pdf/AiPdfService.java` | **Placeholder, untouched.** Intended rendering of an AI artefact to PDF for reviewers. |

##### `service/` — orchestration and guardrails (built)

| File | Goal |
| --- | --- |
| `service/AiExplanationService.java` | Produces an explanation and enforces the financial-truth rule in code: derives the allowed-number set from the user message, calls the model at temperature 0.0, and downgrades to `REJECTED`/`DISPUTED` on refusal or foreign numbers. |
| `service/AiContextService.java` | Assembles prompts and nothing else. Keeps instructions in the system message and data in the user message, and templates only trusted ids — the structural half of the injection defence. Also ranks supporting terms by cosine similarity to stay inside the token budget. |
| `service/AiGuardrailService.java` | The stateless check set: input-size budget, refusal detection, injection heuristics, number extraction with canonicalisation, and `findForeignNumbers` — the thesis fence. |
| `service/ContractInterpretationService.java` | The contract-interpretation use case: resolves a stored document or inline text, parses it through the port, runs extraction, and returns an `AiAnalysis` with provenance rather than raw model output. |
| `service/package-info.java` | States the package rule and is explicit that injection/refusal detection are review signals, while the foreign-number check is the one hard block on the thesis. |

##### `resources/prompts/` — the two prompt templates (both placeholders)

| File | Goal |
| --- | --- |
| `prompts/contract-term-extraction.txt` | **Placeholder, untouched.** Must become the extraction system prompt: the clause vocabulary, the requirement to emit a JSON array of `{termType, description, pageNumber}` objects, and the instruction to emit no monetary field. It contains only `{{CONTRACT_REFERENCE}}` as a placeholder, and the document text is deliberately never substituted into it. |
| `prompts/opportunity-explanation.txt` | **Placeholder, untouched.** Must become the explanation system prompt: describe the supplied deterministic result in prose, restate only figures present in the context, and compute nothing. It contains only `{{FOCUS}}` and `{{OPPORTUNITY_ID}}`; the context text is deliberately never substituted into it. |

#### Flow of journey

##### Contract-term extraction

1. **A caller asks for a contract to be interpreted.** *Planned.* No endpoint exists —
   `ContractInterpretationController` is a placeholder. The entry point today is
   `ContractInterpretationService.interpret(org, InterpretContractRequest)` called
   directly.
2. **The source is recorded as provenance.** *Implemented.* A `SourceReference` of type
   `CONTRACT` is built with the contract id and, when the document was fetched, the upload
   key (`ContractInterpretationService.java:53`).
3. **The document text is resolved.** *Implemented.* Inline `contractText` short-circuits
   the fetch; otherwise the bytes come from `ObjectStoragePort.retrieve` and are parsed via
   `DocumentExtractionPort` (`ContractInterpretationService.java:68-86`). The stream is read
   and discarded inside the call, so no document is ever held on an artefact.
   *Gap:* no `DocumentExtractionPort` implementation exists yet, so the fetch branch raises
   `IllegalStateException` today.
4. **The input is budgeted and checksummed.** *Implemented.* SHA-256 over
   `ai/extraction:<org>:<source>:<text>`; over 12 000 characters the run returns `SKIPPED`
   with a reason instead of calling a model (`ContractExtractionService.java:69-77`).
5. **The prompt is built with the injection fence in place.** *Implemented.* The system
   message is the rendered template with only `{{CONTRACT_REFERENCE}}` substituted; the
   document text goes in as a separate `USER` message
   (`ContractExtractionService.java:83-87`).
6. **The model is called at temperature 0.0** with a 2048-token output cap.
   *Implemented.* A refusal short-circuits to `FAILED`/`REJECTED`
   (`ContractExtractionService.java:90-95`).
7. **The reply is schema-validated.** *Implemented.*
   `StructuredExtractionValidator.validate` parses JSON, rejects a non-array, rejects
   monetary field names, rejects unknown `termType`s, and stamps every surviving term
   `IN_REVIEW`.
   *Fails closed today:* the template file is a TODO placeholder.
8. **The run is returned as `ExtractionResult`** with `SUCCEEDED` / `IN_REVIEW`, the
   output-token cost and the checksum. *Implemented.*
9. **The interpretation response is assembled**, reusing the run id as the analysis id.
   *Implemented* at the service level; the HTTP exposure is *planned*.

##### Opportunity explanation

1. **A caller submits a deterministic context to be explained.** *Planned endpoint,* real
   request record. `ExplainOpportunityRequest` requires a non-blank `context` and carries
   the opportunity id, an optional `focus` and optional `supportingTerms`.
2. **The context is ranked and the prompt assembled.** *Implemented.* Instructions are
   rendered into the `SYSTEM` message from `{{FOCUS}}`/`{{OPPORTUNITY_ID}}` only; the
   context plus up to ten cosine-ranked supporting terms form the `USER` message
   (`AiContextService.java:58-67`, `106-115`).
3. **The allowed-number set is derived from the user message only.**
   *Implemented.* Every number in the user content is canonicalised into the set the reply
   is permitted to restate (`AiExplanationService.java:60-67`).
4. **The input checksum is computed** over
   `ai/explanation:<org>:<opportunityId>:<userContent>`, binding the artefact to tenant and
   to the exact facts shown. *Implemented.*
5. **The model is called at temperature 0.0**, capped at 2048 output tokens.
   *Implemented.* *Fails closed today* — the template is a TODO placeholder.
6. **A refusal is caught two ways.** *Implemented.* The port's own `refused` flag first,
   then `looksLikeRefusal` on the text, so a refusal phrased inside a normal-looking reply
   is still caught (`AiExplanationService.java:72-83`).
7. **The number fence runs.** *Implemented.* `findForeignNumbers` returns the reply's
   numbers minus the allowed set; a non-empty result yields `DISPUTED` with the offending
   figures named in the reason (`AiExplanationService.java:85-92`).
8. **A clean reply yields `CONFIRMED`.** *Implemented*
   (`AiExplanationService.java:94-95`). This is the only path in the module that produces a
   confirmed artefact, and it does so by having introduced no figure of its own.
9. **The response is wrapped** in `ExplanationResponse` with the verdict and checksum.
   *Implemented* at the service level; the HTTP exposure is *planned*.

#### Flow of implementation

##### How AI is fenced from financial truth

The fence is not one mechanism but four, each closing a hole the others leave.

**A type-level fence.** Nothing under `ai/model/` has a `Money` field. `AiAnalysis` and
`ExtractionResult` carry a `SourceReference` and an `inputChecksum`; `AiExplanation` and
`ExtractedCommercialTerm` carry text. A consumer therefore cannot receive an amount from
this module even if the model wanted to give one — there is no setter, no field, no
constructor parameter to put it in. This is the reason `ai` may sit *inside* the
financial product without becoming a source of financial truth.

**A prompt-shaped fence.** `AiContextService` and `ContractExtractionService` both build
exactly two messages: a system message holding instructions, and a user message holding
data. The `render` helper substitutes only fixed, trusted ids, so no document text, context
text or model-supplied string is ever interpolated into the instruction template. The
comment at `AiContextService.java:154-156` and `ContractExtractionService.java:79-82` and
`127-129` says why: a contract that contains `{{system := ...}}` cannot rewrite the model's
instructions, because that text is not in the template — it is a separate message. This is
the structural half of the injection defence and the reason the system/user split is
enforced in the type (`LlmMessage.Role`) rather than left to convention.

**An output fence (the thesis check).** `AiExplanationService` computes the allowed number
set *from the user message* — the one place numbers legitimately live — and then diffs the
model's own numbers against it. Anything unmatched is, by definition, a produced figure
rather than a described one, and the artefact is `DISPUTED` (`AiExplanationService.java:85-92`).
The check is intentionally over-cautious: a model that legitimately cites a section number
or a date it was not given trips it too, and gets reviewed. A false positive costs a
review; a false negative costs the thesis.

**A schema fence.** Extraction output is not prose and is not trusted as prose.
`StructuredExtractionValidator` rejects anything that is not a JSON array, and — the
load-bearing check — walks the field names of each object *before reading any value*, and
rejects the item if any of them is in `FORBIDDEN_FIELDS` (`amount`, `discount_percent`,
`variance`, `expectedAmount`, …). This is what stops a model that wants to be helpful from
"also" computing a discount: it cannot smuggle the number in as a clause. The vocabulary
check is the same idea applied to categories — `termType` must be one of eight codes, so a
hallucinated `DISCOUNT_PERCENT` clause type cannot be invented either.

##### Guardrails

`AiGuardrailService` is a stateless set of pure string functions, deliberately injectable
with its own input budget so a caller can tighten context without subclassing. The four
checks and their real weight differ, and the file says so:

- `isOversized` — a cost and latency guardrail, not a safety one.
- `looksLikeRefusal` and `looksLikeInjection` — *heuristics, explicitly not a security
  boundary.* A model that wants to be hijacked can usually find a tokenisation the literal
  phrase list misses. These are cheap first filters whose job is to make an artefact
  reviewable, and the `INJECTION_PHRASES` Javadoc says so in as many words rather than
  overselling the control.
- `findForeignNumbers` — the one hard block, and the only guardrail the module's thesis
  actually rests on.

Number extraction canonicalises before comparing: `4,299.00`, `4299` and `4299.0` all
collapse to `4299`. That collapse is load-bearing rather than cosmetic — a model that
restates a given figure in a different guise has not invented one, and the fence must not
punish it. The `NUMBER_TOKEN` pattern is correspondingly conservative, and a token the
pattern matches but `BigDecimal` rejects (e.g. `3.14.15`) is skipped rather than
misread (`AiGuardrailService.java:156-167`).

##### Structured-output validation

The validator is defensive at three layers, in this order: recover a JSON document from a
possibly-fenced reply (`parseJson`, documented as a shim, not a spec parser); require the
root to be an array; then per item require an object, run the forbidden-field walk, require
a known `termType`, require a non-blank `description` (accepting `text` as an alias), and
require `pageNumber >= 1`. Every failure is a `ValidationException` — a hard fail, not a
silent skip. A partially-valid array is not accepted, because "some of the model's output
was fine" is not a state any downstream consumer can reason about.

##### Confidence handling

Confidence is not a float here; it is a closed four-way verdict plus a lifecycle, because a
scalar would invite someone to treat "0.82 confidence" as usable. The rules:

- **Output defaults to `IN_REVIEW`.** Extracted terms and the extraction run are unconfirmed
  on production, always. Only a guardrail passing an explanation can yield `CONFIRMED`.
- **`DISPUTED` ≠ `REJECTED`.** `DISPUTED` means output exists but failed the fence, so an
  auditor has something to look at; `REJECTED` means there is no usable value at all. The
  distinction is preserved end to end, including in the boolean helpers
  `isConfirmed()` / `isOpenlyDisputed()`.
- **The seal makes the handling exhaustive.** `AiValidationStatus` is a sealed interface of
  records, so every `switch` over a verdict is a compile-time exhaustiveness check: adding a
  sixth state breaks the build at every site that acts on a status, which is exactly when
  you want to be interrupted.
- **Reasons travel with the artefact, not on the enum.** Free-text reasons ("explanation
  cites numbers absent from the deterministic context: [...]") live on `ExtractionResult`,
  `AiExplanation` and the response DTOs, keeping the code set closed and the audit trail
  intact.
- **Cost is first-class.** `outputTokens` is carried on `LlmResponse`, `ExtractionResult`,
  `AiExplanation` and the responses, so the project can bill per million output tokens
  without re-instrumenting anything.

##### Determinism

Both model calls pass temperature `0.0`, and both compute an input checksum *before*
calling, so the success and failure paths of a run carry it identically. The checksum binds
tenant, source/id and text (extraction) or tenant, opportunity id and user content
(explanation), which means the same inputs reproduce the same artefact and a checksum from
one tenant cannot be replayed into another. Time comes from an injected `DateTimeUtils`, not
`Instant.now()` (rules §4). Note the one place entropy remains: the run id is
`UUID.randomUUID()` inside the orchestrators, which is fine for a correlation id but would
not be acceptable for a financial result — `AiAnalysis` is a *record of a run*, not a result
whose identity is derived.

#### Key comments added

**`ContractExtractionService`** — that the checksum is computed up front so both the success
and the failure path carry it; that the document text is the `USER` message and never
substituted into the system template, with a contract's `{{system := ...}}` named as the
concrete injection it prevents; that the render helper is naive *by design* because only
trusted fixed ids pass through it; that temperature 0.0 is required for reproducibility
(§4); that extracted terms leave review `IN_REVIEW` rather than `CONFIRMED` because the
model is trusted to read, not to decide; that an oversized document is `SKIPPED` with a
reason instead of being refused outright, because splitting is the caller's call; that a
missing template is a startup defect rather than a runtime data error.

**`StructuredExtractionValidator`** — that the monetary-field check runs *before* any value
is read, so a figure masquerading as a clause is rejected wholesale; that the forbidden
field list is the fence itself; that `ALLOWED_TERM_TYPES` is a hand-kept duplicate of
`contract.enums.ContractTermType` because `ai` may not import `contract`, and that the
check is exactly what stops an invented `DISCOUNT_PERCENT`; that a non-array root is
rejected rather than guessed from; that `description`/`text` are accepted as aliases; and
that `parseJson` is a defensive shim, not a spec parser.

**`AiExplanationService`** — that the allowed-number set is derived from the user message
*and only* from the user message, which is what makes the fence sound; the `// THE FENCE`
comment on the foreign-number branch; that a refusal is checked twice because a refusal can
hide inside a normal-looking reply; that `MAX_OUTPUT_TOKENS` bounds both cost and the
surface a model has to invent a figure into.

**`AiContextService`** — that the class assembles prompts "and nothing more"; that keeping
instructions and data in two messages is the structural half of the injection defence,
stated as "data the model should not be able to rewrite is never part of the text it could
rewrite"; that ranking is a selection signal, never a semantic truth; that the zero-vector
guard exists because dividing by zero would otherwise rank everything as perfectly (or not
at all) similar; and that the candidate vectors are cached only for the duration of one
call, bounded by the request rather than by the model.

**`AiGuardrailService`** — that instance state is a configuration carrier (the injected
input budget) and everything else is pure; that the injection phrase list is explicitly
*not* a security boundary; that the refusal set is deliberately broad because a refusal is
better treated as a strong signal than as partial text to be trusted; that number
canonicalisation is what stops the fence punishing a reformatted restatement; that
`findForeignNumbers` is over-cautious on purpose, since a false positive costs a review and
a false negative costs the thesis; and that an unparseable token is skipped rather than
misread.

**`LlmMessage` / `LlmResponse` / `ExtractedDocument`** — that bundling role and content in
one record makes a malformed request structurally impossible, which matters because the
`SYSTEM` role carries the guardrails; that a refusal without a reason is a protocol
violation, because the reason is what makes the run auditable; that `ExtractedDocument` is
a port DTO and only its checksum is ever persisted.

**`AiValidationStatus` / `CodedEnum` / `AiProcessingStatus`** — that the sealed shape exists
so every status-handling `switch` is compiler-checked; that `all()` is a method and not a
constant because a static field holding nested `INSTANCE` references cannot initialise; that
a reason is free text and so does not belong on a closed code set; and that `CodedEnum` is
duplicated here word-for-word because module boundaries forbid the import.

**`ContractInterpretationService`** — that the stream is read and discarded inside the call
so the full document is never held on the artefact (only its checksum, §5); that the run id
doubles as the analysis id because one extraction maps to one interpretation.

**Package docs (`client`, `dto`, `enums`, `extraction`, `model`, `service`)** — that `model`
records can never hold money, which is the type-level fence; that `client` exists so
depending on an interface rather than an HTTP client is what makes the guardrails testable;
that `dto` bodies carry no organization field because scope comes from the principal; and
that `service`'s injection and refusal checks are review signals while the foreign-number
check is the hard block.

#### Verification

No compilation or test run was performed in this pass — the finishing brief scoped this to
comments and documentation only. Everything described above as "implemented" is implemented
in source; the two prompt-template placeholders and the seven placeholder classes are
called out above as stubs rather than described as working.


---

## 11. Investigation, reporting & background processing

#### Module goal

This slice is the outward-facing half of the system. Everything below it — ingestion, financial
normalization, the truth engine, evidence, opportunity detection, value realization — exists to
produce numbers somebody has to be able to defend. These three packages are where defense happens.

**Investigation** (`com.fintech.cfo.investigation`) is the answer to "where did this number come
from?" Given an opportunity, it walks the lineage graph and returns the chain that produced it:
source file → source row → canonical record → calculation result → opportunity → action → outcome.
It is a read-and-explain workstream with almost no state of its own, because its value is not a
second opinion on the number but the path a reviewer can walk independently. The three audit
constants that already exist for it (`INVESTIGATION_OPENED`, `INVESTIGATION_ASSIGNED`,
`INVESTIGATION_CLOSED` in `platform.audit.AuditEventType`) are the only trace the package is
permitted to leave that is not already in the lineage of another module.

**Reporting** (`com.fintech.cfo.reporting`) is the answer to "give me the same view on paper."
It renders a period's persisted truth — totals per currency, top opportunities, attribution
summary — into a PDF artifact that is stored, checksummed and served with
`Content-Disposition`. It computes nothing. Its integrity requirement is total: a figure that
appears on a generated PDF must be traceable to a persisted `Money`, and per the module rules
money is never summed across currencies here, only grouped.

**Background processing** (`com.fintech.cfo.processing`) is the answer to "do that again
tomorrow." It owns the Spring Batch scaffolding shared by all batch paths (`common`) and the
report-generation job specifically (`reporting`). Its job is not speed; it is that a run which
fails halfway can be restarted without duplicating or losing work, and that a run repeated with
the same parameters produces the same output.

These three are separate modules under §2 of the architecture rules and must not import one
another. `investigation` and `reporting` exchange data with `processing` only through the
integration milestone, not by direct type reference.

#### Implementation status

**Nothing in this slice is implemented. All 21 files are the 11-line architecture placeholders
emitted by the scaffold generator** — package declaration, a Javadoc block reading
`TODO: Implement <TypeName>.` / `Architecture placeholder generated by create_cfo_architecture_mega.py.`
/ `This file intentionally contains no business logic.`, an empty class declaration, and an inner
`// TODO: Implement <TypeName>.` comment. There is not one import, field, method, annotation or
type parameter in the slice.

That is stated plainly because the contracts described below are the *design* the file names and
the surrounding codebase encode, not behaviour that exists. Nothing enforces them today.

What does exist in the repository and constrains this slice:

- **Audit vocabulary** — `platform.audit.AuditEventType` already carries
  `INVESTIGATION_OPENED`, `INVESTIGATION_ASSIGNED`, `INVESTIGATION_CLOSED` under an
  `// --- investigation ---` group, and `REPORT_GENERATED`, `REPORT_EXPORTED` under
  `// --- reporting ---`. These are the events the services must raise; the storage contract means
  a constant cannot be renamed once written.
- **Async status vocabulary** — `shared.enums.ProcessingStatus` is documented as covering
  "ingestion, calculation, report" and deliberately separates `SKIPPED` from `FAILED` so that an
  ineligible unit is not alerted on as an error. It is the natural status set for a job execution
  row in `processing/common`.
- **Artifact storage** — `platform.storage.ObjectStoragePort` is the streaming port every generated
  blob must go through. Its `store(...)` contract takes an `InputStream` and a declared length and
  explicitly forbids closing the caller's stream; `StorageObject` returns a checksum (SHA-256) with
  no content field. `ReportArtifact` is the reporting-side record of such a write.
- **Exactly-once writes** — `platform.idempotency.IdempotencyService` claims a key in its own
  `REQUIRES_NEW` transaction *before* the work runs, and distinguishes `PROCEED` / `REPLAY` /
  `IN_PROGRESS`. That is the request-path tool; the batch path needs its own equivalent keyed on
  job parameters, which does not exist yet.
- **No schema.** The migrations stop at `V10__create_audit.sql`. There is no `V11` for
  investigations, reports or artifacts, which is why every `model/` and `repository/` type here is
  a plain class rather than an entity or port with a named table behind it. Per §11 the
  `controller/` and `repository/` files are also on the do-not-touch list.

For comparison, sibling slices are not in this state: `value` ships one real file
(`enums/CodedEnum`) with 23 documented placeholders, and `processing/financialtruth` and
`processing/ingestion` carry contract Javadoc on top of the same generator stub. This slice was
never given that treatment, which is why the contract below is reconstructed from
`docs/code-flow/04-ai-reporting.md` §D/§F, `docs/code-flow/13-stub-roadmap.md`, the audit enum and
the shared/platform types.

#### File inventory

##### `investigation/` — 7 files, all placeholders

| File | Goal |
| --- | --- |
| `investigation/controller/InvestigationController.java` | The HTTP surface for reviewing a finding: raise an investigation against an opportunity, read its state, and fetch the lineage graph. Untouchable per §11. Its intended shape is a thin adapter — tenancy from `SecurityContext`, `ApiResponse<T>` out, `springdoc` on every endpoint, and no entity ever returned. |
| `investigation/dto/CreateInvestigationRequest.java` | The request body for opening an investigation: which opportunity, why it is being raised, and optionally who owns it. It is a request type, so it carries no organization id — under §6 scope is never accepted from a client. |
| `investigation/dto/InvestigationResponse.java` | The read model for an investigation, mapping status, assignee and timestamps for display. Kept separate from the model so that adding an internal column cannot silently change the public contract. |
| `investigation/enums/InvestigationStatus.java` | The closed status set for an investigation, matching the three audit events: open → assigned → closed. Its real job is to make illegal transitions unrepresentable, and it needs terminality spelled out so a closed investigation cannot be silently reopened. |
| `investigation/model/Investigation.java` | The aggregate: the opportunity under review, its status, assignee, rationale and closing note. Its defining constraint is that it asserts no amount — the money belongs to the opportunity, and duplicating it here is how a review page ends up disagreeing with the ledger. |
| `investigation/repository/InvestigationRepository.java` | Persistence for investigations, scoped to the caller's organization and resolving strictly (not-found rather than forbidden for another tenant's row). Untouchable per §11. |
| `investigation/service/InvestigationService.java` | Owns the lifecycle: open, assign, close, each transition audited through `AuditService` and refused when illegal. It is also where the lineage walk is coordinated, because the graph is assembled per-investigation rather than stored on the investigation. |

##### `reporting/` — 10 files, all placeholders

| File | Goal |
| --- | --- |
| `reporting/controller/ReportController.java` | Requests report generation for a period and streams a generated artifact back with `Content-Disposition`. Untouchable per §11. Generation is expected to be asynchronous, so the create endpoint returns a report id and a status rather than a file. |
| `reporting/dto/GenerateOpportunityReportRequest.java` | The generation request: report type, the period or as-of date, and which opportunities or currencies to include. The date is a request for reproducibility — it must become the parameter set the batch run is keyed on. |
| `reporting/dto/ReportResponse.java` | The report's metadata as seen by clients — type, status, period, checksum, size, download link — never the PDF bytes, so polling a status never pulls a multi-megabyte payload. |
| `reporting/enums/ReportType.java` | The closed set of report kinds (period summary, opportunity detail, attribution). A closed enum because the type selects the renderer and the totals layout; an unknown value must fail loudly rather than fall back to a default layout. |
| `reporting/model/Report.java` | The generation record: type, period, status, parameters, and who asked for it. It is the row that makes a generated PDF attributable and reproducible — the same parameters must be able to regenerate it. |
| `reporting/model/ReportArtifact.java` | One stored rendering of a report: storage key, byte length, content type, SHA-256 checksum. Separate from `Report` because a report may be regenerated and each rendering is separately addressable and separately verifiable. |
| `reporting/pdf/PdfReportGenerator.java` | Turns an assembled report model into PDF bytes. Its safety obligations are the interesting part: bounded page count and output size, embedded fonts only, and no outbound calls while a document is open. |
| `reporting/pdf/PdfTemplateService.java` | Loads the versioned report templates and supplies the data-binding context. It is the boundary where "who controls the text in the PDF" is decided — templates are classpath resources under reporting's control, never client-supplied markup. |
| `reporting/service/OpportunityReportService.java` | Assembles an opportunity report from persisted truth: opportunity impacts grouped per currency, top opportunities, attribution summary. The rule it exists to honour is that it aggregates stored `Money` values and never converts or re-derives them. |
| `reporting/service/ReportService.java` | The orchestration point: validate the request, create the report record, hand off to the generator, store the artifact, record the checksum, and audit `REPORT_GENERATED`. It is also the place where idempotency and failure recording belong. |

##### `processing/common/` — 2 files, all placeholders

| File | Goal |
| --- | --- |
| `processing/common/JobExecutionService.java` | Shared execution bookkeeping for every batch job: start a run record, advance it through `ProcessingStatus`, and close it with a result. It exists so ingestion, calculation and report jobs all leave the same auditable trace rather than each inventing its own. |
| `processing/common/JobFailureHandler.java` | The single place that decides what a failure means — retryable versus fatal, per-item versus whole-chunk — and what gets recorded. Centralising this is what stops one job from alerting on a skipped unit and another from retrying a poison record forever. |

##### `processing/reporting/` — 2 files, all placeholders

| File | Goal |
| --- | --- |
| `processing/reporting/ReportJobConfiguration.java` | The Spring Batch job definition for report generation: one `Job`, one or more chunk-oriented steps, and the `JobParameters` contract (report type, period, tenant) that makes a re-run select exactly the same rows. |
| `processing/reporting/ReportJobLauncher.java` | The programmatic trigger for that job, used by the service layer and by the scheduler. It exists separately from the configuration so the job definition and the decision to run it are two different concerns with two different owners. |

#### Flow of journey

Every step below is **planned**. None of it executes today; the markers say where a step would sit
once the files carry code.

##### Raising and investigating an opportunity

1. **[planned]** A user asks the opportunity module to be investigated — via the opportunity's own
   `POST .../investigations` boundary, or via `investigation`'s controller once it exists.
2. **[planned]** `InvestigationService` resolves the opportunity *within the caller's tenant*
   (§6) and throws `NotFoundException` if it is not there, without distinguishing absent from
   foreign.
3. **[planned]** A `DRAFT`-free open transition creates the `Investigation` row and records
   `INVESTIGATION_OPENED` through `AuditService`. The opening rationale is mandatory: an
   investigation with no stated reason is a row that cannot be closed meaningfully.
4. **[planned]** Assignment moves the record to its assigned status and records
   `INVESTIGATION_ASSIGNED`. No money is touched on this path.
5. **[planned]** The reviewer opens the lineage view. The service walks
   source file → source row → canonical record → calculation result → opportunity → action →
   outcome, returning nodes and edges. Every node carries its `SourceReference`; a node that cannot
   be traced back to a source row is reported as such rather than rendered as a fact.
6. **[planned]** Findings from that walk are written back through the opportunity and value modules
   — the investigation module does not mutate them itself (§2: no cross-module imports).
7. **[planned]** Closure requires a written conclusion and records `INVESTIGATION_CLOSED`. Closing
   is terminal: the reopened-work case is a new investigation, so history is additive.

##### Generating a PDF report

1. **[planned]** `POST` on `ReportController` with `GenerateOpportunityReportRequest` — type,
   period, scope. Tenant comes from the principal, never the body (§6).
2. **[planned]** `ReportService` validates the request and creates a `Report` row in a pending
   state. Synchronous generation is the alternative and is wrong for a period-wide report: PDF
   rendering is CPU-bound and unbounded in duration.
3. **[planned]** `ReportJobLauncher` starts the batch job with those parameters; a caller-supplied
   idempotency key (§ `IdempotencyService`) prevents a double-click producing two artifacts.
4. **[planned]** `OpportunityReportService` assembles the content from persisted truth only —
   impacts grouped per currency, top opportunities, attribution summary — reading stored
   `Money` values and never recomputing them.
5. **[planned]** `PdfTemplateService` loads the versioned template and supplies the binding
   context; `PdfReportGenerator` renders it with resource limits in force.
6. **[planned]** The bytes go to `ObjectStoragePort.store(...)` as a stream. `ReportArtifact` is
   written with the returned key, length and SHA-256 checksum.
7. **[planned]** The report moves to succeeded and `REPORT_GENERATED` is recorded. The response
   carries metadata only; the download itself is a separate read that raises `REPORT_EXPORTED`.

##### A background batch run

1. **[planned]** A trigger — scheduler, request thread, or operator — asks a `*JobLauncher` to run
   a job with an explicit `JobParameters` set (tenant, period, job type). Parameters are the
   reproducibility handle; a run with no parameters cannot be repeated.
2. **[planned]** `JobExecutionService` opens a run record in `PENDING`, moves it to `RUNNING`, and
   stamps the `JobInstanceId` so the Spring Batch execution and the business row are the same
   unit of audit.
3. **[planned]** The step reads its slice, processes per item, and writes per chunk. Each chunk is
   one transaction.
4. **[planned]** A per-item failure is filtered and counted; a fatal failure (template missing,
   storage unreachable, no tenant in scope) stops the step. The distinction lives in
   `JobFailureHandler`.
5. **[planned]** On completion the run moves to `SUCCEEDED`; on abandonment it becomes `FAILED`
   with the reason stored separately from the status, per `ProcessingStatus`'s own Javadoc. A unit
   that was never eligible becomes `SKIPPED`, never `FAILED`.
6. **[planned]** Re-running with identical parameters resumes rather than duplicates, because the
   writer is idempotent on run identity — not yet true of anything.

#### Flow of implementation

##### Batch job design

**Chunking is the transaction boundary, and everything else follows from that.** In
`processing/reporting`, the natural unit of work is one report: assemble, render, store, record.
A chunk that spans several reports commits atomically, so a crash repeats the whole chunk including
work that had already succeeded — which for PDF rendering is expensive and, without a deterministic
write, duplicative. The intended shape is one report per chunk, giving exactly-once-per-report
semantics at the cost of a larger number of small transactions. The trade is explicit rather than
tuned: reports are large and infrequent enough that commit overhead is not the bottleneck.

**Job parameters are the reproducibility contract.** `ReportJobConfiguration` must declare
`JobParameters` for report type, period and tenant, because the batch path has no request body to
carry an as-of date. Without them the job could not select the same rows twice, and §4's
requirement that a calculation be reproducible months later would fail for generated reports just
as it would for the truth engine. The launcher, not the configuration, supplies them — that split is
why `ReportJobLauncher` is a separate type.

**Restartability follows from idempotent writes, not from a restart flag.** A restarted chunk
re-reads the same reports and must recognise that a report it is about to write already exists. Two
mechanisms are available and neither is in place: compare the artifact checksum for an existing
`(report, type, period)` tuple, or claim the run through a key derived from the job parameters in
the same spirit as `IdempotencyService.claim`. The first is cheaper and is the one the artifact
checksum exists to serve; the second is the general answer and is what `JobExecutionService` would
own if `IdempotencyService` proved too request-shaped to reuse.

**Skip, retry and fatal are three different outcomes, and the middle one is a decision.** The
policy `processing/common` exists to make uniform:

| Failure kind | Example | Intended response |
| --- | --- | --- |
| Per-item, transient | Storage timeout on one artifact write | Retry a bounded number of times with backoff, then fail that item and continue the chunk |
| Per-item, deterministic | A template that cannot resolve one optional field | No retry — it would fail identically. Skip the item, count it, keep going |
| Fatal, step-wide | No tenant in scope; template resource absent | Stop immediately. Retrying a configuration error only delays the alert |

`ProcessingStatus.SKIPPED` exists precisely so the second row is not recorded as `FAILED`; a batch
that was never eligible is not an error and must not page anyone. `JobFailureHandler` is the single
owner of this table, which is what stops each job from making its own inconsistent version.

**The writer owns transaction scope; the configuration only chooses chunk size.** Mirroring the
division already used by `processing/financialtruth`, `ReportJobConfiguration` selects the chunk
size and nothing else, and per-item atomicity is enforced where the writes happen.

##### PDF generation constraints

PDF rendering is the one place in the system where a malformed input becomes a memory exhaustion or
a file written outside the tenant's scope, so `reporting/pdf` carries obligations that ordinary
service code does not.

*Bounded output.* A report over an unbounded opportunity set must have a page and row ceiling.
Without one, a period containing a hundred thousand findings is a heap exhaustion in a batch worker
rather than a paginated report. The ceiling belongs in the generator, because that is the only place
that can stop mid-render.

*Bounded memory.* Font subsets must be embedded, and the template must not require loading every
image at once. The storage port's own Javadoc makes the same argument from the other side: an
evidence PDF held in memory is a multi-megabyte allocation per concurrent request, which is why
`store` takes a stream rather than a byte array. A report generator that builds a full `byte[]` and
then hands it over defeats that design.

*No outbound calls while rendering.* If the renderer can reach the network or a database mid-render,
a slow dependency turns into an open document held for the duration. The assembler reads everything
first; the generator only lays out what it is given.

*Templates are ours, not the caller's.* `PdfTemplateService` loads versioned classpath resources.
Accepting template markup or a template path from a request would make every generated PDF an
arbitrary-content document with our name on it. Data is bound into the template; the template
itself never comes from outside.

*Determinism.* A regenerated report for the same parameters should be byte-comparable, which is why
`Report` carries its parameters and `ReportArtifact` carries a checksum. Locale-sensitive formatting
(`toUpperCase()` without `Locale.ROOT`), a `LocalDate.now()` in a footer, or a `HashMap`-ordered
section would each break that, and §4 forbids all three in the calculation path this reports on.

*Artifacts are references, never payloads.* `ReportArtifact` stores a key and a checksum, not the
PDF body, for the same reason §5 states for evidence: a business row that carries its document is a
row that cannot be migrated, deduplicated or evicted independently of the data.

##### Investigation workflow

The investigation module's design constraint is that it is a *view*, not a second source of truth.
It asserts no monetary amount, duplicates no calculation and stores no copy of the graph — the
lineage nodes and edges are assembled on request from the modules that own them, through the
integration milestone.

The status set exists to keep the lifecycle closed. Three audit events define it: open, assign,
close. Each is a transition, not a state label, matching how `AuditEventType` is used everywhere
else in the codebase. Closure is terminal and always carries a written conclusion, so the audit
question "was this looked at, by whom, and what was decided" has a row to answer it.

The walk itself is a DAG traversal, and two properties are worth stating because they are the
failure modes: the graph is acyclic by construction (a cycle would mean an outcome justified by a
plan that the outcome justified), and a node that cannot be resolved to a source row is reported as
an unresolved link rather than skipped. A lineage view that silently omits the hop it could not
resolve is worse than no lineage view, because it looks complete.

Tenancy applies twice over: every read filters on the caller's organization, and a row belonging to
another tenant is reported as not-found (§6) rather than forbidden.

#### Key comments added

**None.** Every one of the 21 files in this slice is the generator's 11-line placeholder, and §11
plus the task's own scope put stub files out of bounds for editing — the `investigation/controller`
and `investigation/repository` files are named explicitly, and the remaining 19 are the same
unimplemented stub with no executable content to annotate.

No existing comment was deleted, reworded, or reflowed anywhere in the slice, and no file in the
slice was modified at all. `git status` for these paths is clean.

For the record, the stub Javadoc that was preserved verbatim in all 21 files reads:

```java
/**
 * TODO: Implement InvestigationController.
 *
 * Architecture placeholder generated by create_cfo_architecture_mega.py.
 * This file intentionally contains no business logic.
 */
```

Worth noting for whoever implements this next: the sibling slices reached their current state by
*adding* contract Javadoc above that block, which is the pattern `processing/financialtruth` and
`processing/ingestion` already use, and the pattern `docs/code-flow/13-stub-roadmap.md` calls
"SPEC — Javadoc IS the spec". That work is not done for this slice.

---

### Verification

The slice compiles clean in isolation. All 21 files are empty classes, so this is a weaker check
than for a slice with real code — it confirms no file in the slice references anything missing, and
nothing more. The compile was run with annotation processing disabled so the pre-existing,
unrelated MapStruct errors in `financial/mapper/**` cannot mask the result:

```powershell
mvn -o dependency:build-classpath -Dmdep.outputFile=target/cp.txt
javac -nowarn -proc:none -d target/slicecheck-irp -cp "@target/cp.txt" `
  src/main/java/com/fintech/cfo/investigation/**/*.java `
  src/main/java/com/fintech/cfo/reporting/**/*.java `
  src/main/java/com/fintech/cfo/processing/common/*.java `
  src/main/java/com/fintech/cfo/processing/reporting/*.java
```

`mvn compile` remains red repo-wide on those MapStruct errors; that is pre-existing and not from
this slice. No tests exist for this slice, and per §12 none could be run here — there is no Docker
or local Postgres, and `mvn compile` is red for unrelated reasons. Nothing here starts the
application.

### Known gaps

- All 21 files are unimplemented. Every invariant described in this document is unenforced.
- `InvestigationController` and `InvestigationRepository` are on the §11 do-not-touch list and were
  left byte-identical; the other 19 files are the same kind of stub but are not covered by that rule
  explicitly, so a future pass may add contract Javadoc to them.
- There is no `V11` migration. `Investigation`, `Report` and `ReportArtifact` have no table behind
  them, which is why no entity or port exists to write.
- No port interface exists for investigation or report persistence. §11 asks for a narrow port
  where a component would need to read or write persisted state; neither module declares one yet.
- `processing/reporting` has two files, but `docs/code-flow/13-stub-roadmap.md` counts
  "Processing 8" — which accounts for the four `ingestion` and four `financialtruth` files only.
  The report job appears to have been added after that count was written. No other doc mentions
  `ReportJobConfiguration` or `ReportJobLauncher`, so the report batch path is undocumented
  upstream of this file.
- `docs/code-flow/04-ai-reporting.md` §D describes reporting totals as coming "from FinancialImpact
  per currency". No `FinancialImpact` type exists in the repository; `reporting` must read the
  impact records that `opportunity` owns, through the integration milestone. Treat the name as
  aspirational.
- Nothing in the repository imports `com.fintech.cfo.investigation.*`, `com.fintech.cfo.reporting.*`
  or `com.fintech.cfo.processing.*`. There are no consumers yet, so the unimplemented slice is not
  currently breaking anything — but `reporting` is designed to consume `opportunity` and
  `financialtruth` output, and `investigation` is designed to consume the lineage owned by
  `evidence` and `opportunity`.

---

## 12. Runtime configuration & database schema

#### Goal — configuration

This slice owns the two things that decide what the application *is* at a given moment: the
property values it starts with, and the database it starts against. Neither is business logic, and
neither belongs to any one business module, so both live in `src/main/resources` where every module
depends on them and none of them owns them.

The configuration design follows one rule throughout: **the base file must be runnable and the
profile files must be small.** `application.yml` declares everything needed to boot — datasource,
JPA, Flyway, actuator, storage, ingestion limits, AI limits — and each `application-<profile>.yml`
declares only what genuinely differs. A developer's laptop, a CI run and a production pod are
therefore three short overlays on one shared definition rather than three independently maintained
configurations that drift apart. The corollary is that every security-relevant value is *absent by
default and supplied from the environment*: no password, no OIDC issuer and no AI endpoint is
committed, so a leaked repository cannot leak a credential and a misconfigured deployment fails to
boot rather than booting against the wrong resource. The final rule is that the schema has exactly
one writer. Flyway owns it; Hibernate is held in `validate` in every profile including prod; and a
mapping that disagrees with a migration must fail the boot instead of being silently repaired.

#### Goal — schema

The schema's purpose is narrower than "store the application's data" and that narrowness is the
point: it exists to make a monetary claim **defensible**. Every structural decision in the ten
migrations traces back to one of three questions a CFO or an auditor will eventually ask — *which
tenant does this belong to, where did this number come from, and why is this total the total*.
Tenant scoping is enforced by carrying `organization_id` on every table and putting it in every
unique constraint, so isolation is a property of the data rather than of a filter someone remembers
to apply. Provenance is enforced by keeping raw ingestion immutable and by threading lineage from
source file to calculation result. Determinism is enforced by storing money as `NUMERIC` with an
explicit currency beside it, by pinning `rule_version` and `input_checksum` on a calculation run, and
by refusing at the database level any amount that appears without its currency.

The schema is additive and ordered, never rewritten: V1 through V10 build it in dependency order,
and the invariants a table depends on are created before the tables that reference them.

#### File inventory

| Path | Goal |
| --- | --- |
| `src/main/resources/application.yml` | The base configuration every profile overlays. Declares datasource and Hikari sizing, JPA with `open-in-view: false` and `ddl-auto: validate`, Flyway on `classpath:db/migration`, virtual threads, suppressed server error detail, an explicit actuator allowlist, and the `cfo.*` application-owned keys bound by `@ConfigurationProperties`. Runnable on its own with no profile active. |
| `src/main/resources/application.properties` | A one-line companion file. Spring Boot loads `.properties` *after* `.yml` and properties win on collision, so this file repeats only `spring.application.name` — the effective value is unchanged, and the file stays a valid independent configuration source that has somewhere to put comments. |
| `src/main/resources/application-dev.yml` | Shared development overlay. Turns on `show-sql` with `format_sql`, raises `com.fintech.cfo` to DEBUG while holding Spring Security at INFO, and samples 100% of traces. |
| `src/main/resources/application-prod.yml` | Production overlay. Repeats `ddl-auto: validate` and `show-details: never` so the file reads as the authoritative production posture, adds `server.shutdown: graceful` for rolling deploys, sets root logging to WARN with a console pattern that drops the tenant id from shipped logs, and samples 10% of traces. |
| `src/main/resources/application-test.yml` | Test overlay. Keeps `ddl-auto: validate` (the test schema is built by the same migrations as production), re-enables `spring.flyway.clean-disabled: false` so a test run can reset, quiets framework logging while raising `org.flywaydb` to INFO so migration failures are visible, and sets `cfo.application.environment: test`. |
| `src/main/resources/logback-spring.xml` | Logback configuration, loaded under the `logback-spring.xml` name so it participates in Spring's configuration lifecycle. Two appenders (console and a size-and-time rolling file) sharing one pattern that carries the correlation id and tenant id from the MDC. Pins `org.hibernate.SQL` at WARN so no environment variable can enable SQL parameter logging. |
| `db/migration/V1__create_organizations.sql` | The tenant root. Every later table has an `organization_id` foreign key to it, so this is first by necessity. Declares per-tenant `base_currency` and `timezone`, an `active` flag instead of deletion, and the first `version` column. |
| `db/migration/V2__create_users_roles.sql` | Global users plus the RBAC catalogue and the tenant-scoped `memberships` table that is the authoritative authorization boundary. Case-insensitive unique email via `lower(email)`, uniqueness on `(user_id, organization_id, role_id)` so one user may hold several roles. |
| `db/migration/V3__create_ingestion.sql` | The untrusted-data entry point: `source_files` (with declared vs detected type and a security verdict), `ingestion_runs`, immutable `source_records` with `UNIQUE (source_file_id, row_number)`, and `ingestion_errors`. Nothing here is updated in place. |
| `db/migration/V4__create_financial_data.sql` | The canonical financial model: customers, products, accounting periods, invoices, `invoice_lines` and `financial_transactions`. Carries the schema's most consequential constraint, `ck_invoice_lines_total`, plus the date-range CHECK on periods. |
| `db/migration/V5__create_contracts.sql` | Commercial terms: `contracts`, `contract_terms`, `pricing_terms`, `discount_terms`, `commercial_rules`. Bitemporal via `effective_from` plus nullable `effective_to`, with both a business `term_version` and a locking `version`. Deliberately no unique constraint — terms legitimately overlap by specificity. |
| `db/migration/V6__create_calculations.sql` | The truth engine: `calculation_runs` pinning `rule_version` and `input_checksum`, and append-only `calculation_results` holding expected/actual/variance/impact as separate amount-currency pairs. Two CHECK constraints forbid an amount appearing without its currency. |
| `db/migration/V7__create_evidence_lineage.sql` | Provenance. Content-addressed `evidence_snapshots`, `evidences` with a source file and row pointer, `evidence_references` as typed edges, and a general `lineage_nodes`/`lineage_edges` graph. Every reference column is polymorphic and unconstrained by foreign key. |
| `db/migration/V8__create_opportunities.sql` | The central product object and its children: `opportunities` (the Economic Opportunity Record), `opportunity_impacts`, append-only `opportunity_findings`, `opportunity_reviews` and `opportunity_lifecycle_events`, single-owner `opportunity_assignments`, and `investigations`. |
| `db/migration/V9__create_value_tracking.sql` | Closing the loop: `action_plans` → `action_executions` → `outcomes` → `realized_values` → `value_attributions`. Four steps rather than one, so a plan never executed or an execution never measured is still recordable. Ends with the schema's only non-negativity constraint. |
| `db/migration/V10__create_audit.sql` | `audit_events` (append-only, four indexes each answering one audit question, no foreign keys to anything) and `idempotency_records`, the one table with a genuine update path and the only globally-unique business key in the schema. |

#### Flow of journey

How the configuration resolves at startup, in order:

1. **Active profiles are determined.** From `SPRING_PROFILES_ACTIVE`, or from nothing at all. There
   is no `spring.profiles.active` set in any file in this slice, so a run with no environment
   variable activates **no** profile and uses the base file alone. This is deliberate — the base
   file is complete, and a hard-coded active profile would silently override an operator's choice.
2. **`application.yml` is loaded** and every key in it becomes a property. Three distinct namespaces
   live here and they are not interchangeable:
   - `spring.*` — framework-owned keys (datasource, JPA, Flyway, servlet, virtual threads).
   - `management.*` and `server.*` — framework-owned keys for actuator and the servlet container.
   - `cfo.*` — application-owned keys, bound by `platform.config.ApplicationProperties` and its
     sibling `@ConfigurationProperties` records through `@ConfigurationPropertiesScan`. Services never
     read these keys directly; the platform hands them immutable typed records, so a configuration
     change cannot leak into a business module and a half-applied value can never be observed.
3. **The matching profile file is overlaid**, and only if its `spring.config.activate.on-profile`
   matches. Spring Boot's profile-specific documents have *lower* precedence than the base document,
   so an overlay can change a value but cannot accidentally delete one. Which profile sets what:
   - **`dev`** sets `jpa.show-sql: true`, `hibernate.format_sql: true`,
     `logging.level.com.fintech.cfo: DEBUG`, `logging.level.org.springframework.security: INFO` and
     `management.tracing.sampling.probability: 1.0`. It inherits the base datasource, Flyway,
     storage root, actuator allowlist and the AI block untouched.
   - **`prod`** sets `jpa.show-sql: false`, restates `hibernate.ddl-auto: validate`,
     `server.shutdown: graceful`, `logging.level.root: WARN`, `logging.level.com.fintech.cfo: INFO`,
     a console log pattern that keeps the correlation id but drops the tenant id, sampling `0.1`, and
     restates `management.endpoint.health.show-details: never`.
   - **`test`** restates `hibernate.ddl-auto: validate`, sets `spring.flyway.clean-disabled: false`,
     quiets `root`, `com.fintech.cfo`, `org.springframework.security` while raising
     `org.flywaydb` to INFO, and sets `cfo.application.environment: test`.
   - Nothing sets `staging`. `ApplicationProperties.Environment` declares `STAGING` as a constant and
     this slice ships no `application-staging.yml`, so a staging deployment runs on the base file.
4. **`application.properties` is loaded last** and wins any collision. It declares only
   `spring.application.name=cfo`, which is identical to the base YAML value, so the effective
   configuration is exactly what a reader of `application.yml` would expect. This file exists so
   there is a valid, independently loadable properties source; everything that needs explaining lives
   in the YAML.
5. **`logback-spring.xml` is evaluated** by the logging system once the environment is available,
   substituting `${LOG_LEVEL:-INFO}` from the environment. It is a `logback-spring.xml` rather than a
   `logback.xml` so it is parsed inside Spring's configuration lifecycle and can reference
   `<springProfile>` if a future change needs one.
6. **Spring beans are created** from the resolved environment. Flyway's `FlywayAutoConfiguration` and
   Hibernate's `HibernateJpaAutoConfiguration` both depend on the `DataSource` bean, so bean creation
   for the persistence layer is what actually opens the connection.
7. **Flyway migrates.** `FlywayMigrationInitializer` is a bean that runs `migrate()` on the
   `classpath:db/migration` location before the application context is fully refreshed. It applies
   V1 through V10 in version order, recording each in `flyway_schema_history`, and fails the boot on
   any checksum mismatch — which is the mechanism that makes an edited, already-applied migration an
   error rather than a silent divergence. With `baseline-on-migrate: true`, a pre-existing database
   with no history is baselined at version 1 rather than refused.
8. **JPA validates.** With `ddl-auto: validate` and `open-in-view: false`, Hibernate checks that the
   mapped entities agree with the migrated schema and then creates the `EntityManagerFactory` and
   `SessionFactory`. Note the scope of this check: `validate` inspects only classes carrying JPA
   annotations, and in this codebase that is `platform.audit.AuditEventEntity` and
   `platform.idempotency.IdempotencyRecord`. The other tables have no entity mapping at all, so they
   are created by Flyway and trusted rather than validated. That is not a defect in the configuration —
   it is a consequence of the domain models being plain records — but it means `validate` is a much
   narrower guarantee than the key name suggests.
9. **The web server starts** on `${CFO_SERVER_PORT:8080}` with virtual threads enabled, error detail
   suppressed, and the actuator endpoints restricted to `health,info,metrics,prometheus`.

#### Schema build order

Flyway applies these in strict version order. The order is dictated by foreign-key dependency, with
one deliberate exception noted at V10.

1. **V1 — `organizations`** (module: identity). Creates the tenant root. No other table can be
   created before it, because every later table references it. Invariant enforced: `id` is a
   client-supplied UUID (so a whole object graph can be named in one transaction);
   `registration_number` is globally unique among non-null values via a partial unique index;
   `version` guards against a lost update that would change a tenant's reporting currency.
2. **V2 — `users`, `roles`, `permissions`, `role_permissions`, `memberships`** (module: identity).
   Depends only on V1. Invariants: email is unique case-insensitively; role code and permission code
   are unique globally so they can be cited as authorization keys; `role_permissions` has a composite
   primary key making duplicate grants unrepresentable; memberships are unique per
   (user, org, role) and cascade from both organization and user but **not** from role, so access
   cannot be revoked by deleting a role definition.
3. **V3 — `source_files`, `ingestion_runs`, `source_records`, `ingestion_errors`** (module:
   ingestion). Depends on V1. Invariants: content is addressed by
   `UNIQUE (organization_id, checksum_sha256)`, so identical bytes are ingested once per tenant;
   `source_records` is unique per `(source_file_id, row_number)`, so a retried parse replaces rather
   than duplicates; `security_status` starts `PENDING` and the parser is not reached until it is not.
4. **V4 — `customers`, `products`, `accounting_periods`, `invoices`, `invoice_lines`,
   `financial_transactions`** (module: financial). Depends on V1 and V3 (`invoices.source_file_id`
   and `financial_transactions.source_file_id` reference `source_files`). Invariants: import
   idempotency via partial unique indexes on
   `(organization_id, source_system, external_key)`; invoice numbers unique per source system;
   `ck_accounting_periods_range` forbids `end_date < start_date`;
   `ux_invoice_lines_invoice_line` makes line position the line's identity;
   **`ck_invoice_lines_total` asserts `line_total = (quantity * unit_price) - discount_amount +
   tax_amount`**, encoding money precision in the schema rather than only in a service.
5. **V5 — `contracts`, `contract_terms`, `pricing_terms`, `discount_terms`, `commercial_rules`**
   (module: contract). Depends on V4 (customers, products). Invariants: `ck_contracts_range` and
   `ck_contract_terms_range` both forbid an end date preceding a start date; contract numbers unique
   per tenant; `rule_code` unique per tenant because a calculation result cites it;
   `ix_pricing_terms_lookup` and `ix_discount_terms_lookup` put the whole effective-term predicate in
   the index key. No unique constraint on any term table, because overlapping terms of differing
   specificity are the design.
6. **V6 — `calculation_runs`, `calculation_results`** (module: financialtruth). Depends on V1.
   Invariants: `ck_calc_results_variance_currency` and `ck_calc_results_impact_currency` forbid an
   amount from existing without its currency (the schema's central safety property); `ix_calculation_
   runs_checksum` makes reproducibility a lookup rather than an assertion; results are never updated,
   so a correction is a new run.
7. **V7 — `evidence_snapshots`, `evidences`, `evidence_references`, `lineage_nodes`, `lineage_edges`**
   (module: evidence). Depends on V3 (`evidences.source_file_id`) and, through
   `evidence_references.evidence_id`, on itself. Invariants: content-addressed uniqueness on
   `(organization_id, subject_type, subject_id, content_hash)` so re-capturing identical content is a
   no-op; edge identity is the endpoint tuple, making re-running the lineage builder idempotent;
   all polymorphic reference columns are deliberately unconstrained by foreign key.
8. **V8 — `opportunities` and its six children, plus `investigations`** (modules: opportunity, and
   investigation for the last table). Depends on V6 (`calculation_run_id`, `primary_result_id`,
   `opportunity_findings.calculation_result_id`). Invariants: `reference` unique per tenant;
   `ux_opportunity_assignments_opp` on `opportunity_id` alone enforces exactly one current assignee;
   `investigation.reference` is unique per tenant but *not* per opportunity, because an opportunity may
   be investigated more than once; reviews and lifecycle events are append-only.
9. **V9 — `action_plans`, `action_executions`, `outcomes`, `realized_values`, `value_attributions`**
   (module: value). Depends on V8 (`opportunity_id`). Invariants:
   `ck_value_attributions_non_negative` is the schema's *only* non-negativity rule, and it is scoped
   to attribution alone because a negative attribution has no interpretation while a negative
   `realized_values.amount` (a clawback) does. `opportunities` and `opportunity_id` cascade, so
   deleting an opportunity removes the whole chain; `outcome_id` and `action_execution_id` do not.
10. **V10 — `audit_events`, `idempotency_records`** (module: platform). Depends on nothing and is
    depended on by nothing. Invariants: `audit_events` has **no foreign keys at all** — it must be
    able to record events about deleted entities and survive the deletion of an organization — and is
    append-only, which is why `occurred_at` is never updated;
    `ux_idempotency_key` is global rather than per-tenant, deliberately, so a tenant-scoping bug
    surfaces as a constraint violation instead of silently serving another tenant's cached response;
    `version` plus `@Version` makes the double-execution race resolve to one winner.

#### Table-to-module map

| Module (package) | Tables | Migration |
| --- | --- | --- |
| identity (`com.fintech.cfo.identity`) | `organizations`, `users`, `roles`, `permissions`, `role_permissions`, `memberships` | V1, V2 |
| ingestion (`…ingestion`) | `source_files`, `ingestion_runs`, `source_records`, `ingestion_errors` | V3 |
| financial (`…financial`) | `customers`, `products`, `accounting_periods`, `invoices`, `invoice_lines`, `financial_transactions` | V4 |
| contract (`…contract`) | `contracts`, `contract_terms`, `pricing_terms`, `discount_terms`, `commercial_rules` | V5 |
| financialtruth (`…financialtruth`) | `calculation_runs`, `calculation_results` | V6 |
| evidence (`…evidence`) | `evidence_snapshots`, `evidences`, `evidence_references`, `lineage_nodes`, `lineage_edges` | V7 |
| opportunity (`…opportunity`) | `opportunities`, `opportunity_impacts`, `opportunity_findings`, `opportunity_reviews`, `opportunity_lifecycle_events`, `opportunity_assignments` | V8 |
| investigation (`…investigation`) | `investigations` | V8 |
| value (`…value`) | `action_plans`, `action_executions`, `outcomes`, `realized_values`, `value_attributions` | V9 |
| platform (`…platform`) | `audit_events`, `idempotency_records` | V10 |

Two modules have no tables: **reporting** (`Report`, `ReportArtifact` in
`com.fintech.cfo.reporting.model`) and **processing** (`JobExecutionService` and the three job
configurations). `reporting` has `Report` and `ReportArtifact` Java types with no persistence, so
generated reports are described in the module design but have nowhere to be stored. **ai** is the
same — `AiAnalysis`, `AiExplanation` and `ExtractedCommercialTerm` are in-memory types by design,
since the AI module is explicitly non-authoritative and its output is persisted into the tables it
derives from rather than stored on its own.

#### Flow of implementation

**Why configuration is layered the way it is.** Spring Boot resolves `application-<profile>.yml`
*on top of* `application.yml` at a lower precedence, so the base file is a floor that every profile
inherits and can only adjust. That is why `application-dev.yml` is nineteen lines: it is not a
skeleton, it is the complete set of differences between a developer's runtime and the base runtime.
`application.properties` is deliberately almost empty — because `.properties` wins over `.yml`, a
properties file that quietly re-declared a base key would silently defeat the YAML, so it declares
one key and that key is identical to the YAML's.

**Why the security-sensitive keys are empty by default.** `spring.datasource.password`,
`cfo.security.issuer-uri`, `cfo.ai.model` and `cfo.ai.base-url` all resolve to empty. This is not
inconvenience: an OIDC issuer URI with a permissive default would mean a deployment that forgot to
set it authenticates nothing and accepts everything, and a database password with a working default
means a developer's local value silently ships. Failing at startup is the correct failure for all
four.

**Why `open-in-view: false` and `ddl-auto: validate`.** Open Session in View would hold a database
connection across response rendering and silently re-enable lazy loading in the view layer, which is
how a missing join fetch becomes a production N+1. `validate` makes the migration set the single
source of truth for the schema; `application-prod.yml` restates it so the one setting that must never
be relaxed is visible in the file that gets reviewed at deploy time.

**Why the trace sampling probability is set per profile and not in the base.** Base `management.*`
declares only endpoint exposure and health probes. Sampling has no safe default — 1.0 is unaffordable
and 0.0 is useless — so dev takes the diagnostic cost and prod takes the storage cost.

**Why `org.hibernate.SQL` is pinned at WARN in logback while `application-dev.yml` turns SQL on.**
The two are not in conflict. `application-dev.yml` sets `spring.jpa.show-sql`, which is scoped to the
dev profile where the output is a developer's console; `logback-spring.xml` pins the Hibernate SQL
*logger* at WARN, which no environment variable can override, because that logger emits bound
parameter values — verbatim customer financial data — and is the one path by which a
configuration-level setting could turn a production log into a data store.

**Why the schema is ordered by dependency and the audit table is last.** V1 must precede everything
because `organizations` is the parent of every other table; V3 must precede V4 because canonical
records point back at the files they were ingested from; V6 must precede V8 because an opportunity
cites the calculation that produced it; V9 must follow V8 because every value row hangs off an
opportunity. V10 comes last because it depends on nothing — and placing the audit trail after the
business schema it observes means the schema it records is complete and reviewable before the record
of it is created.

**Why money is `NUMERIC(20,4)` and rate-like quantities are `NUMERIC(20,6)`.** A payable amount is
held at four decimal places, which is the precision the business transacts at. A quantity or a unit
rate is an *input*, not an amount, and legitimately carries more precision than the amount computed
from it. Every money column is paired with an explicit currency, and V6 enforces by CHECK constraint
that a variance or impact amount can never exist without one — because an uninterpretable number is
worse than a missing one.

**Why tenant identity is denormalised onto every table.** A period-wide query must never have to
join through `organizations` to establish tenancy, because a missed join is a cross-tenant leak. The
cost is a duplicated column; the benefit is that isolation is a property of the rows rather than of
query discipline. The same reasoning puts `organization_id` first in nearly every composite index.

**Why every tenant-scoped unique constraint includes `organization_id`.** Registration numbers and
idempotency keys aside, the general rule holds throughout: `ux_memberships_user_org_role`,
`ux_source_files_org_checksum`, `ux_customers_org_source_key`, `ux_invoices_org_source_number`,
`ux_opportunities_org_reference`, `ux_investigations_org_reference`. Two tenants must be free to use
the same invoice number, the same SKU reference and the same opportunity reference. The two
exceptions are documented at their definitions: `ux_organizations_registration_number` is globally
unique because it is a statutory identifier, and `ux_idempotency_key` is globally unique because a
per-tenant key would hide a tenant-scoping bug rather than expose it.

**Why `version` columns are `BIGINT` and `NOT NULL DEFAULT 0`.** They are JPA `@Version` counters.
A `BIGINT` counter is used rather than a timestamp because it must change on every write, and two
writes landing in the same instant would not. `DEFAULT 0` means a row inserted outside JPA is still
lockable. Tables that omit it — `role_permissions`, `source_files`, `source_records`,
`ingestion_errors`, `permissions`, the `opportunity` review and event tables — omit it because they
are either pure edges replaced wholesale or append-only histories where an optimistic-lock counter
would protect a concurrency case that cannot arise.

**Why `term_version` and `version` coexist in V5.** They are different things and confusing them
would break an audit answer. `term_version` is a commercial fact that appears in evidence — "this
calculation used pricing term version 3". `version` is a concurrency mechanism that must never be
read by anyone. Both start at 1 for `term_version` because 1 reads correctly in an audit sentence.

#### Key comments added

The configuration and migration files were, apart from three pre-existing header lines, uncommented.
The comments now in them fall into six groups:

1. **File-level rationale** — what the file is for and, for the profile files, that each one is an
   *overlay* and never a standalone configuration. `application.properties` explains why it is nearly
   empty given that properties beat YAML on a collision.
2. **Per-group WHAT-and-WHY on configuration** — datasource (why the password default is empty, why
   the pool is 20/5 given virtual threads), JPA (why `open-in-view: false` and `validate`), Flyway
   (why `baseline-on-migrate` is safe only because Hibernate is in validate mode), servlet
   multipart (why the file and request limits differ), virtual threads, the three suppressed
   `server.error.*` keys, and the actuator allowlist. For each `cfo.*` group the comment states which
   `@ConfigurationProperties` record consumes it and why services do not read the key directly.
3. **Profile-override comments** — each profile's file says explicitly which base key it changes and
   why that change is right for that environment, including the two restatements
   (`ddl-auto: validate`, `show-details: never`) that are repeated on purpose so the production file
   is self-contained for review.
4. **Logback appender/logger comments** — the reasoning for the two appenders, the field-by-field
   justification of the shared pattern, why `%msg%n` is used instead of `%m%n`, the
   `SizeAndTimeBasedRollingPolicy` limits and why `totalSizeCap` is the operationally important one,
   and per-logger reasoning including why `org.hibernate.SQL` is pinned and why the prod console
   pattern drops the tenant id. The pre-existing "never log bodies/headers/cookies" comment is left
   exactly as it was, with the new logger comments placed after it.
5. **SQL file headers** — each migration states its purpose, its owning module, and its design
   decisions as a bullet list. V5's existing header is preserved verbatim and the new content is
   appended below it. V8's header notes that `investigations` belongs to the investigation module but
   is created here because it depends only on `opportunities`.
6. **Per-object SQL comments** — before every table, index, constraint and CHECK, a comment stating
   why that object exists and what invariant it enforces. The CHECK constraints get the most detail,
   because they are the rules that survive a bad deployment: `ck_invoice_lines_total` is documented
   with its two known limits (the 6-decimal product cannot always be represented in a 4-decimal
   column, and no non-negativity rule exists because credit notes are legitimately negative), and
   `ck_calc_results_variance_currency` is documented as the schema's central safety property. Index
   comments name the query each index serves, because an index without a named query is an index
   that will eventually be dropped or, worse, kept for nothing.

Every migration also now ends with a note on the two distinct versioning columns where both appear,
and the V4 `commercial_rules` note explains why rule codes are tenant-scoped rather than global.

#### Known mismatches between the schema and the Java model

These were found while reading the models against the migrations and are recorded here because they
are visible from this slice even though the fixes belong elsewhere:

- **`InvoiceLine` has no `version` component, but `invoice_lines.version` exists and
  `InvoiceLineResponse` declares `long version`.** `InvoiceMapper` handles this explicitly with
  `@Mapping(target = "version", ignore = true)` and a comment saying the line is "a version-less
  value record", so the field is not a bug — but the API contract advertises an optimistic-lock token
  it cannot honour, and it will serialise as `0` forever. Either the response should drop the field
  or the record should carry the column.
- **`ck_invoice_lines_total` cannot hold for six-decimal quantities.** `quantity NUMERIC(20,6)` ×
  `unit_price NUMERIC(20,6)` yields up to twelve decimal places while `line_total NUMERIC(20,4)` holds
  four, so the CHECK fails by up to half the smallest stored unit for any product that is not exact
  at four places. `InvoiceLine` documents this thoroughly — `policyLineTotal()` rounds the gross once
  before discount and tax, `exactCheckLineTotal()` evaluates the literal expression, and
  `schemaCheckVariance()` reports the residual — and states that "the persistence pass must therefore
  relax that constraint to a rounded comparison". No such migration exists yet. **As written, V4
  cannot store a line whose product needs more than four decimals.**
- **`spring.jpa.hibernate.ddl-auto: validate` validates only two of the ten migrations' tables.**
  Only `AuditEventEntity` and `IdempotencyRecord` carry JPA annotations; every other domain model is
  a plain record. The guarantee the key name implies is therefore much narrower than it appears.
- **`AuditEventEntity` redeclares indexes that already exist in V10, with a different definition.**
  Its `@Table` lists `ix_audit_events_org_time` on `(organization_id, occurred_at)` where V10 creates
  it on `(organization_id, occurred_at DESC)`, and omits `ix_audit_events_actor` entirely. Under
  `validate` this is not detected, so it is harmless today — but if `ddl-auto` were ever changed to
  `update`, Hibernate would try to reconcile indexes it now believes it owns.
- **No table exists for `reporting.Report`, `reporting.ReportArtifact` or any `processing` job
  execution row**, despite the Java types existing. Batch state is referenced from
  `ingestion_runs.job_instance_id` and `calculation_runs.job_instance_id` as a bare `BIGINT`, which
  implies an external Spring Batch schema this repository does not create.

---

## 13. Test suite, build & tooling


#### What this chapter is for

This chapter describes the test suite under `src/test/java`, the build that runs
it, and the documentation set that explains it. It is written for a contributor who
needs to know three things before touching a test or a build file: which behaviours
are already locked down and must never regress, which ones are not yet guarded and
are therefore easy to break, and which versions of the toolchain are pinned and
what happens if they are moved.

It is deliberately explicit about what the suite does **not** cover. This is a
pre-financing codebase assembled in vertical slices, and several test classes exist
only as documented placeholders. A placeholder that looks like a test is worse than
no test, because a build reports green. Every such file is called out below with the
invariant it was meant to lock down, so the gap is visible rather than implied.

Two environmental constraints apply to anyone reading this chapter. There is no
Docker daemon in the authoring environment, so no Testcontainers-backed test can be
executed or verified here. And there is a pre-existing MapStruct compilation break in
the `financial/mapper` package that predates this work; it is out of scope for the
docs-and-comments pass and is recorded rather than fixed.

#### File inventory

Every file in this slice, with what it is for.

##### Test root (`src/test/java/com/fintech/cfo`)

- **`CfoApplicationTests.java`** — the context-load smoke test: the Spring application context starts with the real configuration. It is the cheapest possible check that a change did not break wiring, and it is the first thing that fails when a bean definition is wrong, which is why it is worth its cost.
- **`TestCfoApplication.java`** — a `@SpringBootApplication` in the test tree used as the bootstrap for slice tests (`@WebMvcTest`, `@DataJpaTest`, and the Testcontainers-backed integration tests). It exists so a slice test can start a trimmed context instead of booting the whole application, and so the test context is not accidentally coupled to production scan configuration.
- **`TestcontainersConfiguration.java`** — the test-only wiring that declares the PostgreSQL container and hands it to Boot through `@ServiceConnection`. The credentials are never hardcoded; the connection is supplied by the container, so no test can accidentally depend on a developer's local database.

##### Financial truth (`financialtruth`) — the core of the suite

- **`MoneyTest.java`** — contract tests for `Money`, the type every financial result is built from. The invariant is that monetary arithmetic is exact and cannot be corrupted by floating-point rounding, currency mixing, or null handling.
- **`PricingVarianceRuleTest.java`** — pure unit tests for `PricingVarianceRule`. The invariant is that a price that differs from the contracted price produces a variance of exactly the expected amount and direction, with the correct sign on over- and under-payment.
- **`DiscountVarianceRuleTest.java`** — pure unit tests for `DiscountVarianceRule`. The invariant is that tiered and term-based discounts are applied in the right order and to the right base, so a discount can never be double-counted.
- **`FinancialTruthEngineTest.java`** — end-to-end tests for `FinancialTruthEngine` and its calculator collaborators. The invariant is that the assembled pipeline wires expected amount, actual amount, variance and impact together correctly, since that composition is what every downstream report reads.
- **`CalculationReproducibilityTest.java`** — proves the claim in the module's name: the same input snapshot produces the same output. The invariant is determinism. If a calculation can depend on wall-clock time, iteration order, or a random source, a financial number becomes unreproducible and therefore untrustworthy, and this test is what makes that regression loud.
- **`FinancialRegressionTest.java`** — the financial regression suite: the numbers this product is trusted to report. The invariant is that known inputs keep producing known outputs, character for character. It is the test that would catch a change in rounding policy, a currency conversion path, or a sign convention.
- **`TruthEngineFixtures.java`** — the fixed datasets shared by the financial truth tests. Every value is a literal, derived from no clock, no random source and no environment, because a regression suite whose expected values can move is not a regression suite.

##### Ingestion (`ingestion`)

- **`CsvFileParserTest.java`** — reader tests for the delimited-text path. The invariant is that quoted fields, embedded delimiters and embedded newlines survive the round trip, which is the classic source of silent data corruption in hand-rolled CSV parsers.
- **`ExcelFileParserTest.java`** — reader tests for the spreadsheet path. The invariant is that cell values, column ordering and the header row map onto the ingestion model the same way the CSV path does, so the two paths do not disagree.
- **`FileValidationServiceTest.java`** — the gates an upload must pass before its rows are trusted. The invariant is that an invalid file is rejected at the gate rather than partially ingested, and that a rejection names the specific violation.
- **`IngestionServiceTest.java`** — *(placeholder)* intended scope is the orchestration: parse, validate, normalise and persist as one transaction. The invariant it should protect is that a failed row cannot leave partial data behind.

##### Financial data (`financial`)

- **`FinancialDataServiceTest.java`** — *(placeholder)* intended scope is the persistence-facing service. The invariant: the same financial record is read and written identically, including its source reference.
- **`InvoiceNormalizationTest.java`** — *(placeholder)* intended scope is mapping raw ingested invoice lines onto the normalised model. The invariant: normalisation is total and deterministic, so a given raw line always normalises to the same target row.

##### Contract (`contract`)

- **`ContractServiceTest.java`** — pure unit tests for commercial-term resolution, with no Spring context. The invariant is that a contract's term hierarchy is resolved deterministically and that the winning term is unambiguous, because a contract is a promise and reading it two ways is a legal exposure.
- **`CommercialRuleServiceTest.java`** — *(placeholder)* intended scope is the rule engine over the resolved terms. The invariant: a rule fires only when every precondition is met, never on a partial match.

##### Evidence and lineage (`evidence`)

- **`EvidenceServiceTest.java`** — *(placeholder)* intended scope is capturing the evidence record behind a calculation. The invariant: every calculation can be traced to the exact input that produced it.
- **`LineageServiceTest.java`** — *(placeholder)* intended scope is the lineage graph, source record to downstream artifact. The invariant: lineage is complete and traversable, so no figure in a report is an orphan.

##### API (`api`)

- **`IngestionControllerTest.java`** — *(placeholder)* intended scope is the ingestion endpoints over MockMvc. The invariant: the HTTP contract, status codes and error shapes stay stable for clients.
- **`CalculationControllerTest.java`** — *(placeholder)* intended scope is the calculation endpoints. The invariant: a request produces a deterministic, reproducible response body.
- **`OpportunityControllerTest.java`** — *(placeholder)* intended scope is the opportunity endpoints. The invariant: the lifecycle state exposed over HTTP matches the state the domain actually holds.

##### Security (`security`)

- **`AuthenticationTest.java`** — *(placeholder)* intended scope is that an unauthenticated request is rejected at the filter chain. The invariant: no endpoint is reachable without a token.
- **`AuthorizationTest.java`** — *(placeholder)* intended scope is that an authenticated principal without the required authority is refused. The invariant: authentication is not treated as authorisation.
- **`TenantIsolationTest.java`** — *(placeholder)* intended scope is that one tenant's data is never returned to another. This is the single highest-consequence invariant in the platform: a failure here is a cross-customer data breach, not a bug.
- **`FileUploadSecurityTest.java`** — *(placeholder)* intended scope is the upload guard: type, size and archive-content checks before any parser touches the bytes. The invariant: an upload can never reach a parser as a disguised or oversized payload.

##### Architecture (`architecture`)

- **`ModuleBoundaryTest.java`** — *(placeholder)* intended scope is the module boundaries from `docs/architecture/module-implementation-rules.md` as executable ArchUnit rules. The invariant: a business module never imports another business module, and `shared` and `platform` never depend on business code.
- **`DependencyRuleTest.java`** — *(placeholder)* intended scope is the dependency-direction half of the same rule, split so a violation names the specific rule it broke. The invariant: layering is controller → service → repository, with domain and shared at the bottom depending on nothing.

##### Opportunity and value

- **`OpportunityDetectionTest.java`** — *(placeholder)* intended scope is turning a detected variance into an opportunity. The invariant: the quantified impact equals the variance that triggered it, never an estimate.
- **`OpportunityLifecycleTest.java`** — *(placeholder)* intended scope is the state machine. The invariant: illegal transitions are impossible, so an opportunity cannot skip validation or measurement.
- **`OpportunityValidationTest.java`** — *(placeholder)* intended scope is the validate/challenge/reject path. The invariant: a rejected opportunity records who rejected it and why.
- **`ActionServiceTest.java`** — *(placeholder)* intended scope is recommending and tracking actions. The invariant: an action cannot be marked acted without a measurable outcome attached.
- **`ValueAttributionTest.java`** — *(placeholder)* intended scope is attributing realized value back to the originating opportunity. The invariant: attribution is evidence-based, so realized value can never be claimed without a link to a real financial record.
- **`OutcomeServiceTest.java`** — *(placeholder, `value` package)* intended scope is recording whether an action actually produced the predicted outcome. The invariant: predicted and realized stay separate, and a prediction is never retroactively edited to match reality.

##### Build and tooling files

- **`pom.xml`** — the build definition. Carries the Boot 4.1.1 parent, the Java 25 language level, the annotation-processor ordering that makes MapStruct and Lombok cooperate, the `unmappedTargetPolicy=ERROR` compiler argument, and the pinned third-party versions. Every dependency and every plugin/compiler argument carries a comment stating why it is present.
- **`mvnw`** — the POSIX wrapper launcher. Pins the Maven version so the same commit builds the same way on every machine and in CI.
- **`mvnw.cmd`** — the Windows counterpart of `mvnw`, with the same purpose and the same pinned version.
- **`.mvn/wrapper/maven-wrapper.properties`** — where the pinned Maven distribution is declared. It is the single source of the build-tool version; neither launcher script chooses it.
- **`.gitattributes`** — line-ending normalisation. Its only real job is keeping `mvnw` LF, because a CRLF in that file invalidates the shebang and the wrapper stops working on Linux and macOS.
- **`.gitignore`** — excludes build output and the generated `HELP.md`, and guards against a `maven-wrapper.jar` reappearing when `distributionType=only-script` means none is needed.
- **`README.md`** — the entry point. Intentionally short: it is a map to `docs/`, not a copy of it.
- **`HELP.md`** — generated by the Spring Boot Maven plugin during the build and ignored by git. It is a link board, not durable documentation.
- **`docs/architecture/*.md`** — the binding engineering rules and the product scope. `module-implementation-rules.md` is the authoritative one.
- **`docs/decisions/*.md`** — the ADRs. Currently headings with guidance notes; the decision content itself is not yet written.

#### Test strategy by kind

**Unit tests (pure, no Spring).** `MoneyTest`, `PricingVarianceRuleTest`, `DiscountVarianceRuleTest`, `FinancialTruthEngineTest`, `ContractServiceTest`, `TruthEngineFixtures`. These cover the deterministic financial core. The invariant across all of them is that a given input produces exactly the expected output, with no clock, no random source and no framework in the way. They are the tests that can run anywhere, including in this environment, and they are the ones that carry the most weight.

**ArchUnit boundary and dependency tests.** `ModuleBoundaryTest` and `DependencyRuleTest`, both currently placeholders. The invariants they should protect are that a business module never imports another business module, that `shared` and `platform` stay free of business dependencies, and that layering runs controller → service → repository with domain at the bottom. These are placeholders today, so the boundary is a convention rather than a constraint.

**MockMvc API tests.** `IngestionControllerTest`, `CalculationControllerTest`, `OpportunityControllerTest` — all placeholders. The invariants are stable HTTP contracts, correct status codes, and stable error shapes, since a client-visible contract that changes silently is a breaking change that no other layer would catch.

**Testcontainers integration tests.** The `TestcontainersConfiguration` and `TestCfoApplication` wiring is real; the tests that would use it are among the placeholders. The invariant is that migrations apply and real queries work against a real PostgreSQL, so an entity that does not match the schema is caught here rather than in production. These require a Docker daemon, which this environment does not have, so they could not be executed or verified while writing this chapter.

**Security and tenant-isolation tests.** `AuthenticationTest`, `AuthorizationTest`, `TenantIsolationTest`, `FileUploadSecurityTest` — all placeholders. The invariants are: no endpoint reachable without a token; authentication never treated as authorisation; one tenant's data never visible to another; and an upload never reaches a parser as a disguised or oversized payload. Of everything in the suite, tenant isolation carries the highest consequence — a failure there is a cross-customer data breach.

**Financial regression and reproducibility.** `FinancialRegressionTest`, `CalculationReproducibilityTest`, supported by `TruthEngineFixtures`. The invariants are that known inputs keep producing known outputs, and that the same snapshot always produces the same answer. These two together are what make the platform's numbers defensible to an auditor: a figure that cannot be reproduced is a figure that cannot be relied upon.

**Parser tests.** `CsvFileParserTest`, `ExcelFileParserTest`, `FileValidationServiceTest`. The invariants are that quoting, embedded delimiters and embedded newlines round-trip correctly, that the two input formats agree on the normalised model, and that a file failing validation is rejected at the gate rather than partially ingested.

#### The journey, step by step

1. **Compile.** `mvnw clean verify` runs `maven-compiler-plugin` with the three annotation processors in a fixed order — Lombok, then `lombok-mapstruct-binding`, then `mapstruct-processor`. Compilation fails if a MapStruct mapper leaves a target property unmapped.
2. **ArchUnit boundary check.** The boundary rules run before or alongside the functional tests and fail the build on a cross-module import. This step is currently inert: both ArchUnit classes are placeholders, so nothing is enforced yet.
3. **Unit tests.** The pure financial and contract tests run next. They need no Spring context and no Docker, and they are where a deterministic regression is caught.
4. **API and security tests.** The MockMvc and security slices start a trimmed context and exercise the HTTP surface: authentication, authorisation, tenant isolation and endpoint contracts. All are placeholders today.
5. **Testcontainers integration tests.** Finally the container-backed tests start PostgreSQL, apply Flyway migrations, and exercise real queries. They need Docker and are skipped or fail in an environment without it.

#### How the build is put together

**Why ArchUnit is here.** The module boundaries in `docs/architecture/module-implementation-rules.md` exist to let modules be developed in parallel without quietly merging into one another. A boundary that is only written down stops being true quietly — it decays in review, one import at a time, and by the time anyone notices the modules have already merged. ArchUnit turns the rule into a build failure. The dependency is declared and the version pinned, but the rule bodies are not yet written, so the guarantee is currently documentation rather than code.

**Why the build fails on unmapped MapStruct properties.** MapStruct generates entity-to-DTO mappers at compile time. By default, a source field with no matching target property is silently dropped. On this platform that failure mode is unacceptable: a dropped amount field produces a report that looks complete and is quietly wrong, which is the exact outcome this system exists to prevent. Setting `unmappedTargetPolicy=ERROR` makes adding a field to an entity without deciding what the DTO should do with it a compile error, so the decision is forced at the point where it is cheap to make.

**Why the processor ordering matters.** Lombok generates the accessors; MapStruct needs to read them. Left to classpath discovery, javac runs annotation processors in an order that is not guaranteed, and MapStruct can run before Lombok has produced anything — in which case it sees no accessors, treats the properties as absent, and generates empty mappers. Two things prevent this: `lombok-mapstruct-binding`, which registers Lombok's annotations as a MapStruct binding so the processors are order-independent, and the explicit `annotationProcessorPaths` list that pins the order regardless. Reordering those three entries breaks DTO mapping.

**Toolchain pins and what they are protecting.**

- **Java 25** — the code is written against records, sealed hierarchies with exhaustive `switch`, and pattern-matching `switch`. The language level is set once in the POM. Lowering it means re-running the entire `financialtruth` suite before anything else.
- **Spring Boot 4.1.1** — the parent POM, and the single place the framework version is pinned.
- **springdoc-openapi 3.1.0** — pinned explicitly because the Boot parent does not manage it. Boot 4.x requires springdoc 3.x; the 2.x line is compiled against Boot 3.x and fails to load on a 4.x classpath. The pin is inline rather than inherited so the constraint is visible in one place.
- **MapStruct 1.6.3** — pinned because the Boot parent does not manage it either. The version appears twice, as the library and as the annotation processor, and both must move together.
- **Maven 3.9.16** — pinned in `.mvn/wrapper/maven-wrapper.properties` so that a different Maven cannot resolve a different plugin and produce a different build from the same commit.

**What the build does about Lombok at packaging time.** Lombok is `provided` scope, and the Spring Boot plugin explicitly excludes it. It is compile-time only, and shipping the annotation processor inside the application jar would add weight for no runtime benefit.

#### Key comments added in this pass

The comments in this slice exist to answer *why*, not *what* — the code already says what. The ones that matter most:

- **Every test class** carries Javadoc naming the behaviour it locks down and the consequence of that behaviour regressing. For the placeholder classes, the comment says what invariant the class is meant to protect and states plainly that it is not yet enforced, so the gap cannot be mistaken for coverage.
- **`TruthEngineFixtures`** explains that every value is a literal, and why: a regression suite whose expected values can move is not a regression suite.
- **ArchUnit test comments** explain why the boundary rules exist at all, and why they are split into two files — a violation should name the specific rule it broke rather than reporting "boundary broken".
- **`pom.xml`** carries a comment per dependency stating why it is present, and a comment per plugin and compiler argument. The three that carry real weight: the annotation-processor ordering, `unmappedTargetPolicy=ERROR`, and the springdoc 3.x pin with its Boot 4 rationale.
- **Wrapper and config files** carry purpose comments: what the wrapper pins, why `mvnw` must stay LF, why `HELP.md` is ignored, and why the absent `maven-wrapper.jar` is guarded against.
- **Documentation files** carry clarifying header comments recording what each document is authoritative *over* — so that `module-implementation-rules.md` wins over a README, an ADR, or a reading path, and so the vision document is not mistaken for a design document.

No test assertion, executable statement, import, signature, annotation, XML element or value was changed, deleted or reworded. This pass adds comments and one new documentation file, nothing else.

#### Documentation status

Every file under `docs/`, and whether its content is written or still a stub.

| Document | Status |
| --- | --- |
| `docs/architecture/module-implementation-rules.md` | Filled in — the authoritative rules document |
| `docs/architecture/all phases final goal` | Filled in — product vision, verbatim, single paragraph |
| `docs/architecture/flow of files` | Filled in — Phase 0 build order by vertical slice |
| `docs/architecture/phase-0-scope.md` | Filled in — scope committed to today |
| `docs/architecture/system-architecture.md` | Filled in — high-level structure |
| `docs/architecture/module-boundaries.md` | Filled in — the boundary rules, cross-referenced to the rules doc |
| `docs/architecture/financial-truth.md` | Filled in — the determinism guarantee |
| `docs/architecture/security-model.md` | Filled in — authentication, authorisation, tenancy |
| `docs/decisions/ADR-001-modular-monolith.md` | **TODO stub** — heading and guidance only, decision not written |
| `docs/decisions/ADR-002-financial-truth-over-ai.md` | **TODO stub** — heading and guidance only, decision not written |
| `docs/decisions/ADR-003-economic-opportunity-record.md` | **TODO stub** — heading and guidance only, decision not written |
| `docs/decisions/ADR-004-source-data-lineage.md` | **TODO stub** — heading and guidance only, decision not written |
| `docs/code-flow/README.md` | Filled in — the reading path |
| `docs/code-flow/STATUS.md` | Filled in — per-chunk status index |
| `docs/code-flow/00-how-to-read.md` | Filled in |
| `docs/code-flow/00-overview.md` | Filled in |
| `docs/code-flow/01-ingestion-financial.md` | Filled in |
| `docs/code-flow/02-truth-contract.md` | Filled in |
| `docs/code-flow/03-opportunity-value-evidence.md` | Filled in |
| `docs/code-flow/04-ai-reporting.md` | Filled in |
| `docs/code-flow/05-platform-shared-config.md` | Filled in |
| `docs/code-flow/06-appendices.md` | Filled in |
| `docs/code-flow/10-ingestion-deep.md` | Filled in |
| `docs/code-flow/11-truth-deep.md` | Filled in |
| `docs/code-flow/12-contract-kernel-deep.md` | Filled in |
| `docs/code-flow/13-stub-roadmap.md` | Filled in — the stub inventory this chapter summarises |
| `docs/code-flow/placeholders.md` | Filled in — placeholder register |
| `docs/code-flow/_TEMPLATE.md` | Filled in — authoring template, not content |
| `docs/explain/01-foundation-shared-platform.md` | Filled in |
| `docs/explain/02-identity-tenancy-security.md` | Filled in |
| `docs/explain/03-ingestion-file-pipeline.md` | Filled in |
| `docs/explain/04-financial-data.md` | Filled in |
| `docs/explain/05-financial-truth-engine.md` | Filled in |
| `docs/explain/06-contracts-and-terms.md` | Filled in |
| `docs/explain/07-evidence-and-lineage.md` | Filled in |
| `docs/explain/08-opportunity-lifecycle.md` | Filled in |
| `docs/explain/09-value-realization.md` | Filled in |
| `docs/explain/10-ai-layer.md` | Filled in |
| `docs/explain/11-investigation-reporting-processing.md` | Filled in |
| `docs/explain/12-runtime-config-and-schema.md` | Filled in |
| `docs/explain/13-tests-build-and-docs.md` | Filled in — this document |

The only documentation stubs remaining are the four ADRs, which carry headings and
guidance notes but not the decision content itself.


---


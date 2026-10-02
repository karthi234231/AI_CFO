# 20 — Stub roadmap: what is not built

The honest inventory. This chapter exists because the status documents written
earlier in this folder disagree with each other and at least two of them are
wrong.

⚠ Review: `README.md`, `STATUS.md`, `placeholders.md` and `13-stub-roadmap.md`
in this folder all report different counts. `README.md` claims 452 files,
280 REAL / 24 SPEC / 148 STUB, and per-module figures such as "financial 37" and
"ingestion 69". The verified numbers below contradict all of them. Treat those
four documents as stale and correct or delete them; do not reconcile against
them.

## Method

Counts were produced by enumerating `src/main/java/com/fintech/cfo` with
`Get-ChildItem` and classifying each `.java` file:

- **total** — every `.java` file in the module, including enums, DTOs and
  package-info.
- **stub** — a file containing a generated `TODO: Implement <Type>.` placeholder
  body. These carry a specification in Javadoc and an empty class. Nothing else
  counts as a stub.
- **built** — everything else. That includes enums, DTOs, records with real
  invariants, and fully implemented services. It does **not** mean the module
  works end to end: a module can be 100% built and still be unreachable, because
  its controller and repositories are in another module.

`CfoApplication.java` is counted separately: one file, one stub-free boot class.

## Status

| Module | Total | Built | Stub |
|---|---|---|---|
| shared | 24 | 24 | 0 |
| platform | 28 | 28 | 0 |
| ingestion | 79 | 75 | 4 |
| financial | 43 | 27 | 16 |
| contract | 55 | 50 | 5 |
| financialtruth | 50 | 46 | 4 |
| evidence | 26 | 11 | 15 |
| opportunity | 40 | 18 | 22 |
| ai | 37 | 30 | 7 |
| value | 24 | 1 | 23 |
| identity | 30 | 0 | 30 |
| investigation | 7 | 0 | 7 |
| reporting | 10 | 0 | 10 |
| processing | 12 | 0 | 12 |
| CfoApplication | 1 | 1 | 0 |

Totals: 478 files, 323 built, 155 stub.

Notes on the anomalies, because each one is a trap for someone estimating work:

- **`platform` has one `UnsupportedOperationException`** and it is not a stub.
  It is `ObjectStoragePort.localPath`, a default method that a remote store
  cannot honour (`platform/storage/ObjectStoragePort.java:88`).
- **`contract` and `ai` are mostly built, and it does not help them.** A
  complete rule evaluator with no controller, no repository and no way to load a
  contract cannot answer a question.
- **`value` is 1/24 built.** The single built file is a specification-grade
  Javadoc block in `RealizedValue.java` describing column decisions; the class
  body is the placeholder. "Built" here means the file has no `TODO`.
- **`identity` is 0/30 built**, so nothing in the product currently authenticates
  anyone or binds a tenant. This is the single largest blocker and it is not
  obvious from the count alone, because the shared kernel's `SecurityPrincipal`
  and `SecurityContext` *are* built.
- **`evidence` at 11/26** is almost entirely enums and DTO shapes. The lineage
  graph that the whole audit story depends on does not exist.

---

## Stub inventory

Every stub file, grouped by module. "Test" is the test that would prove it — the
existing suite where one exists, otherwise the test that must be written first.
Per-module invariants are stated once above the table rather than repeated in
each row, because they are the same for every file in the module.

### ingestion — 4 stubs

Invariants: tenant from the verified principal only; a row is either fully
validated or fully recorded as `RejectedRow` with its coordinate and reason;
nothing is silently dropped.

| File | Becomes | Test |
|---|---|---|
| `controller/IngestionController` | upload, status, error endpoints | `api/IngestionControllerTest` |
| `repository/IngestionRepository` | run lookup by id and tenant | `ingestion/IngestionServiceTest` |
| `repository/SourceFileRepository` | file lookup by checksum | new |
| `repository/IngestionErrorRepository` | row-level error listing | new |

The three repositories are the reason the 75 built files in this module are
currently unreachable: parsing, validation, upload security and the orchestrator
are all real, and none of them has anywhere to write.

### financial — 16 stubs

Invariants: every normalizer output is a valid domain object or a recorded
validation failure; the tenant is never read from the request; a re-import
replaces by position, never appends.

| File | Becomes | Test |
|---|---|---|
| `normalization/InvoiceNormalizer` | source row → `Invoice` + lines | `financial/InvoiceNormalizationTest` |
| `normalization/CustomerNormalizer` | source row → `Customer` | `financial/FinancialDataServiceTest` |
| `normalization/ProductNormalizer` | source row → `Product` | new |
| `normalization/FinancialDataNormalizer` | shared text/date/number cleanup | new |
| `service/InvoiceService` | invoice CRUD, import replace | `financial/FinancialDataServiceTest` |
| `service/CustomerService` | customer CRUD | `financial/FinancialDataServiceTest` |
| `service/ProductService` | product CRUD | new |
| `service/FinancialDataService` | transactions, periods | `financial/FinancialDataServiceTest` |
| `controller/InvoiceController` | invoice endpoints | new |
| `controller/CustomerController` | customer endpoints | new |
| `controller/ProductController` | product endpoints | new |
| `repository/InvoiceRepository` | invoice lookup by tenant + number | new |
| `repository/InvoiceLineRepository` | line replacement by position | new |
| `repository/CustomerRepository` | customer lookup | new |
| `repository/ProductRepository` | product lookup | new |
| `repository/FinancialTransactionRepository` | transaction period queries | new |

⚠ Review: `ck_invoice_lines_total` in V4 compares `line_total` against
`(quantity * unit_price) - discount + tax` at full stored scale, but the Java
`InvoiceLine` rounds gross to the money scale once, before discount and tax. The
CHECK is therefore unsatisfiable for any row where the rounding is non-zero. The
migration comments acknowledge this and `InvoiceLine` exposes
`schemaCheckVariance()` to report the residual — but the column definition still
has to be changed, or every import of such a row fails. Decide before
implementing the normalizer, not after.

### contract — 5 stubs

Invariants: as-of resolution goes through `TermSelector` and never through a
raw query; the chosen row is recorded with the calculation; the whole candidate
set is hashed into the input checksum.

| File | Becomes | Test |
|---|---|---|
| `service/ContractService` | contract CRUD | `contract/ContractServiceTest` |
| `service/ContractTermService` | term CRUD with version bump | new |
| `service/CommercialRuleService` | rule CRUD, expression validation | `contract/CommercialRuleServiceTest` |
| `repository/ContractRepository` | contract lookup by tenant | new |
| `repository/ContractTermRepository` | candidate set for an as-of query | new |
| `repository/CommercialRuleRepository` | rules for a term | new |

### financialtruth — 4 stubs

Invariants: the engine never reads the system clock or a repository; a run id is
supplied; `requireReproducible` must pass before a run is closed.

| File | Becomes | Test |
|---|---|---|
| `service/CalculationService` | orchestrates input load → engine → persist | `financialtruth/FinancialTruthEngineTest` |
| `service/CalculationRunService` | run lifecycle, reproducibility check | `financialtruth/CalculationReproducibilityTest` |
| `repository/CalculationRunRepository` | run persistence | new |
| `repository/CalculationResultRepository` | result persistence | new |

The calculation core is the most complete part of the product and it is
unreachable: nothing loads a `CalculationInput`, and nothing stores what the
engine produces.

### evidence — 15 stubs

Invariants: a node or edge is never created without an organization; evidence
content is addressed by hash, never rewritten in place; lineage edges form a DAG
and a cycle is refused.

| File | Becomes | Test |
|---|---|---|
| `service/LineageService` | DAG build and traversal | `evidence/LineageServiceTest` |
| `service/EvidenceService` | evidence registration | `evidence/EvidenceServiceTest` |
| `service/EvidenceSnapshotService` | point-in-time snapshots | new |
| `model/LineageNode` | node record | `evidence/LineageServiceTest` |
| `model/LineageEdge` | edge record | `evidence/LineageServiceTest` |
| `model/EvidenceSnapshot` | hash-addressed snapshot | new |
| `model/EvidenceReference` | typed pointer to a snapshot | new |
| `repository/LineageRepository` | edges for a root | new |
| `repository/EvidenceRepository` | evidence lookup | new |
| `repository/EvidenceReferenceRepository` | reference lookup | new |
| `dto/LineageResponse` | API shape | new |
| `dto/EvidenceResponse` | API shape | new |
| `dto/EvidenceDetailResponse` | API shape | new |
| `controller/LineageController` | `GET /lineage/{root}` | new |
| `controller/EvidenceController` | evidence endpoints | new |

Both test classes named here exist and are themselves stubs, so "existing test"
above means the class is registered and intended, not that it currently asserts
anything.

### opportunity — 22 stubs

Invariants: an opportunity's money must trace back to a calculation result; a
lifecycle transition is recorded as an event, never an in-place status write; no
contribution may be counted twice.

| File | Becomes | Test |
|---|---|---|
| `model/EconomicOpportunity` | the opportunity aggregate | `opportunity/OpportunityLifecycleTest` |
| `model/OpportunityImpact` | per-transaction signed share | `opportunity/OpportunityDetectionTest` |
| `model/OpportunityFinding` | a rule finding attached | `opportunity/OpportunityDetectionTest` |
| `model/OpportunityReview` | validate / challenge / reject record | `opportunity/OpportunityValidationTest` |
| `model/OpportunityAssignment` | owner assignment | `opportunity/OpportunityLifecycleTest` |
| `model/OpportunityLifecycleEvent` | append-only transition log | `opportunity/OpportunityLifecycleTest` |
| `service/OpportunityDetectionService` | variance → opportunity | `opportunity/OpportunityDetectionTest` |
| `service/OpportunityValidationService` | review decisions | `opportunity/OpportunityValidationTest` |
| `service/OpportunityLifecycleService` | state machine | `opportunity/OpportunityLifecycleTest` |
| `service/OpportunityService` | query facade | new |
| `repository/OpportunityRepository` | tenant-scoped lookup | new |
| `repository/OpportunityLifecycleRepository` | event stream | new |
| `repository/OpportunityReviewRepository` | review history | new |
| `controller/OpportunityController` | CRUD + list | `api/OpportunityControllerTest` |
| `controller/OpportunityReviewController` | review endpoints | new |
| `controller/OpportunityAssignmentController` | assign endpoints | new |
| `dto/OpportunityResponse` | API shape | new |
| `dto/OpportunityDetailResponse` | API shape | new |
| `dto/OpportunitySummaryResponse` | API shape | new |
| `dto/ValidateOpportunityRequest` | API shape | new |
| `dto/ChallengeOpportunityRequest` | API shape | new |
| `dto/RejectOpportunityRequest` | API shape | new |
| `dto/AssignOpportunityRequest` | API shape | new |

### ai — 7 stubs

Invariants: the model never returns a figure that reaches a total; every
inbound and outbound call passes the guardrail; a refusal or a numeric mismatch
marks the run rejected rather than publishing partial text.

| File | Becomes | Test |
|---|---|---|
| `client/LlmClient` | model call, no figures out | new |
| `client/EmbeddingClient` | vector call | new |
| `client/DocumentExtractionClient` | extraction call | new |
| `pdf/AiPdfService` | render or send a PDF | new |
| `pdf/PdfExtractionService` | text out of a PDF | new |
| `controller/ExplanationController` | explanation endpoint | new |
| `controller/ContractInterpretationController` | interpretation endpoint | new |

`ai.service.AiExplanationService`, `AiGuardrailService`, `AiContextService` and
`ContractInterpretationService` **are built**. The guardrail, including refusal
detection and numeric cross-checking against computed figures, exists today; what
is missing is the client it guards and the endpoints that reach it.

### value — 23 stubs

Invariants: realized value is the only row summable into a recovery total; a
reversal is a new row, never a decrement; attributed amounts reconcile to the
outcome they settle, within one currency.

| File | Becomes | Test |
|---|---|---|
| `model/RealizedValue` | the payout row | `value/OutcomeServiceTest` |
| `model/Outcome` | measured result | `value/OutcomeServiceTest` |
| `model/ActionPlan` | next steps | `value/ActionServiceTest` |
| `model/ActionExecution` | execution evidence | `value/ActionServiceTest` |
| `model/ValueAttribution` | share of a measure per action | `value/ValueAttributionTest` |
| `enums/RealizationStatus` | status set | new |
| `enums/ActionStatus` | status set | new |
| `enums/OutcomeStatus` | status set | new |
| `enums/AttributionMethod` | method set | new |
| `service/RealizedValueService` | book and reverse | new |
| `service/OutcomeService` | record outcomes | `value/OutcomeServiceTest` |
| `service/ActionService` | plan and execute | `value/ActionServiceTest` |
| `service/ValueAttributionService` | reconcile attributions | `value/ValueAttributionTest` |
| `repository/RealizedValueRepository` | totals per opportunity | new |
| `repository/OutcomeRepository` | outcome lookup | new |
| `repository/ActionRepository` | action lookup | new |
| `controller/ActionController` | action endpoints | new |
| `controller/OutcomeController` | outcome endpoints | new |
| `dto/RealizedValueResponse` | API shape | new |
| `dto/OutcomeResponse` | API shape | new |
| `dto/ActionResponse` | API shape | new |
| `dto/RecordOutcomeRequest` | API shape | new |
| `dto/CreateActionRequest` | API shape | new |

### identity — 30 stubs (the whole module)

Invariants: the organization is never taken from a request body, query or path;
an unverified or anonymous caller raises rather than returning a partial
identity; every repository query is tenant-scoped.

| File | Becomes | Test |
|---|---|---|
| `security/CurrentUser` | principal injection point | `security/AuthenticationTest` |
| `security/CurrentUserProvider` | fail-fast principal accessor | `security/AuthenticationTest` |
| `security/TenantContext` | ScopedValue-backed tenant holder | `security/TenantIsolationTest` |
| `security/TenantContextFilter` | binds tenant around the chain | `security/TenantIsolationTest` |
| `security/JwtAuthenticationConverter` | claims → `SecurityPrincipal` | `security/AuthenticationTest` |
| `security/SecurityConfig` | filter chain, endpoint rules | `security/AuthorizationTest` |
| `security/SecurityHeadersConfig` | response headers | new |
| `service/TenantAccessService` | membership check | `identity/TenantAccessServiceTest` |
| `service/AuthorizationService` | role/permission checks | `security/AuthorizationTest` |
| `service/UserService` | user CRUD | `identity/IdentityServiceTest` |
| `service/OrganizationService` | tenant provisioning | `identity/IdentityServiceTest` |
| `model/User` / `model/Organization` | aggregates | `identity/IdentityServiceTest` |
| `model/Role` / `model/Permission` | authorization model | `security/AuthorizationTest` |
| `model/Membership` | user↔organization link | `identity/TenantAccessServiceTest` |
| `enums/UserStatus` / `RoleType` / `PermissionType` | closed sets | new |
| `repository/UserRepository` | lookup | new |
| `repository/OrganizationRepository` | lookup | new |
| `repository/RoleRepository` | lookup | new |
| `repository/MembershipRepository` | membership lookup | new |
| `controller/UserController` | user endpoints | new |
| `controller/OrganizationController` | tenant endpoints | new |
| `controller/RoleController` | role endpoints | new |
| `dto/UserResponse` / `RoleResponse` / `PermissionResponse` / `OrganizationResponse` | API shapes | new |

(Rows grouping several files are grouped for length only; the count of 30
individual files is the authoritative number.)

### investigation — 7 stubs

Invariants: an investigation cites the opportunity and the evidence that
triggered it; its status transitions are recorded.

| File | Becomes | Test |
|---|---|---|
| `model/Investigation` | the investigation record | new |
| `enums/InvestigationStatus` | status set | new |
| `service/InvestigationService` | open, advance, close | new |
| `repository/InvestigationRepository` | lookup | new |
| `controller/InvestigationController` | endpoints | new |
| `dto/CreateInvestigationRequest` | API shape | new |
| `dto/InvestigationResponse` | API shape | new |

### reporting — 10 stubs

Invariants: a report renders figures that already exist; it computes none; it
reads its own per-currency totals rather than summing across currencies.

| File | Becomes | Test |
|---|---|---|
| `model/Report` / `ReportArtifact` | report and its stored output | new |
| `enums/ReportType` | report kinds | new |
| `service/ReportService` | report lifecycle | new |
| `service/OpportunityReportService` | the opportunity report | new |
| `pdf/PdfReportGenerator` | render PDF | new |
| `pdf/PdfTemplateService` | template resolution | new |
| `repository/ReportRepository` | lookup | new |
| `controller/ReportController` | endpoints | new |
| `dto/GenerateOpportunityReportRequest` / `ReportResponse` | API shapes | new |

### processing — 12 stubs

Invariants: a job is idempotent on re-run after a crash; a failure is recorded
with its cause; a job never computes a number, only schedules the module that
does.

| File | Becomes | Test |
|---|---|---|
| `common/JobExecutionService` | run tracking | new |
| `common/JobFailureHandler` | failure policy | new |
| `financialtruth/CalculationJobConfiguration` | job definition | new |
| `financialtruth/CalculationJobLauncher` | trigger | new |
| `financialtruth/CalculationProcessor` | per-invoice work | new |
| `financialtruth/CalculationWriter` | persist without double-count | new |
| `ingestion/IngestionJobConfiguration` | job definition | new |
| `ingestion/IngestionJobLauncher` | trigger | new |
| `ingestion/IngestionProcessor` | per-file work | new |
| `ingestion/IngestionWriter` | persist results | new |
| `reporting/ReportJobConfiguration` | job definition | new |
| `reporting/ReportJobLauncher` | trigger | new |

---

## Design decisions required before implementation

These are not implementation details. Each one, chosen the wrong way, produces
code that has to be rewritten across several modules, and several of them
currently have two plausible answers already half-built.

### D1 — Which security model is canonical: `identity.security` or `shared.security`?

`shared.security.SecurityContext` / `SecurityPrincipal` are **built**.
`identity.security.CurrentUser`, `CurrentUserProvider`, `TenantContext`,
`TenantContextFilter` are **stubs**, and their Javadoc already states the
intended relationships. But `shared` cannot depend on `identity` (it is beneath
it), and `identity` must not be depended on by `shared`. So the duplication is
structural, not accidental.

⚠ Review: the Javadocs disagree about direction. `SecurityContext`'s comment
says "the identity module's `TenantContextFilter` bridges from this principal
into a ScopedValue-bound tenant scope", while `identity.security.TenantContext`
says it "is the tenant analogue of `SecurityContext`". Both cannot be the
architect. **Decide:** recommended — `shared` keeps `SecurityPrincipal` and
`SecurityContext` as the sole identity vocabulary; `identity` keeps only the
JWT converter, the filter chain and the tenant scope, and `CurrentUser` /
`CurrentUserProvider` are deleted rather than implemented as second sources.
Deleting them is the cheaper mistake than maintaining two.

### D2 — How is tenant context bound, given `ScopedValue`?

`ScopedValue` is immutable and lexically scoped, which is exactly what a
`Filter` cannot conveniently do: a servlet filter wraps the rest of the chain,
which is dynamic extent, and `ScopedValue.where(...).run(...)` needs the
continuation to be passed in. The honest options are (a) bind in the filter and
have downstream code read it, accepting a `ThreadLocal`-shaped helper anyway;
(b) move tenant resolution to a `@ControllerAdvice`/interceptor where the
invocation can be wrapped; (c) drop `ScopedValue` and use a properly
cleared-in-`finally` `ThreadLocal`. ⚠ Review: nothing in the code proves a
`ScopedValue` binding is achievable from a servlet filter — the `Filter` stub
assumes it. Prototype one binding and one read before building the other 29
identity files on top of it.

### D3 — Where does cross-module exchange happen?

The rule is that a business module may import `shared` and `platform` and never
another business module, and the rule is currently unenforced
(`architecture/ModuleBoundaryTest.java:21`). The real question: how does
`financialtruth` obtain a resolved `PricingTerm` when `contract` owns resolution
and neither may import the other? Options: (a) `contract` publishes a SPI in
`shared` that `financialtruth` calls, with `contract` as the provider; (b) a
composition-root service in `platform` that assembles both — but `platform` must
stay free of business code, so this is really a new top-level module;
(c) a written snapshot that `contract` projects into `financial`'s tables, which
is what the stubbed `financialtruth.model.PricingTerm` hints at. **Decide before
implementing `ContractTermRepository`**, because the repository's shape is
determined by the answer.

### D4 — Two parallel term models: unify, or keep them apart?

`contract` has `PricingTerm`, `DiscountTerm`, `PricingType`, `DiscountType` on
top of `ScopedTerm` / `VersionedTerm` / `EffectiveWindow` and a real resolver.
`financialtruth` has its **own** `PricingTerm`, `DiscountTerm`, `PricingType`,
`DiscountType` records, designed to be self-sufficient so the engine never
imports `contract`. That is a defensible hexagonal boundary and it is already
built on both sides. ⚠ Review: keeping both means two expressions of
"in force on this date" and two places a rounding rule can drift. Unifying
couples the engine to `contract` and undoes the boundary. **Decide:** keep both,
and make the projection from one to the other an explicit, checksum-covered
translation — a third option that gets neither benefit and both costs.

### D5 — `OpportunityImpact.isFavourable()` polarity

`OpportunityImpact.java:103` returns `amount.isNegative()`, justified as
"variance is `actual - expected`, so a positive amount is recoverable — this
predicate therefore selects the negative direction". That means "favourable to
the customer" currently means *negative*, i.e. an undercharge, and the name
reads the other way. The Javadoc itself flags this as the one place nothing else
in the module fixes the sign. This single predicate decides whether a variance is
claimed as recoverable money or as an amount owed. **Decide before
`OpportunityDetectionService` is written**, because every contribution row the
detector emits inherits whichever answer is chosen, and rewriting them later
means rewriting historical opportunity data.

### D6 — Sign convention of `netFromComponents`

`VarianceCalculator.netFromComponents` adds pricing variances and **subtracts**
discount variances (`:127-132`). The engine's own inline fold agrees
(`FinancialTruthEngine.java:245-252`). The convention is coherent, but it is
stated in three places and only the class Javadoc explains why, so a future
caller summing components uniformly will produce a silently wrong total. **Decide:**
keep the sign flip and make it a property of the type — e.g. discount variances
carry an inverted contribution — or keep it at the call sites and add a test
that fails on any total not produced by this method. ⚠ Review: the reconciliation
assertion in `assertReconciled` covers the engine's path, not a future caller's.

### D7 — The unsatisfiable `ck_invoice_lines_total`

V4 declares `CHECK (line_total = (quantity * unit_price) - discount_amount +
tax_amount)`, but the Java side rounds gross to the money scale once, before
discount and tax — so the stored components cannot satisfy the expression in
general. Options: round nothing and store full scale (loses the
`NUMERIC(20,4)` alignment), change the CHECK to a tolerance band, or drop it and
let `InvoiceLine.schemaCheckVariance()` report the residual in application code.
**Decide before `InvoiceNormalizer` is written**, because whichever way it goes
changes what a normalized line is.

### D8 — Do we keep `contract.model.CanonicalForm`?

It is a near-exact duplicate of `CanonicalText`, referenced by nothing, and its
Javadoc says it was retained only because deletion was out of scope
(`CanonicalForm.java:24`). It is a trap: a future term author could implement
`canonical()` against the wrong class and silently produce a different digest.
Delete it or make it delegate; do not leave two.

---

## Ordering

The rule: **a milestone is real only when its module has no STUB in its own
path.** A module that is 100% built and whose caller is a stub delivers nothing
— that is exactly the state of `financialtruth` and `contract` today.

1. **Unblock the security boundary (identity, 30 files).** Everything else reads
   a principal. Nothing can be integration-tested without it. Start with D1, D2
   and the `JwtAuthenticationConverter`; `CurrentUser`/`CurrentUserProvider`
   are decided by D1, not implemented independently.
2. **Persistence seams (all repository stubs, 15 files).** `financial`'s five,
   `contract`'s three, `ingestion`'s three, `financialtruth`'s two, plus
   `opportunity`, `evidence`, `value`, `reporting`. Repositories have no
   business logic, so they are the cheapest stubs to close and they unblock
   every service above them. Depends on D3 for the cross-module read path.
3. **`financial` services and normalizers (11 files).** Close D7 first. This is
   the first point at which an uploaded file has anywhere to become an invoice.
4. **`financialtruth` services (2 files).** The engine is already proven by
   `FinancialTruthEngineTest`; this wires input loading and result persistence
   so a real invoice can be calculated. Depends on step 3 and on D4.
5. **`contract` services (3 files).** Depends on D3. Only worth doing after
   step 4, because until terms can be resolved for a real invoice the resolver
   has no caller.
6. **`opportunity` (22) and `evidence` (15).** Depends on step 4, and on D5 for
   the contribution sign.
7. **`value` (23) and `reporting` (10).** Depends on 6.
8. **`ai` (7), `investigation` (7), `processing` (12).** `ai` is optional by
   design; `processing` is only needed once there is something to schedule.

Write `ModuleBoundaryTest` at the end of step 2, once repositories exist to
violate the rule. Enforcing it before there is any cross-module code to catch
produces a green test that proves nothing.

## The integration milestone

Four seams must close together, or nothing runs. They are listed as one
milestone because each is cheap alone and none of them is meaningful without the
other three.

**1. Storage adapter.** `ObjectStoragePort` is an interface with no
implementation and one method that must refuse on a remote store
(`ObjectStoragePort.java:88`). Uploads, source files, evidence snapshots and PDF
artifacts all hang off it. Needed: a local-filesystem implementation for
development and an S3-compatible one, with tenant prefixing applied by the
caller as the port's Javadoc requires.

**2. Security filter chain.** `SecurityConfig` and `TenantContextFilter` are
both stubs, so no request is authenticated and no tenant is bound. Needed: the
chain in the order D2 requires, the JWT converter producing a
`SecurityPrincipal`, and `TenantContextFilter` binding for the rest of the
request. Until this lands, every controller below it is reachable without a
tenant.

**3. Mappers wired into controllers.** The MapStruct mappers are generated with
`uses = FinancialMappingSupport` so the shared normalisation rules apply; nothing
currently generates or calls them. Needed: the `financial`, `contract` and
`financialtruth` mappers built, and their controllers constructed with them
rather than hand-rolled conversion. This is where a tenant is dropped or
double-applied if it is done ad hoc, which is why it is called out separately.

**4. Cross-module composition root.** Per D3, something has to assemble
`contract` resolution with `financialtruth` calculation without either importing
the other. Needed: an explicit wiring point — an SPI provider in `shared`, or a
thin top-level assembler — plus the `CalculationService` that loads a
`CalculationInput` and persists a `CalculationRun`. Without this, the four
verified seams produce a system that boots and does nothing.

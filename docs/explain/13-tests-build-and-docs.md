# 13. Test suite, build & tooling

## 13. Test suite, build & tooling

### What this chapter is for

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

### File inventory

Every file in this slice, with what it is for.

#### Test root (`src/test/java/com/fintech/cfo`)

- **`CfoApplicationTests.java`** — the context-load smoke test: the Spring application context starts with the real configuration. It is the cheapest possible check that a change did not break wiring, and it is the first thing that fails when a bean definition is wrong, which is why it is worth its cost.
- **`TestCfoApplication.java`** — a `@SpringBootApplication` in the test tree used as the bootstrap for slice tests (`@WebMvcTest`, `@DataJpaTest`, and the Testcontainers-backed integration tests). It exists so a slice test can start a trimmed context instead of booting the whole application, and so the test context is not accidentally coupled to production scan configuration.
- **`TestcontainersConfiguration.java`** — the test-only wiring that declares the PostgreSQL container and hands it to Boot through `@ServiceConnection`. The credentials are never hardcoded; the connection is supplied by the container, so no test can accidentally depend on a developer's local database.

#### Financial truth (`financialtruth`) — the core of the suite

- **`MoneyTest.java`** — contract tests for `Money`, the type every financial result is built from. The invariant is that monetary arithmetic is exact and cannot be corrupted by floating-point rounding, currency mixing, or null handling.
- **`PricingVarianceRuleTest.java`** — pure unit tests for `PricingVarianceRule`. The invariant is that a price that differs from the contracted price produces a variance of exactly the expected amount and direction, with the correct sign on over- and under-payment.
- **`DiscountVarianceRuleTest.java`** — pure unit tests for `DiscountVarianceRule`. The invariant is that tiered and term-based discounts are applied in the right order and to the right base, so a discount can never be double-counted.
- **`FinancialTruthEngineTest.java`** — end-to-end tests for `FinancialTruthEngine` and its calculator collaborators. The invariant is that the assembled pipeline wires expected amount, actual amount, variance and impact together correctly, since that composition is what every downstream report reads.
- **`CalculationReproducibilityTest.java`** — proves the claim in the module's name: the same input snapshot produces the same output. The invariant is determinism. If a calculation can depend on wall-clock time, iteration order, or a random source, a financial number becomes unreproducible and therefore untrustworthy, and this test is what makes that regression loud.
- **`FinancialRegressionTest.java`** — the financial regression suite: the numbers this product is trusted to report. The invariant is that known inputs keep producing known outputs, character for character. It is the test that would catch a change in rounding policy, a currency conversion path, or a sign convention.
- **`TruthEngineFixtures.java`** — the fixed datasets shared by the financial truth tests. Every value is a literal, derived from no clock, no random source and no environment, because a regression suite whose expected values can move is not a regression suite.

#### Ingestion (`ingestion`)

- **`CsvFileParserTest.java`** — reader tests for the delimited-text path. The invariant is that quoted fields, embedded delimiters and embedded newlines survive the round trip, which is the classic source of silent data corruption in hand-rolled CSV parsers.
- **`ExcelFileParserTest.java`** — reader tests for the spreadsheet path. The invariant is that cell values, column ordering and the header row map onto the ingestion model the same way the CSV path does, so the two paths do not disagree.
- **`FileValidationServiceTest.java`** — the gates an upload must pass before its rows are trusted. The invariant is that an invalid file is rejected at the gate rather than partially ingested, and that a rejection names the specific violation.
- **`IngestionServiceTest.java`** — *(placeholder)* intended scope is the orchestration: parse, validate, normalise and persist as one transaction. The invariant it should protect is that a failed row cannot leave partial data behind.

#### Financial data (`financial`)

- **`FinancialDataServiceTest.java`** — *(placeholder)* intended scope is the persistence-facing service. The invariant: the same financial record is read and written identically, including its source reference.
- **`InvoiceNormalizationTest.java`** — *(placeholder)* intended scope is mapping raw ingested invoice lines onto the normalised model. The invariant: normalisation is total and deterministic, so a given raw line always normalises to the same target row.

#### Contract (`contract`)

- **`ContractServiceTest.java`** — pure unit tests for commercial-term resolution, with no Spring context. The invariant is that a contract's term hierarchy is resolved deterministically and that the winning term is unambiguous, because a contract is a promise and reading it two ways is a legal exposure.
- **`CommercialRuleServiceTest.java`** — *(placeholder)* intended scope is the rule engine over the resolved terms. The invariant: a rule fires only when every precondition is met, never on a partial match.

#### Evidence and lineage (`evidence`)

- **`EvidenceServiceTest.java`** — *(placeholder)* intended scope is capturing the evidence record behind a calculation. The invariant: every calculation can be traced to the exact input that produced it.
- **`LineageServiceTest.java`** — *(placeholder)* intended scope is the lineage graph, source record to downstream artifact. The invariant: lineage is complete and traversable, so no figure in a report is an orphan.

#### API (`api`)

- **`IngestionControllerTest.java`** — *(placeholder)* intended scope is the ingestion endpoints over MockMvc. The invariant: the HTTP contract, status codes and error shapes stay stable for clients.
- **`CalculationControllerTest.java`** — *(placeholder)* intended scope is the calculation endpoints. The invariant: a request produces a deterministic, reproducible response body.
- **`OpportunityControllerTest.java`** — *(placeholder)* intended scope is the opportunity endpoints. The invariant: the lifecycle state exposed over HTTP matches the state the domain actually holds.

#### Security (`security`)

- **`AuthenticationTest.java`** — *(placeholder)* intended scope is that an unauthenticated request is rejected at the filter chain. The invariant: no endpoint is reachable without a token.
- **`AuthorizationTest.java`** — *(placeholder)* intended scope is that an authenticated principal without the required authority is refused. The invariant: authentication is not treated as authorisation.
- **`TenantIsolationTest.java`** — *(placeholder)* intended scope is that one tenant's data is never returned to another. This is the single highest-consequence invariant in the platform: a failure here is a cross-customer data breach, not a bug.
- **`FileUploadSecurityTest.java`** — *(placeholder)* intended scope is the upload guard: type, size and archive-content checks before any parser touches the bytes. The invariant: an upload can never reach a parser as a disguised or oversized payload.

#### Architecture (`architecture`)

- **`ModuleBoundaryTest.java`** — *(placeholder)* intended scope is the module boundaries from `docs/architecture/module-implementation-rules.md` as executable ArchUnit rules. The invariant: a business module never imports another business module, and `shared` and `platform` never depend on business code.
- **`DependencyRuleTest.java`** — *(placeholder)* intended scope is the dependency-direction half of the same rule, split so a violation names the specific rule it broke. The invariant: layering is controller → service → repository, with domain and shared at the bottom depending on nothing.

#### Opportunity and value

- **`OpportunityDetectionTest.java`** — *(placeholder)* intended scope is turning a detected variance into an opportunity. The invariant: the quantified impact equals the variance that triggered it, never an estimate.
- **`OpportunityLifecycleTest.java`** — *(placeholder)* intended scope is the state machine. The invariant: illegal transitions are impossible, so an opportunity cannot skip validation or measurement.
- **`OpportunityValidationTest.java`** — *(placeholder)* intended scope is the validate/challenge/reject path. The invariant: a rejected opportunity records who rejected it and why.
- **`ActionServiceTest.java`** — *(placeholder)* intended scope is recommending and tracking actions. The invariant: an action cannot be marked acted without a measurable outcome attached.
- **`ValueAttributionTest.java`** — *(placeholder)* intended scope is attributing realized value back to the originating opportunity. The invariant: attribution is evidence-based, so realized value can never be claimed without a link to a real financial record.
- **`OutcomeServiceTest.java`** — *(placeholder, `value` package)* intended scope is recording whether an action actually produced the predicted outcome. The invariant: predicted and realized stay separate, and a prediction is never retroactively edited to match reality.

#### Build and tooling files

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

### Test strategy by kind

**Unit tests (pure, no Spring).** `MoneyTest`, `PricingVarianceRuleTest`, `DiscountVarianceRuleTest`, `FinancialTruthEngineTest`, `ContractServiceTest`, `TruthEngineFixtures`. These cover the deterministic financial core. The invariant across all of them is that a given input produces exactly the expected output, with no clock, no random source and no framework in the way. They are the tests that can run anywhere, including in this environment, and they are the ones that carry the most weight.

**ArchUnit boundary and dependency tests.** `ModuleBoundaryTest` and `DependencyRuleTest`, both currently placeholders. The invariants they should protect are that a business module never imports another business module, that `shared` and `platform` stay free of business dependencies, and that layering runs controller → service → repository with domain at the bottom. These are placeholders today, so the boundary is a convention rather than a constraint.

**MockMvc API tests.** `IngestionControllerTest`, `CalculationControllerTest`, `OpportunityControllerTest` — all placeholders. The invariants are stable HTTP contracts, correct status codes, and stable error shapes, since a client-visible contract that changes silently is a breaking change that no other layer would catch.

**Testcontainers integration tests.** The `TestcontainersConfiguration` and `TestCfoApplication` wiring is real; the tests that would use it are among the placeholders. The invariant is that migrations apply and real queries work against a real PostgreSQL, so an entity that does not match the schema is caught here rather than in production. These require a Docker daemon, which this environment does not have, so they could not be executed or verified while writing this chapter.

**Security and tenant-isolation tests.** `AuthenticationTest`, `AuthorizationTest`, `TenantIsolationTest`, `FileUploadSecurityTest` — all placeholders. The invariants are: no endpoint reachable without a token; authentication never treated as authorisation; one tenant's data never visible to another; and an upload never reaches a parser as a disguised or oversized payload. Of everything in the suite, tenant isolation carries the highest consequence — a failure there is a cross-customer data breach.

**Financial regression and reproducibility.** `FinancialRegressionTest`, `CalculationReproducibilityTest`, supported by `TruthEngineFixtures`. The invariants are that known inputs keep producing known outputs, and that the same snapshot always produces the same answer. These two together are what make the platform's numbers defensible to an auditor: a figure that cannot be reproduced is a figure that cannot be relied upon.

**Parser tests.** `CsvFileParserTest`, `ExcelFileParserTest`, `FileValidationServiceTest`. The invariants are that quoting, embedded delimiters and embedded newlines round-trip correctly, that the two input formats agree on the normalised model, and that a file failing validation is rejected at the gate rather than partially ingested.

### The journey, step by step

1. **Compile.** `mvnw clean verify` runs `maven-compiler-plugin` with the three annotation processors in a fixed order — Lombok, then `lombok-mapstruct-binding`, then `mapstruct-processor`. Compilation fails if a MapStruct mapper leaves a target property unmapped.
2. **ArchUnit boundary check.** The boundary rules run before or alongside the functional tests and fail the build on a cross-module import. This step is currently inert: both ArchUnit classes are placeholders, so nothing is enforced yet.
3. **Unit tests.** The pure financial and contract tests run next. They need no Spring context and no Docker, and they are where a deterministic regression is caught.
4. **API and security tests.** The MockMvc and security slices start a trimmed context and exercise the HTTP surface: authentication, authorisation, tenant isolation and endpoint contracts. All are placeholders today.
5. **Testcontainers integration tests.** Finally the container-backed tests start PostgreSQL, apply Flyway migrations, and exercise real queries. They need Docker and are skipped or fail in an environment without it.

### How the build is put together

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

### Key comments added in this pass

The comments in this slice exist to answer *why*, not *what* — the code already says what. The ones that matter most:

- **Every test class** carries Javadoc naming the behaviour it locks down and the consequence of that behaviour regressing. For the placeholder classes, the comment says what invariant the class is meant to protect and states plainly that it is not yet enforced, so the gap cannot be mistaken for coverage.
- **`TruthEngineFixtures`** explains that every value is a literal, and why: a regression suite whose expected values can move is not a regression suite.
- **ArchUnit test comments** explain why the boundary rules exist at all, and why they are split into two files — a violation should name the specific rule it broke rather than reporting "boundary broken".
- **`pom.xml`** carries a comment per dependency stating why it is present, and a comment per plugin and compiler argument. The three that carry real weight: the annotation-processor ordering, `unmappedTargetPolicy=ERROR`, and the springdoc 3.x pin with its Boot 4 rationale.
- **Wrapper and config files** carry purpose comments: what the wrapper pins, why `mvnw` must stay LF, why `HELP.md` is ignored, and why the absent `maven-wrapper.jar` is guarded against.
- **Documentation files** carry clarifying header comments recording what each document is authoritative *over* — so that `module-implementation-rules.md` wins over a README, an ADR, or a reading path, and so the vision document is not mistaken for a design document.

No test assertion, executable statement, import, signature, annotation, XML element or value was changed, deleted or reworded. This pass adds comments and one new documentation file, nothing else.

### Documentation status

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

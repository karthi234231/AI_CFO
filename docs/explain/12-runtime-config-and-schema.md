## 12. Runtime configuration & database schema

### Goal — configuration

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

### Goal — schema

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

### File inventory

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

### Flow of journey

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

### Schema build order

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

### Table-to-module map

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

### Flow of implementation

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

### Key comments added

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

### Known mismatches between the schema and the Java model

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
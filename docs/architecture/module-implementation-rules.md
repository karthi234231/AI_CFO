<!-- Authoritative engineering rules for this repository. Where any other document
     (README, ADRs, docs/code-flow, docs/explain) appears to disagree with this file,
     this file wins. It is the document an implementer or reviewer is expected to
     read before touching a module, and the document the ArchUnit tests in
     src/test/java/com/fintech/cfo/architecture/ are intended to enforce.
     Build and test mechanics are described in docs/explain/13-tests-build-and-docs.md. -->

# Module Implementation Rules

Binding rules for every module in this codebase. These exist so that modules
can be developed in parallel without conflicting, and so that the financial
logic stays trustworthy.

## 1. The verified toolchain

Everything below was empirically confirmed against this exact build
(Java 25.0.4.1, Spring Boot 4.1.1) by compiling **and running** it. Do not
re-introduce a dependency or API that has not been verified here.

| Capability | How to use it | Verified |
|---|---|---|
| **Records** | Default choice for DTOs and immutable value carriers. Accessors already exist - do **not** add `@Getter`/`@AllArgsConstructor` to a record; Lombok rejects it. | yes |
| **Sealed interfaces + pattern matching** | Model closed state sets (`switch` over a sealed hierarchy is exhaustive and compiler-checked). | yes |
| **Lombok 1.18.46** | `@Getter`, `@Builder` + `@Singular`, `@RequiredArgsConstructor`, `@Slf4j`, `@Value`. Version is managed by the Boot parent and is JDK 25 safe. **Never on a record.** | yes |
| **MapStruct 1.6.3** | Use for entity/DTO mapping instead of hand-written mappers. `componentModel=spring` is already set, so declare `Mappers.getMapper(...)` only for pure unit tests; inject the interface in Spring components. `unmappedTargetPolicy=ERROR`, so an unmapped target property **fails the build**. That is deliberate: a silently dropped amount field would corrupt financial reporting. Use `@Mapping`, `@Named`, `@ValueMapping` for real conversions. | yes |
| **JSpecify** (`@NullMarked`, `@Nullable`) | The null-safety standard adopted by Spring Boot 4. Put `@NullMarked` on a package (via `package-info.java`) and annotate genuinely optional fields `@Nullable`. | yes |
| **`ScopedValue`** | Preferred over `ThreadLocal` for tenant/security context: immutable, lexically scoped, and automatically unbound on exit, so a value cannot leak into a pooled thread. Real API: `ScopedValue.where(key, value).run(...)` / `.call(...)`, plus `get()`, `isBound()`, `orElse(...)`. There is **no** `enter()` and **no** `isSupported()`. | yes |
| **Commons CSV 1.14.1** | CSV parsing. Do not hand-roll a parser. | yes |
| **Apache POI 5.4.1** | XLSX parsing. | yes |
| **Micrometer 1.17.1** | `MeterFilter.deny(Predicate<Meter.Id>)` takes a `java.util.function.Predicate`. `MeterFilter.maximumAllowableTags` has a **four-argument** signature; there is no `IdPredicate` nested type. | yes |
| **springdoc 3.1.0** | Boot 4 requires springdoc **3.x**. 2.x targets Boot 3.x. | yes |

If you need a capability not in this table, verify it before use: compile a
small probe, or read the actual API with
`javap -cp <jar> <fully.qualified.Class>`. Do not infer an API from memory -
that is how `MeterFilter.IdPredicate` and `ScopedValue.enter()` were both
invented and had to be backed out.

## 2. Module boundaries

A module may import:

- `com.fintech.cfo.shared.**`
- `com.fintech.cfo.platform.**`
- Java, Jakarta EE, Spring, and the libraries declared in `pom.xml`

A module **must not** import another business module (`identity`, `ingestion`,
`financial`, `contract`, `financialtruth`, `evidence`, `opportunity`,
`investigation`, `value`, `ai`, `reporting`, `processing`).

Where two modules must exchange data, the boundary is a type owned by the
*consumer* or a port interface. The wiring between them belongs to the
integration milestone, not to either module. Violating this rule produces a
compile cycle between modules, which is exactly what the boundary prevents.

You own exactly the directories listed in your task. Do not create, edit, or
delete files outside them.

## 3. Money

`Money` (`shared.domain.Money`) is the only representation of an amount.

- Never `double` or `float` for money, anywhere.
- Never add or subtract amounts in different currencies. `Money` throws on a
  currency mismatch rather than converting silently. Conversion belongs to an
  explicit, separately audited FX component that does not exist yet.
- Division requires an explicit scale and `RoundingMode`. Do not rely on
  defaults.
- Choose a rounding policy deliberately and document it at the call site. Money
  stored in the database uses `NUMERIC(20,4)`; quantities use `NUMERIC(20,6)`.
- Report variance with its currency. A variance without a currency is a bug.

## 4. Determinism

The financial calculation path must be reproducible months later.

- No `LocalDate.now()`, `Instant.now()` or `OffsetDateTime.now()` in calculation
  or pricing logic. Inject `DateTimeUtils` (or a `java.time.Clock`) so tests can
  fix time.
- No randomness, no locale-sensitive formatting (`toUpperCase()` without
  `Locale.ROOT`), no hash-ordered iteration influencing a sum.
- Any value that feeds a financial result must carry the version and effective
  date used, so a re-run can prove it evaluated the same inputs.
- An input hash/checksum must be computed for any calculation that claims to be
  reproducible.

## 5. Evidence and lineage

Every monetary result must be traceable to the row that produced it.

- A canonical record carries a `SourceReference` (or equivalent source columns):
  source system, record id, file id, row number.
- Never store a full source document inside a business entity. Store a
  reference and a checksum.
- A reported amount with no way back to its source row is not shippable.

## 6. Tenancy

`organization_id` is the tenant boundary.

- Every tenant-owned table has `organization_id`, and every query filters on it.
- Never accept an organization id from a client request body or query
  parameter to decide scope. Scope comes from the authenticated
  `SecurityPrincipal` via `SecurityContext`.
- When a record cannot be found *within the caller's tenant*, report not-found.
  Never distinguish "absent" from "belongs to someone else".
- Never log monetary values, contract text, uploaded file contents, credentials
  or bearer tokens. Log identifiers, statuses and correlation IDs.

## 7. Schema naming (guidance for the later persistence pass)

- Schema is owned by the Flyway migrations in `src/main/resources/db/migration`.
  Map entities to those exact table and column names. Do not edit migrations to
  suit an entity; if the schema is genuinely wrong, say so in your summary
  instead of silently diverging.
- Do not use `ddl-auto` to create or alter tables in production profiles.
- Versioned, tenant-scoped entities use a `version` column for optimistic
  locking; translate lock failures into `ConflictException`.
- Use `BigDecimal` in entities for money columns. Prefer
  `NUMERIC(20,4)` for amounts, `NUMERIC(20,6)` for quantities and unit prices.

## 8. Audit

Record business and security events through `platform.audit.AuditService`.

- `AuditService.record(...)` returns an outcome. For events where a missing
  record would be a compliance problem, check `outcome.recorded()` and react to
  `false`.
- Auditing is not a substitute for domain history. A state transition should
  also be visible in the domain's own lifecycle records.

## 9. API

- Return `ApiResponse<T>` for success and let `GlobalExceptionHandler` render
  failures. Do not hand-roll error bodies.
- Throw the typed exceptions from `shared.exception`
  (`ValidationException`, `NotFoundException`, `BusinessRuleException`,
  `ConflictException`, `AccessDeniedException`) rather than returning error
  objects or throwing raw framework exceptions.
- Document endpoints with springdoc annotations and describe them in terms of
  the business action, not the implementation.
- Never return an entity directly. Map to a DTO.

## 10. Code style

- Tabs for indentation. Explicit imports, no wildcards.
- Records for immutable value carriers, final classes for types with behaviour.
- Sealed interfaces plus `switch` pattern matching for closed state sets, so
  adding a state forces every handler to be revisited at compile time.
- Constructor injection only; no field injection, no `@Autowired` on fields.
  Use Lombok `@RequiredArgsConstructor` where it removes real noise, but never
  on a record.
- Prefer a MapStruct `@Mapper` over a hand-written mapper class. Use
  `@ValueMapping`/`@Named` for genuine conversions rather than defaulting to
  implicit field copying.
- Javadoc explains *why* a decision was made and what invariant it protects.
  Do not narrate what the code obviously does, and do not add comments that
  merely restate the method name.
- No `TODO` placeholders in delivered code. If something is genuinely not
  implemented, say so in your final summary rather than leaving a stub.

## 11. Persistence and connections

Persistence, database access and outbound connections are being added in a
later pass, after the codebase exists. Until then:

- **Do not** write `@Entity` classes, Spring Data repositories, `@Configuration`
  beans that open connections, Flyway migrations, or any HTTP/AI client.
- Model domain shape as plain records and immutable classes that mirror the
  intended schema. Keep the mapping obvious so adding JPA later is mechanical
  rather than a redesign.
- Where a component would need to read or write persisted state, declare a
  narrow **port interface** in your module and leave the implementation for the
  persistence pass. Return domain results, not framework types.
- Leave `*/controller/`, `*/repository/` and any `*Entity*` stub files untouched
  and list them in your report.

## 12. Verification

Verify your own module before reporting completion:

```powershell
powershell -ExecutionPolicy Bypass -File "C:\Users\21pa1\AppData\Local\Temp\opencode\cfoagent\check.ps1" <module>
```

It compiles your module against the full dependency classpath using a private
output directory, so it is safe to run while other agents work in parallel.

**This compiles only. The application must not be started** - that is an
explicit project constraint. Unit tests that need no Spring context and no
database may be run with `mvnw -Dtest=<YourTest>`; there is no Docker or local
Postgres available in this environment, so Testcontainers-based tests cannot be
verified here. Say so plainly in your summary rather than claiming they pass.
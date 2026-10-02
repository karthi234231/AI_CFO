# 1. Foundation — shared kernel & platform

## Module goal

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

## File inventory

Every file in the slice, with what it is for.

### Bootstrap

| Path | Goal of this file |
| --- | --- |
| `CfoApplication.java` | Entry point. Declares the component-scan root (`com.fintech.cfo`) and enables `@ConfigurationPropertiesScan` so typed properties records anywhere in the tree are registered by being present. Contains no logic on purpose. |

### `shared/domain` — the business vocabulary

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

### `shared/enums` — cross-module status vocabulary

| Path | Goal of this file |
| --- | --- |
| `shared/enums/Currency.java` | Curated closed set of currencies the business transacts in, for config values and enum-typed API parameters. Converts losslessly to and from `CurrencyCode`. |
| `shared/enums/ProcessingStatus.java` | Status of an asynchronous processing unit. Separates `SKIPPED` from `FAILED` so an ineligible batch is not alerted on as an error. |
| `shared/enums/Status.java` | Coarse lifecycle status shared across long-running business records, alongside the Phase 0 opportunity/value lifecycle it defers to. |

### `shared/exception` — the error contract

| Path | Goal of this file |
| --- | --- |
| `shared/exception/DomainException.java` | Base for deliberate application failures, carrying a stable machine-readable code alongside the human message. The code is the client contract; the message is a diagnostic. |
| `shared/exception/ValidationException.java` | Domain validation failure. The exception every `Preconditions` guard raises, so a bad request is a 400 rather than an unhandled 500. |
| `shared/exception/NotFoundException.java` | Unresolvable resource, reported so that under tenant isolation the message cannot reveal that a record belongs to someone else. |
| `shared/exception/ConflictException.java` | State conflict: optimistic locking, duplicate operation, idempotency conflict, illegal transition. |
| `shared/exception/AccessDeniedException.java` | Application-layer authorization refusal, for decisions Spring Security's own authorization does not make. |
| `shared/exception/BusinessRuleException.java` | A well-formed request rejected by a business rule. Exists so the calculation engine raises it rather than returning a fabricated or default monetary value. |

### `shared/security` — request identity

| Path | Goal of this file |
| --- | --- |
| `shared/security/SecurityPrincipal.java` | Immutable authenticated caller: user, email, organization scope, authorities, and whether the tenant was verified server-side. Never accepted from client-supplied request parameters. |
| `shared/security/SecurityContext.java` | Static access point reading Spring Security's `SecurityContextHolder`, so there is exactly one source of truth for request identity. Returns null for anonymous, throws for `requirePrincipal`. |

### `shared/util` and `shared/validation`

| Path | Goal of this file |
| --- | --- |
| `shared/util/DateTimeUtils.java` | The single injectable clock. Business dates are UTC; tests inject a fixed `Clock` so period boundaries and reproducibility hold. Also owns month/quarter/financial-year arithmetic. |
| `shared/util/HashUtils.java` | SHA-256 helpers for file checksums and content fingerprints. Integrity and deduplication only, never password hashing. Streams so large documents never load whole. |
| `shared/util/IdGenerator.java` | Single seam for identifier generation. Random UUIDs so records can be created across modules without a central sequence and without leaking record counts. |
| `shared/validation/Preconditions.java` | The guard clauses every module's compact constructor uses. Centralised because twenty-six hand-copied private helpers had drifted and made the same defect a 500 in one model and a 400 in another. |
| `shared/validation/package-info.java` | Package-level rationale for the guard-clause centralisation, and the `@NullMarked` nullness default for the package. |

### `platform/audit` — append-only history

| Path | Goal of this file |
| --- | --- |
| `platform/audit/AuditEvent.java` | Immutable transport type for an audit fact. Fifteen shared fields so one query answers any compliance question; no setters, so a recorded fact cannot be rewritten. |
| `platform/audit/AuditEventType.java` | Closed set of auditable event kinds, grouped by concern so a reviewer can separately ask "who touched this data" and "what happened in the business". Values are storage contracts. |
| `platform/audit/AuditEventEntity.java` | JPA mapping for `audit_events`, with indexes for the three access patterns (org+time, entity+time, correlation). Append-only; `occurred_at` is never updated. |
| `platform/audit/AuditRepository.java` | Narrow read/write port over audit storage. Append is the only write; every read takes an explicit limit so a diagnostic call cannot become expensive. |
| `platform/audit/AuditEventJpaRepository.java` | Spring Data queries for the same port, including the entity-history and correlation lookups the port promises. |
| `platform/audit/AuditService.java` | The entry point for recording audit facts. Writes in `REQUIRES_NEW` so an audit row survives independently of the business transaction, and swallows storage failures so audit trouble never rolls back a legitimate financial operation. Returns an outcome so callers who need a complete trail can escalate. |

### `platform/config` — application wiring

| Path | Goal of this file |
| --- | --- |
| `platform/config/ApplicationProperties.java` | Typed, immutable configuration bound from `cfo.application.*`, with a default for every field so the app starts with no config block at all. Secrets deliberately excluded. |
| `platform/config/JacksonConfig.java` | JSON rules that matter financially: monetary numbers go through `BigDecimal` and never `double`, trailing tokens are rejected, polymorphic deserialization stays off, and shared value objects render as their canonical string form. |
| `platform/config/TransactionConfig.java` | Declares that application services own transaction boundaries and repositories do not, and records why ingestion and calculation are chunked rather than wrapped in one large transaction. |
| `platform/config/AsyncConfig.java` | Virtual-thread executor for lightweight, non-durable work only, plus the handler that stops a failure in a `void @Async` method from disappearing silently. |
| `platform/config/OpenApiConfig.java` | Document-level OpenAPI model: service title, bearer scheme applied globally, and the correlation header with the exact constraints the filter enforces. |

### `platform/idempotency` — exactly-once writes

| Path | Goal of this file |
| --- | --- |
| `platform/idempotency/IdempotencyRecord.java` | Persisted claim with an optimistic-locking version, a payload fingerprint, the stored response, and an explicit four-state lifecycle. Two concurrent requests with the same key cannot both complete. |
| `platform/idempotency/IdempotencyService.java` | Claims a key in its own short transaction *before* the business work starts, so a process death mid-handler replays rather than duplicates. Rejects a key reused with a different payload. |
| `platform/idempotency/IdempotencyRepository.java` | Three storage operations: lookup by key, cheap existence check, and the expiry sweep — the only deletion in the mechanism. |
| `platform/idempotency/IdempotencyFilter.java` | Applies idempotency to unsafe methods carrying an `Idempotency-Key`. Buffers the response so a completed claim replays verbatim, and abandons the key when the handler fails so the caller may retry. |

### `platform/observability` — metrics without leaks

| Path | Goal of this file |
| --- | --- |
| `platform/observability/BusinessMetrics.java` | Business-level meters for calculation runs, variances, ingestion batches, opportunity transitions, audit failures and idempotent replays. Counts and latencies only; monetary values are never tag values. |
| `platform/observability/MetricsConfiguration.java` | Registry hygiene enforced in code: drops any meter carrying an identity tag and caps total distinct meters, so instrumentation cannot become the incident or export financial data. |
| `platform/observability/TracingConfiguration.java` | Deliberately does *not* configure a tracer, since `micrometer-tracing` is absent and a tracer without an exporter costs work and yields nothing. Configures only the common application tag. |

### `platform/persistence` — JPA auditing

| Path | Goal of this file |
| --- | --- |
| `platform/persistence/JpaConfiguration.java` | Enables JPA auditing and supplies the auditor beans, resolving to empty for scheduled and async work so automated activity is attributed to the system rather than to whoever triggered it. |
| `platform/persistence/PersistenceAuditListener.java` | The same auditor resolution as a testable collaborator, plus two small helpers for deciding whether a row is attributable to a real user. |

### `platform/storage` — evidence blobs

| Path | Goal of this file |
| --- | --- |
| `platform/storage/ObjectStoragePort.java` | Hexagonal port for object storage so financial modules never depend on a vendor SDK. Streaming both ways; `localPath` throws by default because a remote store cannot honour it. |
| `platform/storage/ObjectStorageService.java` | Tenant-scoped, checksum-verified facade over the port. Names objects by tenant so one tenant's key cannot reach another's evidence, and reports a foreign key as "not found" rather than "forbidden". Ships a sandboxed local-filesystem default that cannot be escaped with `../`. |
| `platform/storage/StorageObject.java` | Metadata handle for a stored blob, carrying no content so large documents never pass through the heap twice. |

### `platform/web` — the HTTP edge

| Path | Goal of this file |
| --- | --- |
| `platform/web/CorrelationIdFilter.java` | Creates or validates one correlation ID per request, publishes it as a response header, and puts it in the MDC. Runs at `HIGHEST_PRECEDENCE` so everything downstream can join its logs. |
| `platform/web/RequestLoggingFilter.java` | Exactly one structured entry per request, timed on a monotonic clock and logged in `finally` so failures are never missed. Runs at `HIGHEST_PRECEDENCE + 1` so the MDC is already populated. Never logs payloads, headers or financial data. |
| `platform/web/ApiResponse.java` | Standard success envelope: payload plus metadata carrying the correlation ID supplied by the caller, never regenerated. |
| `platform/web/ApiErrorResponse.java` | The single stable error envelope. Clients branch on `code`; stack traces, SQL, class names and provider internals must never appear. |
| `platform/web/GlobalExceptionHandler.java` | Funnels every exception into one response shape and decides status, code, title and safe detail per exception type. Ordered so specific handlers beat the catch-all, and never echoes an unexpected exception's message. |

---

## Flow of journey

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

## Flow of journey — background work

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

## Flow of journey — evidence upload and read

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

## Flow of implementation

### Invariants other modules may depend on

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

### What each module may depend on

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

### Why the key lines exist

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

### Caveats worth stating plainly

- **The identity module is unimplemented.** `identity/**` is currently stub placeholders, so nothing
  in the repository constructs a `SecurityPrincipal` and `SecurityContext.currentPrincipal()`
  always returns null at runtime. `shared/security` is the consumer side of a contract whose producer
  does not exist yet. This slice documents what the code states rather than a working JWT path.
- **Spring Security is not wired.** There is no `SecurityFilterChain` in this slice, so the filter
  ordering described above places `IdempotencyFilter` after a security chain that is not yet present.
- **Tracing is intentionally absent**, by decision recorded in `TracingConfiguration`, not by
  oversight.

---

## Key comments added

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

### A note on concurrent edits

Several files in this slice (`DateTimeUtils`, `HashUtils`, `GlobalExceptionHandler`,
`RequestLoggingFilter`, `CorrelationIdFilter`, `AuditEventEntity`, `IdempotencyRecord`,
`IdempotencyFilter`, `AuditService`) arrived already carrying extensive design-rationale Javadoc, and
at least one was rewritten by another process while this documentation pass was reading it. On-disk
content was treated as authoritative, every file was re-read immediately before editing, and all
existing comments were preserved verbatim. No executable statement, import, signature, annotation or
indentation was altered.
# 05 Kernel + Platform + Config

## A. WHY
Shared money types, HTTP plumbing, audit, storage, DB
shape, and wiring. Everything above depends on this.

## B. FLOW request in
Tomcat -> CorrelationIdFilter -> RequestLogging ->
IdempotencyFilter -> Security (TenantContext) ->
controller -> GlobalExceptionHandler maps ex->HTTP.

## C. FILES
shared 24 REAL: domain 8, enums 3, exception 6,
security 2, util 3, validation 2.
platform 28 REAL: web 5, config 5, audit 6,
idempotency 4, storage 3, observability 3,
persistence 2. identity 30 PH. processing 12 PH.

## D. DEEP DIVE
### Money.java
final+BigDecimal never double; same-currency guard;
divide needs scale+mode; equals via compareTo (100==1E+2);
hash via stripTrailingZeros; clamp/min/max/between.
### CurrencyCode.java L26-141
interned 3-letter; of() trim->upper(ROOT)->len+range
(no regex)->map get then computeIfAbsent (bound 26^3);
inr/usd/eur consts.
### CorrelationIdFilter L26-63
HIGHEST OncePerRequest; reuse X-Corr-ID if
[A-Za-z0-9._-]<=64 else UUID; header+attr+MDC/finally
remove. Observability only, never auth.
### ApplicationProperties L17-47
record cfo.application.* name/version/env enum
+isProduction+from() case-insensitive.
### Storage/Audit/Idempotency
root ./var/storage, normalize anti-traversal, sha256.
Audit append-only JSONB never throws. Idempotency-Key
TTL PT24H.

## E. V1-V10 (one line each)
V1 orgs(id,name,INR,active). V2 users/roles/memberships.
V3 source_files(sha256 uniq/org)+runs+errors. V4 invoices
+lines(NUMERIC 20,4, uniq org+ext). V5 contracts+
pricing(20,6)+discount(20,4)+rules. V6 runs(checksum,
version)+results(var+ccy CHECK). V7 refs+edges(no loop).
V8 opps+reviews. V9 actions+outcomes+attributions+
realized. V10 audit_events append-only.

## F. application.yml keys
datasource CFO_DB_URL Hikari20/5; jpa no-open-view,
ddl validate, UTC, batch50; flyway baseline; multipart
25/30MB; virtual threads; 8080 never leak; health/info/
metrics/prometheus; cfo app/security(issuer,audience,
skew60s)/ingest(25MB,200k,csv+excel)/storage/async8-32-
500/idempot/TTL/ai off/springdoc.

## G. IMPLEMENT PH
identity: OIDC resource-server JWT->Tenant RBAC.
processing: Batch chunk jobs w/ skip+retry.

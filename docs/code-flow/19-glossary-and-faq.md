# 19 — Glossary and FAQ

A reference for someone who has to work in this repository without reading all of
it. Every definition below was checked against the code, not recalled; the
`file:line` pointer is the authority, and where this document and the code
disagree the code wins.

Three layers live here:

1. **Glossary** — every domain term, alphabetically, with the chapter that owns it.
2. **FAQ** — the questions a newcomer actually asks, answered concretely.
3. **Reading paths** — "to understand X, read Y then Z, about N minutes".

Terms are written in the vocabulary the code uses, including the three project
coinages (economic opportunity, financial truth, realized value) that are
defined by behaviour rather than by a specification.

---

## Glossary

### Attribution

The act of deciding how much of one measured amount a single action is
responsible for. Attribution is a separate row from the action and from the
outcome because the same action usually recovers part of several opportunities
and one opportunity is usually recovered by several actions; collapsing the two
makes the sum either unauditable or double-counted. The guard lives on
`value.model.ValueAttribution`, whose javadoc states that V9's schema enforces
non-negativity on the attributed amount but nothing else, so the module owns the
reconciliation. Owner: chapter 13 (value), `value/model/ValueAttribution.java:65`.

### Canonical form

The fixed textual rendering of a value used for hashing, comparison and
fingerprinting. A canonical form is order-stable and scale-insensitive so that
two descriptions of the same commercial fact produce the same string. Contract
terms render through `CanonicalText`; a near-duplicate `CanonicalForm` still
exists in `contract/model/CanonicalForm.java:30` and is retained only because
deleting it was out of scope when it was found. Financial amounts render
through `RoundingPolicy.canonicalMoney`
(`financialtruth/model/RoundingPolicy.java:105`). Owner: chapters 08 and 07.

### CommercialRule

A row in `commercial_rules` carrying a machine-checkable clause — a price floor,
a discount cap, a minimum order — attached to a contract term. Most of the
`expression` column is prose written by a commercial reader and is never
evaluated; only the subset inside the supported grammar is. Owner: chapter 07,
`contract/model/CommercialRule.java`.

### ContentHash

The SHA-256 digest of an uploaded file's bytes, stored as
`source_files.checksum_sha256`. It is the identity of the file: V3 makes
`(organization_id, checksum_sha256)` unique, so the same bytes uploaded twice are
one source file, not two. SHA-256 only, and it is not password hashing.
Owner: chapter 05, `ingestion/model/FileChecksum.java:21`.

### Correlation ID

The per-request identifier stamped onto every log line and returned in every API
response, so a user reporting a failure can be joined to the server's own record
of that request. It is a tracing concern owned by `platform.web`, not a business
identifier, and it never appears in a calculation. Owner: chapter 15,
`platform/web/GlobalExceptionHandler.java:59`.

### CurrencyCode

The typed currency a `Money` is denominated in. A `Money` may only be added to,
compared with, or clamped against another `Money` of the same currency; a
mismatch raises rather than converting. The module holds no exchange rates, so
no total ever crosses currencies. Owner: chapter 02,
`shared/domain/Money.java:19` and `shared/domain/CurrencyCode.java`.

### deterministicFingerprint

The SHA-256 over a calculation run's rule version, input checksum, timestamps
and every result row in evaluation order, including the run id. It is the proof
of reproducibility: two runs over identical inputs, rules and clock produce the
same fingerprint, and any movement in an amount, a term version or the
evaluated-at instant changes it. Results are appended in list order and never
re-sorted, because a reordering regression must change the fingerprint.
Owner: chapter 08, `financialtruth/model/CalculationRun.java:100`.

### DiscountTerm

A contractual entitlement to reduce an invoice line, in either percentage or
fixed-amount form, scoped to a contract, product, customer or nothing, and
bounded by an effective window. An absent entitlement is a *measured* zero; an
unreadable one is not, and the engine treats the two differently.
Owner: chapters 07 and 08, `contract/model/DiscountTerm.java` and
`financialtruth/model/DiscountTerm.java`.

### Economic opportunity

Project coinage. A quantified, tenant-scoped claim that recoverable money exists
and is worth pursuing — created by turning a calculated variance into something
with an owner, a lifecycle, evidence and a value. It is deliberately not a
finding and not a variance: a variance is arithmetic, an opportunity is a
business object with state. The type is `opportunity.model.EconomicOpportunity`,
which is still a stub. Owner: chapter 10, `opportunity/model/`.

### EffectiveWindow

The `effective_from` / `effective_to` pair on every contract row that takes part
in as-of resolution. Both ends are **inclusive**, and a null end is
open-ended, not a sentinel date. The inclusive convention is chosen because a
duplicated boundary day is visible in the candidate set and decidable by the
documented tie-break, whereas a dropped day is invisible and unrecoverable.
Owner: chapter 07, `contract/model/EffectiveWindow.java:30` and `:76`.

### Evidence

A stored artefact supporting a claim: a document, a snapshot of a record, or a
reference to one, hash-addressed so it can be shown not to have changed since
the claim was made. Evidence is separate from lineage — evidence is *what* was
captured, lineage is *how it connects*. Owner: chapter 11,
`evidence/model/EvidenceSnapshot.java` (stub) and `evidence/model/EvidenceReference.java` (stub).

### FinancialImpact

The rolled-up financial consequence of a set of line results, one per currency,
carrying the total amount, a confidence, how many lines were included, how many
were not, and a rationale sentence that says so in words. A total that omits
lines is a floor, not the whole truth, and is downgraded to MEDIUM confidence
for that reason. Owner: chapter 08,
`financialtruth/calculator/ImpactAggregator.java:76`.

### Financial truth

Project coinage. The set of amounts the system is willing to defend: what the
contract entitled, what was charged, the signed difference, and the roll-up —
all produced deterministically from stored data with no model in the path. It
is a claim about provenance and reproducibility, not about accuracy in the
abstract. Owner: chapter 08, `financialtruth/calculator/FinancialTruthEngine.java:72`.

### Finding

One rule's verdict about one line, recorded as its own result row even when the
rule made no claim. Recording the non-claims matters: omitting them would hide
the fact that a check was considered at all. A finding carries its rule code and
version, its status (`Evaluated`, `NotApplicable`, `IncompleteInputs`) and a
confidence derived from that status. Owner: chapter 08,
`financialtruth/calculator/FinancialTruthEngine.java:295`.

### idempotency

The property that a retried write request produces one effect, not two. A key is
claimed in its own short transaction *before* the business work starts, so a
crash mid-handler leaves a durable claim and the retry replays rather than
duplicates. Three outcomes are possible: proceed, replay the stored response, or
in-progress. A key reused with a *different* payload is a conflict, not a
replay. Owner: chapter 03, `platform/idempotency/IdempotencyService.java:71`.

### Impact

The direction money moved, derived from a variance's sign rather than supplied
alongside it. Positive means the customer was overcharged and the money is
recoverable; negative means the customer was undercharged. `ImpactDirection` is
computed from the amount so the two can never disagree. Owner: chapter 08,
`financialtruth/model/Variance.java:114`.

### inputChecksum

The SHA-256 over the canonical rendering of every input a calculation read —
the query key plus labelled, **sorted** groups of term canonical forms. Two
properties are deliberate: row order cannot move the digest, because the same
rows in a different order describe the same commercial position; and every
*candidate* term is hashed, not just the winner, so a checksum attests to the
decision that was made and not only to the outcome. Owner: chapter 07,
`contract/service/InputChecksum.java:46`.

### Lineage

The directed graph connecting a source row to everything derived from it:
source row → calculation result → impact → opportunity → action → outcome. It
is a DAG of edges and nodes, not a column chain, because one source row
legitimately fans out into several results. Lineage answers "how did this number
come to exist"; evidence answers "what proves it". Owner: chapter 11,
`evidence/model/LineageNode.java` and `LineageEdge.java` (both stubs).

### Money

An immutable amount bound to exactly one currency. It is a final class, not a
record, because it carries guarded arithmetic: same-currency-only operations, and
a `divide` that has no overload without an explicit scale and rounding mode.
Its `equals` and `hashCode` are scale-insensitive, so `100` and `100.00` are the
same money. Owner: chapter 02, `shared/domain/Money.java:19` and `:135`.

### OrganizationId / tenant

The tenant boundary. `OrganizationId` is a one-value record over a UUID with no
behaviour beyond construction. Every enterprise row carries one, and it is
never taken from a request body, query parameter or path variable — it comes
from the verified principal. Owner: chapters 02 and 04,
`shared/domain/OrganizationId.java:20`.

### Outcome

A recorded business result against an action: what was actually measured to
have happened, when it was measured, and its status. It is distinct from
realized value because measurement and cash arrival are different events with
different evidence, and the gap between them is itself the finding the value
module exists to surface. Owner: chapter 11, `value/model/Outcome.java` (stub).

### PricingTerm

A versioned contractual price for a product or a contract-wide default, with
minimum and maximum bounds, a currency, an effective window and a
`term_version`. It is the authority for what a line *should* have cost on a
given business date. Note that `contract` and `financialtruth` each define their
own `PricingTerm`; see chapter 20's decision list, ⚠ Review. Owner: chapters 07
and 08.

### Realized value

Project coinage. The portion of a measured amount that may be counted as money
that actually arrived. It is the only row in the product that may be summed
into a recovery total, and the only place a figure is asserted to be real rather
than calculated. A reversal is a new row, never a decrement. Owner: chapter 11,
`value/model/RealizedValue.java:1` (spec javadoc; the class itself is a stub).

### RoundingPolicy

The single rounding and scale policy for the calculation module: monetary amounts
at 4 decimal places, quantities and unit prices at 6, and `HALF_UP` everywhere.
Each line component is rounded exactly once, at the point it is produced; totals
are the plain sum of already-rounded components, which is what makes the
arithmetic associative and line order irrelevant. Owner: chapter 08,
`financialtruth/model/RoundingPolicy.java:43`.

### RuleExpression

The evaluator for the minority of `commercial_rules.expression` values that are
genuinely arithmetic. The text is tokenised and parsed once into a sealed tree
of records; there is no `eval`, no reflection and no name-to-class lookup, and
division requires an explicit scale and rounding mode. Source length, nesting
depth and node count are all bounded. Owner: chapter 07,
`contract/model/RuleExpression.java:52` and `:165`.

### SecurityPrincipal

The immutable authenticated caller: user id, email, organization scope, granted
authorities, and a `tenantVerified` flag. It is established from a verified
credential and never accepted from client-supplied parameters. `isTenantResolved()`
is the single test a tenant-scoped query should make before touching tenant data.
Owner: chapters 02 and 04, `shared/security/SecurityPrincipal.java:78`.

### SourceReference

An immutable pointer from normalized data back to where it came from: source
system, record type, record id, and optionally the uploaded file and 1-based row.
It stores references only, never row contents — that is what lets a reported
amount be traced without holding a copy of the document. All five components
participate in equality, so the same record seen in two files is genuinely two
provenances. Owner: chapter 02, `shared/domain/SourceReference.java:17`.

### ScopedValue

The JDK lexical-scope binding the tenant context is intended to use, in place of
a `ThreadLocal`: immutable, lexically scoped and automatically unbound, so a
tenant value cannot leak into a pooled thread. The intended binding point is
`identity.security.TenantContextFilter`, which is currently a stub; see chapter
20. Owner: chapters 04 and 15, `identity/security/TenantContext.java:4`.

### Specificity

How precisely a contract term targets the request: product and customer (3),
product only (2), customer only (1), contract-wide default (0). Among eligible
rows the most specific wins **before** version is considered — otherwise a
negotiated customer rate at `term_version 1` loses to a published default at
version 3 and the bespoke price silently reverts to list. Owner: chapter 07,
`contract/service/TermSelector.java:96`.

### Term precedence

The total order used to break ties between competing term rows, derived only
from stored columns: highest `term_version`, then latest `effective_from`, then
earliest `effective_to` with open-ended ordered last, then lowest row id. The
optimistic-lock `version` column deliberately plays no part, so an unrelated
concurrent UPDATE cannot change which terms a historical calculation used.
Owner: chapter 07, `contract/service/EffectiveTermResolver.java:65`.

### Variance

The signed difference `actual - expected`, carried with both operands and the
component type. Positive means the customer was overcharged. The amount is
computed once in the constructor, in `Money`, so no caller can hand it a figure
that disagrees with its own expected and actual values. Owner: chapter 08,
`financialtruth/model/Variance.java:14`.

### CalculationRun

The record that makes a result defensible: run id (supplied, never generated),
tenant, calculation type, status, rule version, period, input checksum, the
clock-derived start and completion instants, and the results. A completed run
must record `completedAt`; a failed run must record why. Owner: chapter 08,
`financialtruth/model/CalculationRun.java:33`.

### ActionPlan

The set of concrete next steps attached to an opportunity, each with an owner
and a due framing, carried separately from the lifecycle status so a workflow
can change without the plan being rewritten. It is a stub; the specification is
`value/model/ActionPlan.java`. Owner: chapter 11.

---

## FAQ

**1. Why can AI not produce a number?**
Because a number produced by a model cannot be reproduced, audited or defended,
and this product's whole claim is that its figures can. The model is confined to
explaining amounts the engine already computed. When a rule raises because no
contract price was in force, the run fails rather than substituting a default,
and when inputs are incomplete the line gets no combined row at all
(`FinancialTruthEngine.java:66`).

**2. Why is `Money` a class and not a record?**
A record would auto-generate `equals`/`hashCode` over `BigDecimal`, which
compares `100` and `1E+2` as unequal, so two representations of the same amount
would compare unequal inside a collection. The class also owns the guard that
every two-operand operation passes through, and a `divide` with no
scale-less overload (`shared/domain/Money.java:19`).

**3. Why is the idempotency key global rather than per-tenant?**
The key is what the *caller* chose, and the fingerprint check means a key reused
with a different payload is a conflict rather than a silent replay. Scoping by
tenant would let two tenants choose the same key and one would start receiving
the other's stored response. The claim is made in its own `REQUIRES_NEW`
transaction before the handler runs, which is what makes a mid-handler crash
replay rather than duplicate (`IdempotencyService.java:71`).

**4. Why does the input checksum ignore row order and decimal scale?**
Because neither carries commercial meaning. A repository returning the same rows
in a different order describes the same position, and a value re-read as `920.0`
rather than `920.00` is the same value. A checksum that moved on either would
report a spurious input change and break every reproducibility proof
(`InputChecksum.java:23`, `RoundingPolicy.java:100`).

**5. Why is variance `actual - expected` and not the other way round?**
So that a positive figure always means the same thing everywhere: the customer
was charged more than the contract entitles, and the money is recoverable. The
discount component is the documented asymmetry — it is measured on the discount,
so a positive discount component *reduces* the net variance
(`financialtruth/model/Variance.java:14`, `VarianceCalculator.java:116`).

**6. Why is tax treated as pass-through?**
Tax is statutory, not contractual, so the amount expected equals the amount
charged and it cancels out of the variance. It is still included on both sides of
the net payable because the payable includes it, and it is still reported for
exactly that reason (`FinancialTruthEngine.java:56`).

**7. Why must a missing record be not-found rather than forbidden?**
Telling a caller that a record in another tenant is "forbidden" confirms it
exists. `NotFoundException` maps to 404 identically whether it was reached
through the invoice endpoint or the opportunity endpoint, so the two routes
cannot drift into telling a client different things
(`GlobalExceptionHandler.java:138`).

**8. Why is `uses =` needed on the MapStruct mappers?**
`@Mapper(config = FinancialMappingSupport.class, uses = FinancialMappingSupport.class)`
(`financial/mapper/InvoiceMapper.java:24`). Without it MapStruct generates its
own trivial implementations and the shared support class — which is where the
trimming, blank-collapsing and `Money` construction rules live — is never
consulted, so the same field is normalised two different ways in two mappers.

**9. Why does the suite need `-Duser.timezone=UTC`?**
Effective windows and business dates are resolved against dates, and a test JVM
in a local zone can place a boundary date on the wrong side of midnight, making
the suite pass in one timezone and fail in another. It is on `argLine` rather
than `systemPropertyVariables` because `argLine` is a real JVM argument resolved
at startup, whereas a system property is set later by the surefire booter and
only takes effect if nothing has already triggered zone resolution (`pom.xml`,
surefire configuration).

**10. Why is an invoice deduplicable without having a resolution key?**
`Invoice.resolutionKey()` returns null when `externalKey` is absent, and that is
reported rather than worked around. The natural key `(organization_id,
invoice_number)` still identifies the invoice; the resolution key is the
*external* identity, and inventing one when the source did not supply it would
fabricate provenance (`financial/model/Invoice.java:35`).

**11. Why is no-double-counting the module's job and not the database's?**
Because the thing that must not be counted twice is not a row — it is a
deviation. A total that summed pricing rows, discount rows and the combined row
would count the same rupees three ways, and no unique index can express that. The
aggregator therefore *rejects* any non-combined result rather than filtering it,
so a caller cannot misread "no complaint" as "nothing to worry about"
(`ImpactAggregator.java:160`).

**12. What happens to a rejected row, and how does it stay traceable?**
A rejected row is retained, not discarded: it carries its `RowCoordinate` (file,
sheet, row number) and a `RejectionReason`, so a user can open the file at the
exact line and see why it was refused. `RejectedRow.of` is how a validator
reports one (`ingestion/model/RejectedRow.java`, `DuplicateValidator.java:70`).

**13. What happens when the same file is uploaded twice?**
The second upload resolves to the same source file, because `(organization_id,
checksum_sha256)` is unique in V3. Re-imported invoice lines are replaced by
position rather than appended, so a re-run does not duplicate the ledger
(`ingestion/model/FileChecksum.java:14`).

**14. Why is a duplicate row decided positionally?**
Because "the first occurrence wins" is only reproducible if rows are fed in
reading order, which is what the orchestrator does — one validator instance per
file. A duplicate is a property of the *set* of rows, not of one row, so the
validator is stateful on purpose (`DuplicateValidator.java:61`).

**15. Why does the checksum hash losing candidates as well as the winner?**
A checksum of winners alone attests to an outcome but not to the decision. If a
term that nearly won was displaced by an overlapping correction, the difference
lives in the candidate set, and only the full set records that
(`InputChecksum.java:26`).

**16. What exactly makes a run reproducible?**
Three independent things must match on replay: the input checksum, the rule
version, and the run's deterministic fingerprint. All three are checked and all
differences are collected rather than short-circuited, because one mismatch
report sends a reader into a second round of diagnosis for the same bug
(`ReproducibilityService.java:69`).

**17. Why does the engine take a `Clock` and read it once?**
Two reasons. A fixed clock makes a replay byte-identical, and reading it once
stamps every row of a run with the same instant. The run id is supplied by the
caller rather than generated, because generation would inject entropy into the
very record whose purpose is to be reproducible
(`FinancialTruthEngine.java:139`, `CalculationRun.java:31`).

**18. Why does a line that cannot be evaluated get no row at all?**
Because a combined row asserts a net variance, and there is no defensible net
variance without a readable discount entitlement. The omission is not silent: the
aggregator counts the line as unevaluated, the run's confidence drops, and the
component rows on the record say why (`FinancialTruthEngine.java:210`).

**19. Why is reconciliation asserted only sometimes?**
A net variance must equal `pricingVariance - discountVariance`, but that is only
provable when the entitlement was measured or the invoice granted no discount. A
line carrying a discount the contract never authorised is a genuine finding with
no component row to explain it, so the total is still reported — at MEDIUM
confidence, with the gap stated in words (`FinancialTruthEngine.java:253`).

**20. Why is specificity ranked before version?**
A negotiated rate for a customer is intended to override the contract's published
default. Ranking version first would let a later amendment to the *default* beat
the bespoke rate, and the agreed price would revert to list the moment the
default was touched (`TermSelector.java:59`).

**21. Why are both ends of an effective window inclusive?**
Because the half-open convention silently drops the last day of every price. A
duplicated boundary day is visible in the candidate set and is settled by the
documented tie-break; a dropped day is invisible and unfixable
(`EffectiveWindow.java:24`).

**22. Why is there no exchange rate anywhere?**
A cross-currency total would have to invent a rate, and an invented rate is the
one number in this product that cannot be audited. Totals are per currency, each
measuring its own coverage against the lines denominated in that currency
(`ImpactAggregator.java:30`).

**23. Why is `OpportunityImpact.isFavourable()` currently a decision, not a fact?**
The method tests for a *negative* amount, on the stated reading that a positive
impact is money the customer overpaid. That polarity is the opposite of the
conventional reading, and it decides whether a variance is claimed as recoverable
money or as an amount owed. ⚠ Review — see chapter 20's decision list before
implementing detection (`OpportunityImpact.java:103`).

**24. Why does a commercial rule refuse to be evaluated when it is unreadable?**
A threshold that cannot be computed must not degrade to zero, because zero would
fail every transaction that reached it while looking like a real rule. The
expression grammar is closed, so a stored expression can name nothing the
evaluator does not already implement (`RuleExpression.java:130`).

**25. Which parts of this system actually run today?**
`shared`, `platform`, the contract resolver, and the whole `financialtruth`
calculation core are real. Ingestion parsing, validation and security are real
but its controller and repositories are not. `financial`'s models and enums are
real; its normalizers, services, mappers' wiring, controllers and repositories
are not. `evidence`, `opportunity`, `value`, `identity`, `investigation`,
`reporting` and `processing` have specifications and enums, not behaviour. There
is no end-to-end path today. Chapter 20 gives the exact per-file inventory.

**26. Where does the tenant boundary actually get enforced?**
At the security layer, not at the request edge and not in each service. Reads go
through `SecurityContext.requirePrincipal()` and must satisfy
`isTenantResolved()` before touching tenant data
(`shared/security/SecurityPrincipal.java:78`). Today the filter that would bind
that context is a stub, so the boundary is designed but unenforced.

**27. Why does `SecurityContext` delegate to Spring's holder instead of keeping its own?**
So there is exactly one source of truth for request identity. It returns null
rather than a synthetic principal for anonymous or unauthenticated requests, so
each caller has to decide what anonymous means to it
(`shared/security/SecurityContext.java:32`).

**28. Can I add a field to a `canonical`/`toString` rendering?**
Only with care. These renderings feed stored checksums, so changing one changes
the digest of every future calculation. `CanonicalForm` renders its own text
rather than delegating to `toString()` for exactly this reason
(`EffectiveWindow.java:110`).

**29. Why does `hashCode` on `Money` avoid `Objects.hash`?**
It runs for every amount added to a hash collection during variance aggregation,
and `Objects.hash` allocates a varargs array on every call. The implementation
strips trailing zeros to stay consistent with the scale-insensitive `equals`
(`shared/domain/Money.java:216`).

**30. Is the module boundary actually enforced?**
No. `ModuleBoundaryTest` is a placeholder: the ArchUnit dependency is declared
but the rules are not written, so "no module imports another" is currently a
convention that only a reviewer enforces
(`src/test/java/com/fintech/cfo/architecture/ModuleBoundaryTest.java:21`).

---

## Reading paths

| To understand | Read, in order | Time |
|---|---|---|
| The money type and why it is guarded | ch 02, then `Money.java` | 15 min |
| How a variance becomes defensible | ch 08, then `FinancialTruthEngine.java` | 40 min |
| Which contract term applied, and why | ch 07, then `TermSelector.java` | 25 min |
| Why a past result can be replayed | ch 11, then `ReproducibilityService.java` | 20 min |
| Why rounding happens where it does | ch 08, then `RoundingPolicy.java` | 15 min |
| Where the tenant boundary is meant to sit | ch 04, then ch 15 | 30 min |
| Why no-double-counting is a code rule | ch 06, then `ImpactAggregator.java` | 20 min |
| What is not built yet | ch 20, this folder | 20 min |
| A full CSV-to-opportunity trace | ch 05, then ch 18 | 60 min |

## 6. Contracts & commercial terms

### Module goal

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

### File inventory

#### `controller/` — HTTP boundary (2 files, both reserved stubs)

| File | Goal |
| --- | --- |
| `controller/ContractController.java` | Reserved stub, HTTP pass pending. Intended entry point for asking what a contract says on a given date. Its governing constraints are already fixed by the engine: the as-of date must be caller-supplied rather than defaulted, and the tenant must come from the security principal rather than a request parameter. |
| `controller/ContractTermController.java` | Reserved stub, HTTP pass pending. Intended entry point for narrative clauses, and the place that must keep the two clause journeys apart — reading a stored clause as of a date versus returning a document-extraction *proposal* that has not been reviewed. |

#### `dto/` — wire shapes and resolution results (14 files, all built)

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

#### `enums/` — closed code sets mirroring the V5 VARCHAR columns (8 files, all built)

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

#### `extraction/` — reading clauses out of documents (4 files, all built)

| File | Goal |
| --- | --- |
| `extraction/ContractTermExtractor.java` | The port for pulling clauses out of contract text, declared in the consumer so a deterministic reader and a later document-interpreting adapter are interchangeable. Its output is proposals, never stored terms, because nothing extracted is trusted. |
| `extraction/ContractTermExtractionService.java` | The deterministic heuristic reader: narrow regexes over heading text and three date formats. Stateless and side-effect free, and deliberately capable of returning nothing — an empty list and a guess must not look alike to the reviewer. |
| `extraction/ExtractedContractTerm.java` | A clause candidate before acceptance, allowed to carry a missing description or a missing window because "we could not read this" and "this says nothing" have to be distinguishable. Carries a confidence so an author can triage rather than re-read everything. |
| `extraction/package-info.java` | Package rule: extraction is a pure transformation from text to a candidate type, stores nothing, and produces a value that must still satisfy every term invariant before it can be used. |

#### `model/` — value types mirroring the V5 tables (13 files, all built)

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

#### `repository/` — persistence boundary (3 files, all reserved stubs)

| File | Goal |
| --- | --- |
| `repository/CommercialRuleRepository.java` | Reserved stub, persistence pass pending. Intended access to `commercial_rules`; the query has to return organisation-wide and contract-scoped rules together and must not pre-filter by version or type, because the tie-break belongs in reviewable code rather than in SQL. |
| `repository/ContractRepository.java` | Reserved stub, persistence pass pending. Intended access to `contracts`, with tenant scoping in every signature and row mapping routed through the value type's constructor so the window constraint cannot be bypassed in memory. |
| `repository/ContractTermRepository.java` | Reserved stub, persistence pass pending. Intended access to `contract_terms`, narrowed only by tenant and contract. Its date predicate must use the same inclusive endpoint as the engine, or it will silently drop the last day of every clause. |

#### `service/` — the resolution engine (11 files, all built)

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

### Flow of journey

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

### Flow of implementation

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

### Key comments added

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
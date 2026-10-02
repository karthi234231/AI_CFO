## 5. Financial truth engine — expected vs actual

### Module goal

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

### File inventory

#### `calculator/` — the deterministic arithmetic (all built)

| File | Goal |
| --- | --- |
| `calculator/FinancialTruthEngine.java` | The composition root of the module's logic: sorts and validates the rule set, pins the rule-set version, builds a closed `RuleContext` per line, records one result row per rule, composes the authoritative combined net row per line, and aggregates impacts per currency. Owns the net-vs-components reconciliation check and the rule-status-to-confidence mapping. |
| `calculator/ExpectedAmountCalculator.java` | Turns contract terms into the amount that should have been charged: contracted gross, per-term discount entitlements, and the net. Owns one rounding policy for the whole expected side and refuses to invent a price, default a missing term to zero, or convert currencies. |
| `calculator/ActualAmountCalculator.java` | Reads what was actually charged off an invoice line, mirroring the expected-side normalisation and single rounding step exactly so the two sides of a comparison differ only in figures, never in method. |
| `calculator/VarianceCalculator.java` | Produces variances under the module's single sign convention and asserts the internal consistency guard: a reported net variance must equal the algebraic combination of its disjoint components, or the run fails. |
| `calculator/ImpactAggregator.java` | Rolls per-line combined variances up into one impact per currency. Accepts combined rows exclusively (rejecting component rows), never crosses currencies, sorts rows before summing, and downgrades a total's confidence when it omitted lines. |
| `calculator/package-info.java` | States the package rule: the two amount calculators together own the rounding policy, the variance calculator owns decomposition, and the aggregator owns honest totals. |

#### `model/` — the input snapshot and every result carrier (all built)

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

#### `rules/` — the pluggable variance checks (all built)

| File | Goal |
| --- | --- |
| `rules/FinancialRule.java` | The interface every commercial check implements. Deliberately free of framework annotations: a rule is a plain object with constructor-injected collaborators, so it can be exercised in a unit test with no container, database or clock. |
| `rules/RuleContext.java` | Everything one rule may see about one line: the line, an explicit as-of date, the currency, and the terms in force on that date. A closed view — a rule cannot reach another line, query contracts, or read a clock. |
| `rules/RuleEvaluationResult.java` | What one rule concluded, enforcing that the three findings — evaluated, not-applicable, incomplete-inputs — stay distinguishable and that the two latter carry no monetary figures at all. |
| `rules/PricingVarianceRule.java` (`PRICING_VARIANCE@1.0.0`) | Compares the invoiced unit price against the contracted unit price on the gross amount only. Raises when no contract price was in force or when currencies disagree; reports incomplete inputs for an unevaluable pricing type or an unusable quantity. |
| `rules/DiscountVarianceRule.java` (`DISCOUNT_VARIANCE@1.0.0`) | Compares the discount granted against the entitlement derived from the contracted gross, applying every effective term in the fixed order and clamping by each term's cap and by the gross. Disjoint from the pricing rule so no deviation is counted twice. |
| `rules/package-info.java` | States that rules are pure, that both shipped rules refuse to produce a figure they cannot justify, and why `RuleContext` and `RuleEvaluationResult` are final classes rather than records. |

#### `enums/` — the closed value sets (all built)

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

#### `service/` — lifecycle and reproducibility (all built)

| File | Goal |
| --- | --- |
| `service/CalculationService.java` | The application-facing entry point: run the engine, expose the checksum and rule-set version, and read back the authoritative combined rows and per-currency impacts. Infrastructure-free — the audit wiring it would need requires a JPA repository, so it is left to the composition root. |
| `service/CalculationRunService.java` | Owns the run lifecycle: `start` records checksum, rule version and period before any figure is produced; `complete` refuses to close a run with results from a different run id or different inputs; `fail` preserves the checksum and truncates the reason to the `VARCHAR(2000)` column. |
| `service/ReproducibilityService.java` | Turns "we believe it is reproducible" into "we have checked". Replays a stored snapshot under the original run id and compares checksum, rule version, result count, every result's canonical form and the run fingerprint — collecting all differences rather than short-circuiting. |
| `service/package-info.java` | States that every service takes its collaborators and a `Clock` where it needs time, and that no Spring or I/O appears in this package. |

#### `dto/` — the wire shape (all built)

| File | Goal |
| --- | --- |
| `dto/RunCalculationRequest.java` | The client request, with money modelled as decimal strings and parsed with `new BigDecimal(...)`. Deliberately carries no organization id: tenant scope must come from the authenticated principal, never from a request body. Contains the nested line, pricing-term and discount-term request records and their converters. |
| `dto/CalculationResponse.java` | One result row as reported, carrying its rule code and version because a figure without the version that produced it cannot be defended later. Nullable amounts are explicit, not incidental. |
| `dto/CalculationRunResponse.java` | A run as reported, exposing `inputChecksum`, `ruleVersion` and the deterministic fingerprint on the wire: a client that cannot see them cannot reproduce anything. |
| `dto/VarianceResponse.java` | A variance plus a derived `direction` so consumers need not re-derive the sign convention, and a `percentageOfExpected` that is omitted rather than sent as zero when the expected amount is zero. |
| `dto/FinancialImpactResponse.java` | The per-currency impact with its evaluated/unevaluated counts and its mandatory rationale, because a total whose limits are not stated cannot be defended. |
| `dto/AmountResponse.java` | An amount and its currency as two fields, matching the V6 `NUMERIC`/`CHAR(3)` column pairs and refusing to emit a non-null amount with a null currency. |
| `dto/package-info.java` | States that these are transport shape only and never compute, and that every optional field is explicitly `@Nullable` because "no expected amount was established" is a real answer. |

#### `repository/` — persistence (both are placeholders)

| File | Goal |
| --- | --- |
| `repository/CalculationRunRepository.java` | Intended home of the `calculation_runs` row: the input checksum, rule version and lifecycle timestamps. Documents the tenant-scoped lookup, the opaque-checksum rule, and the 2000-character failure-reason bound shared with `CalculationRunService`. |
| `repository/CalculationResultRepository.java` | Intended home of the per-run result rows. Documents the tenant-scoped, line-ordered read whose ordering the positional reproducibility comparison depends on, the null-means-unevaluated column rule, and append-only semantics so a re-run writes a new run rather than overwriting an audit trail. |

#### `controller/` — the HTTP boundary (both are placeholders)

| File | Goal |
| --- | --- |
| `controller/CalculationController.java` | Intended synchronous single-invoice entry point, the interactive counterpart to the batch job. Documents that tenant scope comes only from the principal, money arrives as decimal strings, no silent zero is rendered for an unevaluable line, and a missing contract price surfaces as a typed failure. |
| `controller/CalculationRunController.java` | Intended audit-facing read and reproduction endpoint. Documents that the response must carry its own proof, that reproducibility is returned as a reviewable verdict rather than thrown, that only authoritative runs are served, and that stored figures are never recomputed in place. |

#### `processing/financialtruth/` — the batch job (all four are placeholders)

| File | Goal |
| --- | --- |
| `processing/financialtruth/CalculationJobConfiguration.java` | Intended Spring Batch job and step definition. Documents that chunk size is the transaction boundary, that job parameters carry the as-of date and tenant filter so a re-run selects the same invoices, and that a restart must be idempotent by run id. |
| `processing/financialtruth/CalculationProcessor.java` | Intended per-item bridge from a batch item to the engine: assemble a `CalculationInput`, invoke the engine, return the completed run. Documents stable run ids, a job-parameter as-of date rather than `today()`, one item per invoice so no total spans a chunk boundary, and per-item failure rather than a fatal abort. |
| `processing/financialtruth/CalculationWriter.java` | Intended persistence of each run. Documents one transaction per run, fixed row order, null-preserving money columns, idempotence by run id, and the rule that a wrong stored figure is a finding to report rather than a value to correct in place. |
| `processing/financialtruth/CalculationJobLauncher.java` | Intended programmatic job entry point for operators and month-end orchestrators. Documents that parameters must be explicit and complete, that no business logic belongs here, and that relaunching with identical parameters is the supported resume path. |

### Flow of journey

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

### Flow of implementation

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

### Key comments added

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

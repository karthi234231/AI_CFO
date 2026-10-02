# 8. Economic opportunity lifecycle

## Module goal

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

## File inventory

Every file in `src/main/java/com/fintech/cfo/opportunity/`.

### Enums - `enums/` (10 files, all implemented)

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

### Model - `model/` (12 files, 8 implemented, 4 placeholders)

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

### Service - `service/` (5 files, all placeholders)

| Path | Goal of this file |
| --- | --- |
| `service/OpportunityDetectionService.java` | **Placeholder.** Named throughout the enums as the component that raises `FindingType`s and the three `OpportunityType`s, and that checks contributions partition the net impact. No code. |
| `service/OpportunityValidationService.java` | **Placeholder.** Named as the place duplicate detection, confidence ceilings and the validation gate belong. No code. |
| `service/OpportunityReviewService.java` | **Placeholder.** Named as the place a `ReviewDecision` is turned into a state change plus a written rationale. No code. |
| `service/OpportunityLifecycleService.java` | **Placeholder.** Named as the enforcement point for `legalSuccessors()`, for content preconditions ("a quantified record needs a calculation reference"), and for a required reason on backward moves. No code. |
| `service/OpportunityService.java` | **Placeholder.** Intended facade over the record for callers. No code. |

### Repository - `repository/` (3 files, all placeholders)

| Path | Goal of this file |
| --- | --- |
| `repository/OpportunityRepository.java` | **Placeholder.** Persistence for `opportunities`; per `module-implementation-rules.md` Â§11 left untouched until the persistence pass. |
| `repository/OpportunityReviewRepository.java` | **Placeholder.** Persistence for `opportunity_reviews`, queried newest-first per opportunity. |
| `repository/OpportunityLifecycleRepository.java` | **Placeholder.** Persistence for `opportunity_lifecycle_events`, the append-only domain history. |

### DTO - `dto/` (7 files, all placeholders)

| Path | Goal of this file |
| --- | --- |
| `dto/OpportunitySummaryResponse.java` | **Placeholder.** List-view projection: reference, title, type, status, priority, impact, owner. |
| `dto/OpportunityResponse.java` | **Placeholder.** Single-record projection including validation and lifecycle position. |
| `dto/OpportunityDetailResponse.java` | **Placeholder.** Full projection: impacts, findings, evidence, reviews, lifecycle events. |
| `dto/ValidateOpportunityRequest.java` | **Placeholder.** Body for the validate action. |
| `dto/ChallengeOpportunityRequest.java` | **Placeholder.** Body for the challenge action; carries the rationale the schema makes mandatory. |
| `dto/RejectOpportunityRequest.java` | **Placeholder.** Body for the reject action. |
| `dto/AssignOpportunityRequest.java` | **Placeholder.** Body for the assign action; must not accept an organization id. |

### Controller - `controller/` (3 files, all placeholders)

| Path | Goal of this file |
| --- | --- |
| `controller/OpportunityController.java` | **Placeholder.** Tenant-scoped read endpoints over opportunities. |
| `controller/OpportunityReviewController.java` | **Placeholder.** Validate, challenge, reject endpoints. |
| `controller/OpportunityAssignmentController.java` | **Placeholder.** Assign and reassign endpoints. |

---

## Flow of journey

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

### Full status transition table

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

### Validation status, the other axis

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

## Flow of implementation

### The record: one object, deliberately split into many

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

### Input form versus stored form: `AffectedTransactionRef` and `from()`

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

### State machine invariants

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

### Human-in-the-loop gates

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

### Scoring, and why no ordinals

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

### Money and lineage

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

### Determinism

Timestamps (`createdAt`, `capturedAt`, `evaluatedAt`) are required constructor parameters rather
than `Instant.now()` calls, so a detection re-run over the same inputs produces the same rows.
`CodedEnum.normalise` upper-cases with `Locale.ROOT`, because case mapping is locale-sensitive and
a record read on a differently configured node must resolve to the same variant it was written as.
`fromCode` fails loudly on an unrecognised code and additionally checks the code against the V8
column width, so an over-long code surfaces at the boundary that owns the schema contract instead
of being truncated at write time and read back as an unknown status.

### Known gaps

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

## Key comments added

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

## Verification

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
## 9. Value realization & outcome tracking

### Module goal

`com.fintech.cfo.value` closes the loop. Every other module produces a claim: the calculation engine
produces a variance, the opportunity module produces an approved Economic Opportunity Record, the AI
layer produces an explanation. This module is where a claim either becomes money or does not. It owns
five V9 tables — `action_plans`, `action_executions`, `outcomes`, `realized_values` and
`value_attributions` — and the closed status and method sets that govern them.

It exists because a detection engine that cannot be scored is a machine for generating plausible
numbers. The product's core claim is that it finds recoverable money; the only way that claim is ever
tested is by walking the whole path to the bank and comparing what was predicted against what arrived.
So this module records commitment (a plan), attempt (an execution), observation (an outcome), the
counting decision (a realized value), and the reasoning that links that money back to the claim which
predicted it (an attribution). Each step is a separate row, deliberately, and the separations are the
design.

**Measurement and counting are different events.** `outcomes` says what happened and how sure we are;
`realized_values` says how much of it may be counted. An invoice was credited, the credit was issued,
the cash landed — any two without the third. Collapsing them would make a correction to a measurement
silently restate an accounting decision, and would erase the gap between what was measured and what was
recovered, which is itself the finding. **A reversal is a row, never an edit.** `REVERSED` is a status
on a new row with its own amount, because the question an audit of realized value exists to answer is
whether money was counted and later withdrawn — and mutating `amount` in place leaves no trace of
either. **Attribution is explicit and states its own method.** There is no default and no inference,
because an automatically derived link manufactures the evidence the layer is supposed to test.

A structural caveat that shapes everything below: of the 24 files in this slice, **1 carries real code
(`enums/CodedEnum.java`) and 23 are unimplemented placeholders** — every controller, DTO, model,
repository and service. The contracts those placeholders now document are derived from
`V9__create_value_tracking.sql`, from `CodedEnum`'s own list of the columns and codes it guards, and
from the module-boundary rules. The journeys below describe the contract the schema and the one real
file encode, not a path that executes today.

### File inventory

#### `enums/` — closed code sets (one built, four placeholders)

| File | Goal |
| --- | --- |
| `enums/CodedEnum.java` | **Built.** The shared contract for the closed sets whose variant is also the stored column value: `code()`, `normalise` (trims, upper-cases with `Locale.ROOT`, refuses blank) and `requireColumnWidth`. Resolves strictly — an unknown code throws rather than defaulting, because a status this module does not recognise means schema and code diverged, and guessing would report a realized amount whose provenance cannot be reconstructed. A deliberate local copy of the identical `financialtruth.enums` contract: value must not depend on financial-truth, and the alternative is a dependency this pure module has no reason to carry. |
| `enums/ActionStatus.java` | Intended `PLANNED` / `IN_PROGRESS` / `DONE` / `CANCELLED` lifecycle for `action_plans.status`, separate from `OpportunityStatus` so that "approved but never started" stays reportable. `DONE` and `CANCELLED` are terminal; `CANCELLED` is a first-class state rather than a deleted row so abandoned work remains countable. |
| `enums/AttributionMethod.java` | Intended `DIRECT` / `INCREMENTAL` / `PROPORTIONAL` / `ESTIMATED` set for `value_attributions.attribution_method`. Ordered by `isObservation()` and `requiresStatedBasis()` rather than by ordinal, so a modelled estimate cannot drift into a board-level number by accident of declaration order. |
| `enums/OutcomeStatus.java` | Intended `PENDING` / `CONFIRMED` / `DISPUTED` / `REVERSED` for `outcomes.status`. No schema default, matching the migration: an outcome row must arrive with a deliberate stance because `measured_amount` is `NOT NULL` and will always carry a number. `CONFIRMED` and `REVERSED` are terminal. |
| `enums/RealizationStatus.java` | Intended `REALIZED` / `PARTIALLY_REALIZED` / `NOT_REALIZED` / `REVERSED` for `realized_values.realization_status` — the only status that decides whether a figure may be summed into a realized total. No schema default, for the same reason: a defaulted status would attach a countable figure to an unstated reasoning. |

#### `model/` — the five V9 tables (all placeholders)

| File | Goal |
| --- | --- |
| `model/ActionPlan.java` | The hinge of the module: what will be done, by whom, against which system, by when. Documents why `expected_value` is deliberately *not* `opportunities.impact_amount` — the gap between the analysis's prediction and the doer's expectation is either overclaiming or under-claiming, and collapsing them destroys the ability to see it — and why `owner_id`/`due_at` are nullable so an unowned approved opportunity is visible rather than hidden by a placeholder. |
| `model/ActionExecution.java` | One *attempt* at a plan, which is why this table is separate: remediation really does fail, and overwriting the plan per try would make "we tried twice and recovered nothing" — the finding that changes recovery strategy — unstatable. Documents why `status` has no default while `executed_at` does. |
| `model/Outcome.java` | What actually happened, and how sure the record is of it. Documents the `DATE` vs `TIMESTAMPTZ` split: a recovery lands on a business date and must line up with an accounting period, while the row's own creation is an operational fact with a time of day. Records why `action_execution_id` is nullable — a supplier may issue a credit unprompted, and refusing to record that leaves the money invisible. |
| `model/RealizedValue.java` | The payout row: the only place a figure is asserted to be real money that arrived. Documents why `opportunity_id` is denormalized even though `outcome_id` implies it (the realized totals a tenant sees are always grouped by opportunity, and `ix_realized_values_opp` serves them), and why a reversal is a new row rather than a decrement. |
| `model/ValueAttribution.java` | The reasoned link from counted money back to the claim that predicted it — what converts a measurement into evidence about a model. Documents the reasoning behind V9's only database-level invariant (`ck_value_attributions_non_negative`), and the module's own harder one: the sum of attributions against a realized value must equal it exactly. |

#### `repository/` — query semantics (all placeholders)

| File | Goal |
| --- | --- |
| `repository/ActionRepository.java` | Plans and their executions together, as one aggregate. Documents the duplicate-live-plan guard (two open plans for one opportunity is how a recovery is pursued and counted twice), the `ix_action_plans_owner` prefix argument, and why undated plans must sort as "no date" rather than as the epoch. |
| `repository/OutcomeRepository.java` | Scoped strictly to what was *observed*, so it can never be the thing that answers "may this be counted". Documents the `DESC` index argument, half-open date bounds, and why sums happen in Java rather than in SQL: a SQL `SUM` cannot enforce a currency check or raise this module's business-rule exception. |
| `repository/RealizedValueRepository.java` | Realized values *and* the attributions explaining them, together on purpose — an `attributed_amount` alone is a number with no scale, paired with the realized amount it is a checkable statement. Documents the status filter and currency argument baked into the total signature, and why every method filters `organization_id` even where the index omits it. |

#### `service/` — the invariants (all placeholders)

| File | Goal |
| --- | --- |
| `service/ActionService.java` | Owns `action_plans` + `action_executions` and transitions both together. Documents why there is no generic "update status": a free-form update would let a caller move `PLANNED` → `DONE` with no execution row, producing the completed-looking record that makes a realized figure unreconstructible. Never touches money. |
| `service/OutcomeService.java` | The two checks that keep the middle honest: an outcome needs provenance (a measurement method or a recorded execution) and may not exceed the claim it settles. Documents why clamping is specifically refused — a clamped outcome reports a recovery nobody made, silently, in the one field the product exists to get right. |
| `service/RealizedValueService.java` | The counting gate. Only `CONFIRMED` outcomes may back a realized value; the counted amount may not exceed the measured; amounts are magnitudes with direction carried elsewhere. Documents that it never infers attribution — a counted amount that cannot be traced to a claim is real money that teaches nothing, and inferring the link would manufacture the evidence the module requires. |
| `service/ValueAttributionService.java` | Attribution method selection from strongest to weakest, what each requires, and the no-double-counting reconciliation. The one service where a plausible number with unsound reasoning is worse than no number. |

#### `controller/` — HTTP boundary (both placeholders)

| File | Goal |
| --- | --- |
| `controller/ActionController.java` | Intended workflow boundary exposing transitions as named operations (`/start`, `/complete`, `/cancel`) rather than a generic `PATCH`. Documents why no realized-value or attribution endpoints belong here. |
| `controller/OutcomeController.java` | Intended measurement boundary that deliberately stops short of counting — realizing an amount is a separate accounting decision with its own invariants, and merging them would let one request book a portfolio number. |

#### `dto/` — wire contracts (all placeholders)

| File | Goal |
| --- | --- |
| `dto/CreateActionRequest.java` | Plan creation payload. The absences are the design: no `organizationId`, no `status`, no `version`, no timestamps. |
| `dto/ActionResponse.java` | Work-queue projection. Includes `version` (so a queue is concurrently editable) and a `Money`, never a bare number. Embeds no executions or outcomes. |
| `dto/RecordOutcomeRequest.java` | Measurement payload carrying the bounds the service must enforce, and documenting the asymmetry with `CreateActionRequest`: status cannot be implied by creating a plan, but recording an outcome says nothing about whether it is confirmed. |
| `dto/OutcomeResponse.java` | Outcome projection. Status travels as a stable code, never an ordinal, and inlines no `RealizedValue`. |
| `dto/RealizedValueResponse.java` | Countable-recovery projection. `realizationStatus` is first-class so aggregation is a deliberate filter rather than an assumption; documents that the sum of all rows is *not* the realized value. |

### Flow of journey

Approved opportunity → action plan → execution → realized value → recorded outcome, with the
attribution reasoning that ties the money back to the claim.

1. **An opportunity reaches `APPROVED`.** Upstream, in the opportunity module: a variance was found,
   quantified, validated and authorised. At this point money exists only as a figure on a record. The
   opportunity sits at `APPROVED` precisely because "authorised" and "started" are different facts,
   and merging them makes the number of approvals that never became work unreportable.

2. **A plan is raised against it.** `ActionController` receives a `CreateActionRequest` — which
   contains no `organizationId` and no `status`, because tenancy comes from the authenticated principal
   and a create implies its own starting state. `ActionService` checks the opportunity is `APPROVED`,
   then checks `ix_action_plans_opp` for an existing live plan and refuses a second one. It writes an
   `action_plans` row with `expected_value` set to what the *doer* expects to recover — a different
   number from `opportunities.impact_amount`, and the gap between them is the useful finding. Status
   defaults to `PLANNED`. An `AuditService` event records the commitment.

3. **An attempt is opened and worked.** `POST /api/actions/{id}/start` transitions the plan to
   `IN_PROGRESS` and opens an `action_executions` row in the same transaction, with `executed_at` taken
   from the injected clock rather than the client — a backdated execution timestamp would silently move
   money into a closed accounting period. A rejected credit note leaves the execution row in place with
   a required note explaining why; the plan moves to `CANCELLED`, which is a state rather than a
   deletion so "how much approved work was abandoned" stays countable.

4. **What happened is measured.** `OutcomeController` takes a `RecordOutcomeRequest`.
   `OutcomeService` enforces the two bounds that matter: the request must carry provenance (a
   `measurement_method`, or an `action_execution_id` tying it to a recorded attempt), and
   `measured_amount` must not exceed the opportunity's impact — an outcome above the claim it settles
   means the measurement is wrong or the claim was under-scoped, never that the larger figure is better
   news. It is refused as a `BusinessRuleException` rather than clamped. The row is written with
   `measured_at` as a business `DATE`, distinct from `created_at`, and `status` stated deliberately
   because the column has no default.

5. **A counting decision is taken — separately.** `RealizedValueService` books an amount only from a
   `CONFIRMED` outcome: `PENDING` and `DISPUTED` cannot be counted, since a pending measurement is a
   claim and a contested one is a disagreement. The counted amount may not exceed the measured amount;
   the excess is the under-recovery finding, so it is refused rather than truncated. The row carries
   `realization_status` and a business `realized_at` date. Totals sum `REALIZED` and
   `PARTIALLY_REALIZED` only, in one currency, in Java.

6. **The money is attributed back to the claim.** `ValueAttributionService` records one or more
   `value_attributions` rows, each naming its `attribution_method` — `DIRECT` when a specific ledger row
   is traceable to an entity the opportunity named, `INCREMENTAL` where only the excess over the
   expected value is attributable, `PROPORTIONAL` where shared money is split on a basis recorded in
   `notes`, `ESTIMATED` where nothing measures it. Confidence is set explicitly rather than left to the
   column's `HIGH` default. Then the reconciliation runs: the `attributed_amount`s pointing at one
   realized value must sum to exactly that value's amount, or the write fails.

7. **A reversal, when it comes.** A clawback or refund is a new `outcomes` row or a new
   `realized_values` row with `REVERSED`, never an edit. Totals subtract it as its own row. The amount
   first booked and the amount it later disappeared by both remain readable.

### Flow of implementation

**The attribution model.** Five questions are kept apart rather than collapsed, and each table answers
exactly one:

| Question | Table | Status set |
| --- | --- | --- |
| What was committed? | `action_plans` | `ActionStatus` |
| What was attempted? | `action_executions` | `ActionStatus` |
| What happened, and how sure? | `outcomes` | `OutcomeStatus` |
| What may be counted? | `realized_values` | `RealizationStatus` |
| Why does it belong to this claim? | `value_attributions` | `AttributionMethod` + confidence |

The consequence is that `OutcomeStatus` and `RealizationStatus` are separate enums answering separate
questions, and they disagree in exactly the cases worth noticing: a `CONFIRMED` outcome may be only
`PARTIALLY_REALIZED`, and a `REALIZED` amount may rest on a `PENDING` outcome because a settlement
cleared first. One enum for both would force one of the two questions to be answered wrongly.

**Realization invariants.**

- Only a `CONFIRMED` outcome may back a realized value.
- The counted amount may never exceed the measured amount, and the measured amount may never exceed the
  claim. Neither bound is clamped — both are refused, because silent truncation destroys the very
  discrepancy the module exists to surface.
- `realized_values.amount` is a non-negative magnitude; direction lives on the opportunity's impact
  sign. V9 enforces non-negativity *only* on `value_attributions.attributed_amount`
  (`ck_value_attributions_non_negative`), so this column's sign discipline is the module's
  responsibility and is asserted in the service rather than assumed.
- Currencies must agree across the entire chain — opportunity, outcome, realized value, attribution.
  `Money` refuses mixed-currency arithmetic outright, which is the desired behaviour: an unconvertible
  currency means a missing FX component, and inventing one here would produce totals that cannot be
  reconstructed from recorded inputs.
- `REVERSED` subtracts as its own row; totals sum `REALIZED` and `PARTIALLY_REALIZED` only.
- **Attributions reconcile exactly.** The `attributed_amount`s against one realized value must sum to
  its amount. This is the invariant with the most weight in the module: the same money counted twice is
  the error this layer was built to make impossible, V9 constrains nothing about it, so the check is
  entirely the module's. It fails as a `BusinessRuleException`, not a warning — a reconciliation that is
  only logged is one nobody acts on. Exact equality, not a tolerance: amounts are already at
  `NUMERIC(20,4)` and rounded once, so a residual is real drift, not a rounding artefact.

**Design decisions and why the key lines exist.**

- **`action_executions` is a separate table from `action_plans`,** and the outcome's reference points
  *upward* from measurement to attempt (`outcomes.action_execution_id`), never back. Remediation fails
  in reality; one plan needs many attempts, and "recovered on the second attempt" has to be
  representable rather than overwritten.
- **Denormalized `opportunity_id` on `realized_values` and `value_attributions`,** even though
  `outcome_id` implies it. The totals a tenant sees are always grouped by opportunity, and that is what
  the index serves; resolving through the outcome would also silently exclude realizations with no
  outcome row — exactly the ones a completeness check needs to find.
- **`DATE` for money, `TIMESTAMPTZ` for bookkeeping.** `measured_at` and `realized_at` are business
  dates because money is recognized on a date and must line up with accounting periods;
  `attributed_at` is a timestamp because it is a judgement made at a moment, and that ordering
  (`ix_value_attributions_opp ... attributed_at DESC`) is what an audit of a claim's reasoning history
  needs. `created_at` timestamps come from the injected `DateTimeUtils`, never a client, so no row can
  be backdated.
- **Nullable where absence is a real, reportable state:** `owner_id`, `due_at`, `action_execution_id`,
  `outcome_id`, `realized_value_id`. An unowned approved opportunity is alarming and should be
  visible; an unprompted supplier credit is real money; a realization booked from a bank confirmation
  never had an outcome. Each would become invisible if forced to a placeholder.
- **Free text where the set is open-ended** (`action_type`, `target_system`, `outcome_type`) and a
  coded enum where this module owns the set (`status`, `realization_status`,
  `attribution_method`, `confidence`).
- **Strict code resolution, never a default.** `CodedEnum.normalise` and `requireColumnWidth` fail
  loudly: an unrecognised status means schema and code have diverged, and guessing would report a
  realized amount whose provenance cannot be reconstructed — the one failure that cannot be caught
  downstream, because the number still looks like money.
- **Tenant scoping in every query,** including `ix_realized_values_opp`, whose index omits
  `organization_id`. An unfiltered read there is a cross-tenant leak, not merely a slow query. A record
  not found *within* the caller's tenant reports as not-found, never as forbidden.
- **Sums in Java, not in SQL.** A database `SUM` cannot enforce the currency equality check, cannot
  raise this module's business-rule exception, and cannot distinguish a deliberate total from an
  accidental one during an audit. `Money` also makes totals scale-insensitive and order-independent,
  so re-running a report over the same rows gives the same figure.

### Key comments added

- **`enums/CodedEnum` (already written, preserved verbatim):** the shared `code()` / `normalise` /
  `requireColumnWidth` contract, its list of the nine V9 columns it guards, and why resolution fails
  loudly rather than defaulting.
- **Status and method enums:** the group each set holds with every constant's meaning, the schema
  default (or deliberate absence of one) for its column, which states are terminal and why a finished
  record must not be silently reopened, and why ordering is expressed by an explicit predicate rather
  than `ordinal()`.
- **Models:** per-column rationale for the nullable/no-default columns, the `DATE` vs `TIMESTAMPTZ`
  split, why `expected_value` is not `impact_amount`, why `opportunity_id` is denormalized, why
  reversal is a row, and — on `ValueAttribution` — why the database-level non-negativity check sits on
  the attribution table rather than on realized values.
- **Repositories:** the index each query serves and why it is ordered `DESC`, half-open business-date
  bounds, the tenant predicate required even where the index omits it, the status filter and currency
  argument baked into the total signature, and why sums are computed in Java rather than pushed into
  SQL.
- **Services:** attribution method selection and what each method requires; the provenance and
  ceiling bounds on outcomes; why clamping and silent truncation are refused rather than offered; the
  exact-equality reconciliation and why a residual is drift rather than a rounding artefact; why
  `RealizedValueService` never infers an attribution; and the tenancy, concurrency and audit rules each
  service applies.
- **Controllers and DTOs:** why transitions are named operations rather than a generic status `PATCH`,
  why measurement stops at the controller that records it, and which fields are deliberately absent
  from each request (`organizationId`, `status`, `version`, client timestamps).

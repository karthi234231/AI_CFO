## 10. AI layer — extraction & explanation

### Module goal

`com.fintech.cfo.ai` is the only place in the system where a language model is allowed to
run, and it is deliberately given the narrowest job the product can survive: **read
clauses, and write prose**. It does two things. It extracts commercial terms out of
contract documents (`extraction/`, behind `DocumentExtractionPort` and `LlmPort`), and it
explains an already-computed financial result in plain English (`service/`). It never
computes, and it never holds money.

That distinction is the module's whole thesis, and it is enforced structurally rather than
by asking the model nicely:

- **No type in this module carries a `Money`.** An AI artefact holds a `SourceReference`
  and an input checksum, never an amount. There is nowhere for a model-computed figure to
  live.
- **The number fence.** An explanation may only cite figures that appeared in the
  deterministic context it was handed. `AiGuardrailService.findForeignNumbers` diffs the
  numbers in the reply against the numbers in the prompt, and anything left over makes the
  artefact `DISPUTED` rather than `CONFIRMED`.
- **The schema fence.** On the extraction side, `StructuredExtractionValidator` rejects any
  field whose name smells like money (`amount`, `discount_percent`, `variance`, …) and any
  `termType` outside a fixed vocabulary — so a model cannot smuggle a computed quantity in
  as a "clause".
- **Trust is never assumed.** Extracted terms leave the validator as `IN_REVIEW`, never
  `CONFIRMED`. `Confirmed` is a verdict the guardrails reached, not a default.

Two structural facts shape the rest of this document. First, the module depends **outward**
only: it imports `com.fintech.cfo.shared.**` and one `platform` port
(`ObjectStoragePort`). It never imports `contract` or `opportunity`, so both vocabularies
it needs (`ContractTermType`, the coded-enum contract) are hand-copied and documented as
duplicates. Second, no other module in the repository currently imports `ai` — a search of
`src` for `com.fintech.cfo.ai` outside this package returns nothing. The seams for
consumers exist and are named; the composition root that injects them is integration-milestone
work, not this pass.

### Implementation status

Plainly: the logic is built, the boundary is not.

**Built (30 files).** All of `enums/` (the three state sets plus `CodedEnum` and its
package doc), all of `model/` (six records), all of `extraction/` (the extraction engine,
its validator and the run result), all of `service/` (the explanation orchestrator, the
context builder, the guardrail set and the contract-interpretation use case), all of `dto/`
(four request/response records), the three outbound **port interfaces** in `client/` plus
the client `package-info.java`, and six `package-info.java` files. Everything in this list
is real, exercised-by-contract code: the guardrails are pure functions, the validator is a
pure function over a string, and both orchestrators take their ports by constructor
injection, so all of it is testable with no network and no model.

**Stubbed (7 files).** All three HTTP client adapters — `LlmClient`, `EmbeddingClient`,
`DocumentExtractionClient` — are generated placeholders containing only a `TODO`
comment and an empty class, left byte-identical per rules §11 ("do not write any HTTP/AI
client"; the adapters belong to the transport pass). Both controllers —
`ContractInterpretationController`, `ExplanationController` — are the same kind of
placeholder and are likewise untouched. Both PDF helpers — `pdf/AiPdfService` and
`pdf/PdfExtractionService` — are placeholders too; the local OpenPDF implementation of
`DocumentExtractionPort` that `DocumentExtractionPort`'s Javadoc refers to does not exist
yet, so today there is no working `DocumentExtractionPort` implementation in the tree and
the fetch-and-parse branch of `ContractInterpretationService` is unreachable until the
transport pass supplies one. The inline-`contractText` branch works without it.

**Both prompt templates are empty.** `resources/prompts/contract-term-extraction.txt` and
`resources/prompts/opportunity-explanation.txt` each contain a title line and
`TODO: Add prompt/template content.` That is a real gap, not an oversight of this pass:
`ContractExtractionService.loadTemplate` and `AiContextService.loadTemplate` will both
throw (`ValidationException` / `IllegalStateException`) on a missing or empty template, so
both model-calling paths fail closed until the prompts are authored. They were left
byte-identical deliberately: the renderer performs only `{{TOKEN}}` substitution and does
not strip comments, so a "documentation" comment added to a `.txt` template would be sent
to the model verbatim. The contract those files must satisfy is documented in
`AiTaskType`'s Javadoc and in `Flow of implementation` below.

### File inventory

#### `client/` — outbound ports and their (stubbed) HTTP adapters

| File | Goal |
| --- | --- |
| `client/LlmPort.java` | The single verb this module is licensed to ask of a model: complete a prompt, capped in output tokens, at a caller-chosen temperature. Orchestrators depend on this interface and never on the HTTP client, so "summarise this" or "compute this" has no route to a transport. |
| `client/EmbeddingPort.java` | Embeds text for relevance ranking only. Justified as safe here because a vector is a position, not a statement — similarity can select which clauses fit the budget but cannot produce or favour a number. |
| `client/DocumentExtractionPort.java` | Turns raw document bytes into `ExtractedDocument` (text + SHA-256 + page count). Intended to have two interchangeable adapters: a local OpenPDF one and a remote one. |
| `client/LlmClient.java` | **Placeholder, untouched.** HTTP adapter for `LlmPort`; written in the transport pass. |
| `client/EmbeddingClient.java` | **Placeholder, untouched.** HTTP adapter for `EmbeddingPort`; written in the transport pass. |
| `client/DocumentExtractionClient.java` | **Placeholder, untouched.** HTTP adapter for `DocumentExtractionPort`; written in the transport pass. |
| `client/package-info.java` | States the package rule: depend on ports, never on clients, so every guardrail and cost rule is unit-testable with no network. |

#### `controller/` — HTTP boundary (both placeholders, untouched)

| File | Goal |
| --- | --- |
| `controller/ContractInterpretationController.java` | **Placeholder, untouched.** Intended endpoint that delegates `InterpretContractRequest` to `ContractInterpretationService`. |
| `controller/ExplanationController.java` | **Placeholder, untouched.** Intended endpoint that delegates `ExplainOpportunityRequest` to `AiExplanationService`. |

#### `dto/` — request and response bodies (built, not yet wired to a controller)

| File | Goal |
| --- | --- |
| `dto/InterpretContractRequest.java` | Addresses a contract by id plus *either* an object-storage key *or* inline text, never bytes. The inline branch exists so the extraction path can be driven in tests without OpenPDF. |
| `dto/ExplainOpportunityRequest.java` | Carries the deterministic context as text — the model's only window onto the numbers — plus an optional focus and optional candidate supporting terms to rank. |
| `dto/ContractInterpretationResponse.java` | Returns the extracted terms, an optional prose summary, the guardrail verdict, the input checksum and the run id, so a returned interpretation is reproducible. |
| `dto/ExplanationResponse.java` | Envelope for an explanation: the opportunity it explains, the `AiExplanation` (prose + verdict), the status and the input checksum. |
| `dto/package-info.java` | Records that these bodies deliberately carry no organization field: tenant scope comes from the authenticated principal, never from a request body. |

#### `enums/` — closed code sets (built)

| File | Goal |
| --- | --- |
| `enums/AiValidationStatus.java` | Sealed interface of five records: `PENDING`, `IN_REVIEW`, `CONFIRMED`, `DISPUTED`, `REJECTED`. Sealed rather than a plain enum so every site that switches on a verdict is compiler-checked for exhaustiveness, and so `DISPUTED` (content a human must review) stays distinct from `REJECTED` (no content at all). |
| `enums/AiProcessingStatus.java` | Lifecycle of a run (`PENDING`…`SKIPPED`). A plain enum because no variant carries behaviour; free-text reasons deliberately live on `ExtractionResult`/`AiAnalysis`, not on the code set. |
| `enums/AiTaskType.java` | The two permitted kinds of AI work, each bound to its prompt resource path. Keeps a template from ever being matched to the wrong task by a string typo. |
| `enums/CodedEnum.java` | The module-local contract for a persisted code: stable string code, case/whitespace normalisation on read, and a column-width assertion so an over-wide code fails loudly instead of truncating silently. |
| `enums/package-info.java` | Documents the sealed-interface-vs-enum convention and why `CodedEnum` is duplicated here rather than shared. |

#### `extraction/` — structured extraction (built)

| File | Goal |
| --- | --- |
| `extraction/ContractExtractionService.java` | The extraction engine: budget-checks the input, renders the prompt with only a safe contract id, calls the model at temperature 0.0, and refuses outright on refusal. It reads clauses; it does not compute money. |
| `extraction/StructuredExtractionValidator.java` | Parses the reply as JSON, enforces the allowed term-type vocabulary, and rejects any monetary field name before it is read. This is the extraction-side expression of the "AI never produces numbers" rule. |
| `extraction/ExtractionResult.java` | The output of one run: terms, both statuses, output tokens for cost, the input checksum, the source reference and an optional reason. Carries no money and no document. |
| `extraction/package-info.java` | States that the engine lives here and the transport does not, so guardrails and validation are testable against `LlmPort` without a network. |

#### `model/` — the immutable carries (built)

| File | Goal |
| --- | --- |
| `model/AiAnalysis.java` | The aggregate artefact handed to the rest of the system: terms, optional explanation, both statuses, input checksum, source reference, timestamp. It is the type-level fence — no `Money` field exists, so no consumer can mistake it for financial truth. |
| `model/AiExplanation.java` | Model prose plus the guardrail's verdict on it. The fence lives in the status, not the text: the text is the model's words and stays untrusted. |
| `model/ExtractedCommercialTerm.java` | One clause as the model reported it: type, text, page number for traceability, and a status that is `IN_REVIEW` on first production. |
| `model/ExtractedDocument.java` | Port DTO for parsed document text with its SHA-256 and page count. Transient by design: only the checksum is ever persisted. |
| `model/LlmMessage.java` | One chat message. Bundling role and content in a record makes a malformed request structurally impossible, which matters because the `SYSTEM` role is where the guardrail instructions live. |
| `model/LlmResponse.java` | The raw model reply plus token count and refusal flag. Untrusted by contract; a refusal must carry a reason so the run is auditable. |
| `model/package-info.java` | Records the module's central type claim: none of these records can hold money. |

#### `pdf/` — document rendering and parsing (both placeholders, untouched)

| File | Goal |
| --- | --- |
| `pdf/PdfExtractionService.java` | **Placeholder, untouched.** Intended local OpenPDF implementation of `DocumentExtractionPort`. |
| `pdf/AiPdfService.java` | **Placeholder, untouched.** Intended rendering of an AI artefact to PDF for reviewers. |

#### `service/` — orchestration and guardrails (built)

| File | Goal |
| --- | --- |
| `service/AiExplanationService.java` | Produces an explanation and enforces the financial-truth rule in code: derives the allowed-number set from the user message, calls the model at temperature 0.0, and downgrades to `REJECTED`/`DISPUTED` on refusal or foreign numbers. |
| `service/AiContextService.java` | Assembles prompts and nothing else. Keeps instructions in the system message and data in the user message, and templates only trusted ids — the structural half of the injection defence. Also ranks supporting terms by cosine similarity to stay inside the token budget. |
| `service/AiGuardrailService.java` | The stateless check set: input-size budget, refusal detection, injection heuristics, number extraction with canonicalisation, and `findForeignNumbers` — the thesis fence. |
| `service/ContractInterpretationService.java` | The contract-interpretation use case: resolves a stored document or inline text, parses it through the port, runs extraction, and returns an `AiAnalysis` with provenance rather than raw model output. |
| `service/package-info.java` | States the package rule and is explicit that injection/refusal detection are review signals, while the foreign-number check is the one hard block on the thesis. |

#### `resources/prompts/` — the two prompt templates (both placeholders)

| File | Goal |
| --- | --- |
| `prompts/contract-term-extraction.txt` | **Placeholder, untouched.** Must become the extraction system prompt: the clause vocabulary, the requirement to emit a JSON array of `{termType, description, pageNumber}` objects, and the instruction to emit no monetary field. It contains only `{{CONTRACT_REFERENCE}}` as a placeholder, and the document text is deliberately never substituted into it. |
| `prompts/opportunity-explanation.txt` | **Placeholder, untouched.** Must become the explanation system prompt: describe the supplied deterministic result in prose, restate only figures present in the context, and compute nothing. It contains only `{{FOCUS}}` and `{{OPPORTUNITY_ID}}`; the context text is deliberately never substituted into it. |

### Flow of journey

#### Contract-term extraction

1. **A caller asks for a contract to be interpreted.** *Planned.* No endpoint exists —
   `ContractInterpretationController` is a placeholder. The entry point today is
   `ContractInterpretationService.interpret(org, InterpretContractRequest)` called
   directly.
2. **The source is recorded as provenance.** *Implemented.* A `SourceReference` of type
   `CONTRACT` is built with the contract id and, when the document was fetched, the upload
   key (`ContractInterpretationService.java:53`).
3. **The document text is resolved.** *Implemented.* Inline `contractText` short-circuits
   the fetch; otherwise the bytes come from `ObjectStoragePort.retrieve` and are parsed via
   `DocumentExtractionPort` (`ContractInterpretationService.java:68-86`). The stream is read
   and discarded inside the call, so no document is ever held on an artefact.
   *Gap:* no `DocumentExtractionPort` implementation exists yet, so the fetch branch raises
   `IllegalStateException` today.
4. **The input is budgeted and checksummed.** *Implemented.* SHA-256 over
   `ai/extraction:<org>:<source>:<text>`; over 12 000 characters the run returns `SKIPPED`
   with a reason instead of calling a model (`ContractExtractionService.java:69-77`).
5. **The prompt is built with the injection fence in place.** *Implemented.* The system
   message is the rendered template with only `{{CONTRACT_REFERENCE}}` substituted; the
   document text goes in as a separate `USER` message
   (`ContractExtractionService.java:83-87`).
6. **The model is called at temperature 0.0** with a 2048-token output cap.
   *Implemented.* A refusal short-circuits to `FAILED`/`REJECTED`
   (`ContractExtractionService.java:90-95`).
7. **The reply is schema-validated.** *Implemented.*
   `StructuredExtractionValidator.validate` parses JSON, rejects a non-array, rejects
   monetary field names, rejects unknown `termType`s, and stamps every surviving term
   `IN_REVIEW`.
   *Fails closed today:* the template file is a TODO placeholder.
8. **The run is returned as `ExtractionResult`** with `SUCCEEDED` / `IN_REVIEW`, the
   output-token cost and the checksum. *Implemented.*
9. **The interpretation response is assembled**, reusing the run id as the analysis id.
   *Implemented* at the service level; the HTTP exposure is *planned*.

#### Opportunity explanation

1. **A caller submits a deterministic context to be explained.** *Planned endpoint,* real
   request record. `ExplainOpportunityRequest` requires a non-blank `context` and carries
   the opportunity id, an optional `focus` and optional `supportingTerms`.
2. **The context is ranked and the prompt assembled.** *Implemented.* Instructions are
   rendered into the `SYSTEM` message from `{{FOCUS}}`/`{{OPPORTUNITY_ID}}` only; the
   context plus up to ten cosine-ranked supporting terms form the `USER` message
   (`AiContextService.java:58-67`, `106-115`).
3. **The allowed-number set is derived from the user message only.**
   *Implemented.* Every number in the user content is canonicalised into the set the reply
   is permitted to restate (`AiExplanationService.java:60-67`).
4. **The input checksum is computed** over
   `ai/explanation:<org>:<opportunityId>:<userContent>`, binding the artefact to tenant and
   to the exact facts shown. *Implemented.*
5. **The model is called at temperature 0.0**, capped at 2048 output tokens.
   *Implemented.* *Fails closed today* — the template is a TODO placeholder.
6. **A refusal is caught two ways.** *Implemented.* The port's own `refused` flag first,
   then `looksLikeRefusal` on the text, so a refusal phrased inside a normal-looking reply
   is still caught (`AiExplanationService.java:72-83`).
7. **The number fence runs.** *Implemented.* `findForeignNumbers` returns the reply's
   numbers minus the allowed set; a non-empty result yields `DISPUTED` with the offending
   figures named in the reason (`AiExplanationService.java:85-92`).
8. **A clean reply yields `CONFIRMED`.** *Implemented*
   (`AiExplanationService.java:94-95`). This is the only path in the module that produces a
   confirmed artefact, and it does so by having introduced no figure of its own.
9. **The response is wrapped** in `ExplanationResponse` with the verdict and checksum.
   *Implemented* at the service level; the HTTP exposure is *planned*.

### Flow of implementation

#### How AI is fenced from financial truth

The fence is not one mechanism but four, each closing a hole the others leave.

**A type-level fence.** Nothing under `ai/model/` has a `Money` field. `AiAnalysis` and
`ExtractionResult` carry a `SourceReference` and an `inputChecksum`; `AiExplanation` and
`ExtractedCommercialTerm` carry text. A consumer therefore cannot receive an amount from
this module even if the model wanted to give one — there is no setter, no field, no
constructor parameter to put it in. This is the reason `ai` may sit *inside* the
financial product without becoming a source of financial truth.

**A prompt-shaped fence.** `AiContextService` and `ContractExtractionService` both build
exactly two messages: a system message holding instructions, and a user message holding
data. The `render` helper substitutes only fixed, trusted ids, so no document text, context
text or model-supplied string is ever interpolated into the instruction template. The
comment at `AiContextService.java:154-156` and `ContractExtractionService.java:79-82` and
`127-129` says why: a contract that contains `{{system := ...}}` cannot rewrite the model's
instructions, because that text is not in the template — it is a separate message. This is
the structural half of the injection defence and the reason the system/user split is
enforced in the type (`LlmMessage.Role`) rather than left to convention.

**An output fence (the thesis check).** `AiExplanationService` computes the allowed number
set *from the user message* — the one place numbers legitimately live — and then diffs the
model's own numbers against it. Anything unmatched is, by definition, a produced figure
rather than a described one, and the artefact is `DISPUTED` (`AiExplanationService.java:85-92`).
The check is intentionally over-cautious: a model that legitimately cites a section number
or a date it was not given trips it too, and gets reviewed. A false positive costs a
review; a false negative costs the thesis.

**A schema fence.** Extraction output is not prose and is not trusted as prose.
`StructuredExtractionValidator` rejects anything that is not a JSON array, and — the
load-bearing check — walks the field names of each object *before reading any value*, and
rejects the item if any of them is in `FORBIDDEN_FIELDS` (`amount`, `discount_percent`,
`variance`, `expectedAmount`, …). This is what stops a model that wants to be helpful from
"also" computing a discount: it cannot smuggle the number in as a clause. The vocabulary
check is the same idea applied to categories — `termType` must be one of eight codes, so a
hallucinated `DISCOUNT_PERCENT` clause type cannot be invented either.

#### Guardrails

`AiGuardrailService` is a stateless set of pure string functions, deliberately injectable
with its own input budget so a caller can tighten context without subclassing. The four
checks and their real weight differ, and the file says so:

- `isOversized` — a cost and latency guardrail, not a safety one.
- `looksLikeRefusal` and `looksLikeInjection` — *heuristics, explicitly not a security
  boundary.* A model that wants to be hijacked can usually find a tokenisation the literal
  phrase list misses. These are cheap first filters whose job is to make an artefact
  reviewable, and the `INJECTION_PHRASES` Javadoc says so in as many words rather than
  overselling the control.
- `findForeignNumbers` — the one hard block, and the only guardrail the module's thesis
  actually rests on.

Number extraction canonicalises before comparing: `4,299.00`, `4299` and `4299.0` all
collapse to `4299`. That collapse is load-bearing rather than cosmetic — a model that
restates a given figure in a different guise has not invented one, and the fence must not
punish it. The `NUMBER_TOKEN` pattern is correspondingly conservative, and a token the
pattern matches but `BigDecimal` rejects (e.g. `3.14.15`) is skipped rather than
misread (`AiGuardrailService.java:156-167`).

#### Structured-output validation

The validator is defensive at three layers, in this order: recover a JSON document from a
possibly-fenced reply (`parseJson`, documented as a shim, not a spec parser); require the
root to be an array; then per item require an object, run the forbidden-field walk, require
a known `termType`, require a non-blank `description` (accepting `text` as an alias), and
require `pageNumber >= 1`. Every failure is a `ValidationException` — a hard fail, not a
silent skip. A partially-valid array is not accepted, because "some of the model's output
was fine" is not a state any downstream consumer can reason about.

#### Confidence handling

Confidence is not a float here; it is a closed four-way verdict plus a lifecycle, because a
scalar would invite someone to treat "0.82 confidence" as usable. The rules:

- **Output defaults to `IN_REVIEW`.** Extracted terms and the extraction run are unconfirmed
  on production, always. Only a guardrail passing an explanation can yield `CONFIRMED`.
- **`DISPUTED` ≠ `REJECTED`.** `DISPUTED` means output exists but failed the fence, so an
  auditor has something to look at; `REJECTED` means there is no usable value at all. The
  distinction is preserved end to end, including in the boolean helpers
  `isConfirmed()` / `isOpenlyDisputed()`.
- **The seal makes the handling exhaustive.** `AiValidationStatus` is a sealed interface of
  records, so every `switch` over a verdict is a compile-time exhaustiveness check: adding a
  sixth state breaks the build at every site that acts on a status, which is exactly when
  you want to be interrupted.
- **Reasons travel with the artefact, not on the enum.** Free-text reasons ("explanation
  cites numbers absent from the deterministic context: [...]") live on `ExtractionResult`,
  `AiExplanation` and the response DTOs, keeping the code set closed and the audit trail
  intact.
- **Cost is first-class.** `outputTokens` is carried on `LlmResponse`, `ExtractionResult`,
  `AiExplanation` and the responses, so the project can bill per million output tokens
  without re-instrumenting anything.

#### Determinism

Both model calls pass temperature `0.0`, and both compute an input checksum *before*
calling, so the success and failure paths of a run carry it identically. The checksum binds
tenant, source/id and text (extraction) or tenant, opportunity id and user content
(explanation), which means the same inputs reproduce the same artefact and a checksum from
one tenant cannot be replayed into another. Time comes from an injected `DateTimeUtils`, not
`Instant.now()` (rules §4). Note the one place entropy remains: the run id is
`UUID.randomUUID()` inside the orchestrators, which is fine for a correlation id but would
not be acceptable for a financial result — `AiAnalysis` is a *record of a run*, not a result
whose identity is derived.

### Key comments added

**`ContractExtractionService`** — that the checksum is computed up front so both the success
and the failure path carry it; that the document text is the `USER` message and never
substituted into the system template, with a contract's `{{system := ...}}` named as the
concrete injection it prevents; that the render helper is naive *by design* because only
trusted fixed ids pass through it; that temperature 0.0 is required for reproducibility
(§4); that extracted terms leave review `IN_REVIEW` rather than `CONFIRMED` because the
model is trusted to read, not to decide; that an oversized document is `SKIPPED` with a
reason instead of being refused outright, because splitting is the caller's call; that a
missing template is a startup defect rather than a runtime data error.

**`StructuredExtractionValidator`** — that the monetary-field check runs *before* any value
is read, so a figure masquerading as a clause is rejected wholesale; that the forbidden
field list is the fence itself; that `ALLOWED_TERM_TYPES` is a hand-kept duplicate of
`contract.enums.ContractTermType` because `ai` may not import `contract`, and that the
check is exactly what stops an invented `DISCOUNT_PERCENT`; that a non-array root is
rejected rather than guessed from; that `description`/`text` are accepted as aliases; and
that `parseJson` is a defensive shim, not a spec parser.

**`AiExplanationService`** — that the allowed-number set is derived from the user message
*and only* from the user message, which is what makes the fence sound; the `// THE FENCE`
comment on the foreign-number branch; that a refusal is checked twice because a refusal can
hide inside a normal-looking reply; that `MAX_OUTPUT_TOKENS` bounds both cost and the
surface a model has to invent a figure into.

**`AiContextService`** — that the class assembles prompts "and nothing more"; that keeping
instructions and data in two messages is the structural half of the injection defence,
stated as "data the model should not be able to rewrite is never part of the text it could
rewrite"; that ranking is a selection signal, never a semantic truth; that the zero-vector
guard exists because dividing by zero would otherwise rank everything as perfectly (or not
at all) similar; and that the candidate vectors are cached only for the duration of one
call, bounded by the request rather than by the model.

**`AiGuardrailService`** — that instance state is a configuration carrier (the injected
input budget) and everything else is pure; that the injection phrase list is explicitly
*not* a security boundary; that the refusal set is deliberately broad because a refusal is
better treated as a strong signal than as partial text to be trusted; that number
canonicalisation is what stops the fence punishing a reformatted restatement; that
`findForeignNumbers` is over-cautious on purpose, since a false positive costs a review and
a false negative costs the thesis; and that an unparseable token is skipped rather than
misread.

**`LlmMessage` / `LlmResponse` / `ExtractedDocument`** — that bundling role and content in
one record makes a malformed request structurally impossible, which matters because the
`SYSTEM` role carries the guardrails; that a refusal without a reason is a protocol
violation, because the reason is what makes the run auditable; that `ExtractedDocument` is
a port DTO and only its checksum is ever persisted.

**`AiValidationStatus` / `CodedEnum` / `AiProcessingStatus`** — that the sealed shape exists
so every status-handling `switch` is compiler-checked; that `all()` is a method and not a
constant because a static field holding nested `INSTANCE` references cannot initialise; that
a reason is free text and so does not belong on a closed code set; and that `CodedEnum` is
duplicated here word-for-word because module boundaries forbid the import.

**`ContractInterpretationService`** — that the stream is read and discarded inside the call
so the full document is never held on the artefact (only its checksum, §5); that the run id
doubles as the analysis id because one extraction maps to one interpretation.

**Package docs (`client`, `dto`, `enums`, `extraction`, `model`, `service`)** — that `model`
records can never hold money, which is the type-level fence; that `client` exists so
depending on an interface rather than an HTTP client is what makes the guardrails testable;
that `dto` bodies carry no organization field because scope comes from the principal; and
that `service`'s injection and refusal checks are review signals while the foreign-number
check is the hard block.

### Verification

No compilation or test run was performed in this pass — the finishing brief scoped this to
comments and documentation only. Everything described above as "implemented" is implemented
in source; the two prompt-template placeholders and the seven placeholder classes are
called out above as stubs rather than described as working.

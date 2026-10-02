## 7. Evidence & data lineage

### Module goal

`com.fintech.cfo.evidence` is the module that makes every number in this system
*arguable*. It owns three things and nothing else: the record of a **proof**
(`evidences`), the frozen **renderings** of a subject at the moment a claim was made
(`evidence_snapshots`), and the **graph** that connects a computed figure back to the
uploaded row it was read from (`lineage_nodes`, `lineage_edges`,
`evidence_references`). Its scope is deliberately narrow. It does not compute figures,
it does not own the source documents, and it does not own the subjects that figures are
about. It owns the *provenance* of both.

The module exists because of rule §5 of the implementation rules: a reported amount
with no way back to its source row is not shippable. Everything here is in service of
one chain that V7's own header comment names —

```
Calculation -> affected transaction -> canonical record -> source row -> source file
```

— and of the reverse journey: given any figure this system ever reported, name the
file and the row inside it that a reviewer should open.

Three commitments run through the built code.

**It never holds the bytes.** An `Evidence` row carries a `SourceLocation` (file id and
row number) and an `EvidenceArtifact` (a storage key and a content hash). It never
carries a document. An inline copy could not be independently re-read against the
source system, and could not be independently deleted when retention expired — it would
be simultaneously unverifiable and undeletable, which is worse than either. The only
inline content in the module is `evidence_snapshots.content`, and that is a *derived*
rendering of a subject, not the source document it was derived from.

**It refuses a proof it cannot check.** This is the module's defining property and it is
structural, not conventional. The type of an evidence row *decides* what the row must
point at: `EvidenceType.requiresSourceFile()` and `requiresSourceRow()` are consulted
inside `Evidence`'s compact constructor, so a `SOURCE_ROW` row naming no file and no
row cannot exist in memory. An unverifiable claim stored as evidence is worse than a
missing claim, because a reviewer will trust it.

**It separates "is derived" from "is verifiable".** `EvidenceType` keeps two axes apart
that are easy to conflate. `isDerived()` marks the computed types
(`CALCULATION_RESULT`, `CALCULATION_RUN`, `SNAPSHOT`), which are admissible only
alongside the source-bearing evidence beneath them. `isIndependentlyVerifiable()` marks
the types a third party can re-check, and it is *not* the complement: a
`REVIEW_NOTE` is neither derived nor verifiable (it is an opinion), while an
`INTEGRITY_ATTESTATION` is derived in spirit yet fully machine-checkable, because a
reviewer can re-hash the stored object and compare.

A structural caveat that shapes everything below: of the **26 files** in this slice,
**11 carry real code** (6 in `model/` — five value types plus the package contract — and
5 in `enums/` — three sets, the shared `CodedEnum`, and the package contract) and **15
are unimplemented placeholders** — all three services, all three
repositories, both controllers, all three DTOs, and the three graph/snapshot model types
(`LineageNode`, `LineageEdge`, `EvidenceSnapshot`, `EvidenceReference`). The vocabulary
and the value types are built; the traversal, the persistence and the HTTP boundary are
not. **No module in the repository currently imports `evidence`** — the boundary rule
is respected in both directions — so this is a self-contained slice awaiting its
composition root. The journeys below therefore describe the contract the built code
encodes and the placeholders now document, not a path that executes end to end today.

### File inventory

#### `enums/` — the vocabulary of provenance (all built)

| File | Goal |
| --- | --- |
| `enums/CodedEnum.java` | The shared contract for the closed sets whose variant name *is* the persisted `VARCHAR` value: one stable `code()` per variant, normalisation that fails loudly on a blank or unknown code, and a column-width guard so an over-long variant is refused at the boundary rather than silently truncated by the database. Deliberately a local copy of the identical contract in `contract.enums` and `financialtruth.enums`, because a business module must not depend on another business module. |
| `enums/EvidenceType.java` | What kind of proof an evidence row carries, over the eleven `VARCHAR(32)` values. Its four exhaustive `switch` predicates — `requiresSourceFile`, `requiresSourceRow`, `isDerived`, `isIndependentlyVerifiable` — are the module's rule engine: they are read by `Evidence`'s constructor, so a new constant that nobody classified is a compile error rather than a silently permissive `false`. |
| `enums/SourceType.java` | The kind of thing a piece of evidence or a lineage node is *about*, persisted as free `VARCHAR(64)` text with no lookup table. A final class rather than an enum precisely so subjects owned by other modules (`OPPORTUNITY`, `INVESTIGATION`) can be referenced without importing them. Fixes the chain hops as interned named constants and accepts any other well-formed code. |
| `enums/LineageRelationType.java` | The meaning of one edge, persisted as `VARCHAR(48)`, read as `from <relation> to`. Each constant declares both the `SourceType` it may point at and a `Direction`, which is what turns the traceability chain from a convention into something the compiler and the walk can both check — and what stops a trace from wandering into a cycle. |
| `enums/package-info.java` | States why two of the three sets are enums and one is a class, and — newly added — what the sets can and cannot verify on their own: a code can be well formed without the row it names existing or belonging to the caller's tenant. |

#### `model/` — the immutable shape of a proof (partly built)

| File | Goal |
| --- | --- |
| `model/ContentHash.java` | A SHA-256 digest as a validated 64-character value type rather than a bare `String`, because the columns that hold it are `CHAR(64) NOT NULL` and `CHAR` silently pads — a 60-character digest would look valid and deduplicate nothing. Owns the algorithm choice, the normalisation and the hash comparison in one place. |
| `model/Evidence.java` | One proof, mirroring a row of `evidences` column for column. The compact constructor is the module's front door: it refuses a row whose `EvidenceType` demands a source it does not carry, a non-positive row number, a blank title, a missing tenant and a missing digest. The three `with*` methods define what "immutable once stored" permits, and each advances `version`. |
| `model/EvidenceArtifact.java` | The pointer to an object stored outside the database: a storage key, the digest of what was written there, and the instant it was written. The digest is of the *bytes*, never of the key, so a reviewer can fetch the object and prove it is the same one the evidence was registered against. `pointsAtSameObject` compares keys alone, so a re-upload of identical bytes is idempotent rather than a conflict. |
| `model/SourceLocation.java` | The two source columns V7 repeats on `evidences` — `source_file_id` and `source_row_number` — and deliberately nothing more. Refuses a non-positive row number because parser numbering is 1-based and an off-by-one would send a reviewer to the wrong line while looking correct. `matches(SourceReference)` is the check that closes the gap between a locator and a canonical record's own provenance. |
| `model/SubjectRef.java` | A typed pointer at any subject, wherever it is owned: the pair V7 stores as `from_type`/`from_id` and as `subject_type`/`subject_id`. Wrapping the type and the id together is what makes it impossible to look up "some evidence by id" when the caller meant "the evidence for this invoice". Carries no tenancy, because a bare reference names nothing until it is read through a scoped query. |
| `model/LineageNode.java` | **Placeholder.** Intended: one vertex of the provenance graph, mapping to a row of `lineage_nodes`, carrying `node_type`, `node_id`, an optional label, and — when its `SourceType.bearsSourceCoordinates()` — a `SourceReference`. |
| `model/LineageEdge.java` | **Placeholder.** Intended: one directed relation between two nodes, mapping to a row of `lineage_edges`, carrying `relation_type` and validating the `to` side against `LineageRelationType.accepts(...)`. |
| `model/EvidenceSnapshot.java` | **Placeholder.** Intended: a frozen, content-hashed rendering of a subject at the instant a claim was made. Immutable after capture, deduplicated on `(organization_id, subject_type, subject_id, content_hash)` exactly as V7's unique index declares. |
| `model/EvidenceReference.java` | **Placeholder.** Intended: the typed from/to edge that binds an `evidences` row to two arbitrary subjects, matching V7's `evidence_references` and its unique index on the edge itself. |
| `model/package-info.java` | States the package rule — every type mirrors a V7 column, no `@Entity`, no framework types — and, newly added, which of the two stated invariants are *enforced today* (`Evidence` against `EvidenceType`) versus merely stated (`LineageNode` carrying a `SourceReference`, which no code can yet check). Also records why `evidence_snapshots.content` is a deliberate exception to the no-bytes rule rather than a contradiction of it. |

#### `service/` — the runtime behaviour (all placeholders)

| File | Goal |
| --- | --- |
| `service/EvidenceService.java` | **Placeholder.** Intended: registration, artifact attachment, hash re-verification and tenant-scoped retrieval. Referenced by name in `EvidenceType.isDerived()` as the component that must enforce `requireTraceableToSource`. |
| `service/EvidenceSnapshotService.java` | **Placeholder.** Intended: capture of a subject rendering, deduplicated by content hash, and retrieval of the snapshot a given claim was asserted against. |
| `service/LineageService.java` | **Placeholder.** Intended: the graph walk. Builds edges, enforces the four-hop `traceabilityChain()`, follows only `isTowardSource()` relations, and owns `requireTraceableToSource` — the check that refuses a derived claim whose chain does not reach a source-level node. |

#### `repository/` — persistence ports (all placeholders, left byte-identical per §11)

| File | Goal |
| --- | --- |
| `repository/EvidenceRepository.java` | **Placeholder.** Intended: a narrow port for reading and writing `evidences` rows, returning domain records rather than entities, with every query filtered on `organization_id`. |
| `repository/LineageRepository.java` | **Placeholder.** Intended: the port for `lineage_nodes` and `lineage_edges`, including the neighbour queries a breadth-first walk needs and the cycle guard that makes repeated visits detectable. |
| `repository/EvidenceReferenceRepository.java` | **Placeholder.** Intended: the port for `evidence_references`, including the reverse lookup V7 indexes on `(to_type, to_id)`. |

#### `controller/` — the HTTP boundary (both placeholders, left byte-identical per §11)

| File | Goal |
| --- | --- |
| `controller/EvidenceController.java` | **Placeholder.** Intended: register evidence, attach an artifact, list and read evidence for the caller's tenant, all returning `ApiResponse<T>` over a mapped DTO with tenant scope taken from the authenticated principal. |
| `controller/LineageController.java` | **Placeholder.** Intended: answer "where did this number come from?" for a claim-level subject, rendering the walked chain and marking any hop that could not be resolved. |

#### `dto/` — the wire shape (all placeholders)

| File | Goal |
| --- | --- |
| `dto/EvidenceResponse.java` | **Placeholder.** Intended: the summary projection of an evidence row — identifiers, type, title, version and whether it is independently verifiable. No description text, no document content. |
| `dto/EvidenceDetailResponse.java` | **Placeholder.** Intended: the full projection, adding the source locator and the artifact pointer plus its digest. |
| `dto/LineageResponse.java` | **Placeholder.** Intended: one hop of a walked chain — subject type and id, relation, and the level reached — so a UI can render a trace without inferring direction from relation names. |

### Flow of journey

Steps marked **[built]** execute in code today. Steps marked **[planned]** describe the
contract the placeholders now document and the built types already constrain; they have
no implementation yet.

**Registering a piece of evidence**

1. **[planned]** A caller (ingestion, financial, opportunity, or an audit action)
   asks `EvidenceService` to register a proof. The organization id comes from the
   authenticated `SecurityPrincipal`, never from the request body — rule §6.
2. **[built]** `EvidenceType` is chosen first, because the type *is* the requirement.
   `requiresSourceFile()` and `requiresSourceRow()` decide what the rest of the call
   must supply; a `SOURCE_ROW` registration arrives with a file id and a 1-based row
   number, a `CALCULATION_RESULT` arrives with neither.
3. **[built]** The content is hashed. `ContentHash.ofBytes` for an uploaded document's
   exact bytes, `ContentHash.ofText` for a canonical rendering of a derived claim.
   Hashing the bytes as written — never a re-encoding — is what makes the later
   comparison against a fetched object meaningful.
4. **[built]** `Evidence.register(...)` builds the row at `version` 0 with no storage
   key. Its compact constructor refuses a null tenant, a blank or over-long title, a
   missing digest, a non-positive row number, and any type-driven source requirement
   the caller failed to satisfy.
5. **[planned]** `EvidenceRepository` persists the row. Because the type-driven
   invariants are already enforced in memory, the repository has nothing to re-check
   and the write cannot introduce a row the model would have rejected.
6. **[built, second transition]** If the proof is a document, it is written to object
   storage through `platform.storage.ObjectStoragePort`, and the resulting pointer
   comes back as `EvidenceArtifact(storageKey, contentHash, storedAt)`. `storedAt` is
   the caller's instant, not `Instant.now()`.
7. **[built]** `evidence.withArtifact(artifact, now)` attaches it. If the row already
   points at a *different* object this raises — evidence is immutable once stored.
   Re-attaching the *same* key is permitted, because re-uploading identical bytes under
   one key is the same evidence rather than a new claim. The version advances.

**Taking a snapshot**

8. **[planned]** `EvidenceSnapshotService` is asked to capture a subject. The subject is
   named as a `SubjectRef` — a `SourceType` plus a `UUID` — so a lookup can never mix a
   subject id with the wrong subject type. For a subject owned by another module, the
   type is a free code accepted by `SourceType.of`, which is how this module describes
   an investigation without importing the investigation module.
9. **[planned]** The subject is rendered to text and hashed with `ContentHash.ofText`.
   The rendering is the frozen thing; the hash is its identity.
10. **[planned]** The snapshot is stored with `(organization_id, subject_type,
    subject_id, content_hash)` as the deduplication key — exactly V7's
    `ux_evidence_snapshots_subject_hash`. Capturing the same unchanged subject twice
    yields one row, so "the value at the time of the claim" is a single addressable
    fact rather than a growing pile.
11. **[planned]** Once captured, a snapshot is never edited. Correcting a subject means
    capturing a new snapshot with a new hash, which is why `content` and `content_hash`
    travel together: a reader can always tell which rendering the digest describes.

**Walking lineage from a computed number back to source rows**

12. **[planned]** `LineageService.trace(subject)` is asked where a figure came from,
    with the subject given as a `SubjectRef` whose type satisfies
    `SourceType.isClaimLevel()` — an opportunity, a calculation run, or a calculation
    result. The walk starts at a claim; nothing else is a legitimate starting point.
13. **[planned]** For each hop, only edges whose `LineageRelationType.isTowardSource()`
    is followed. That filter is a property of the *relation*, not of the edge row, so a
    graph mixing evidence-directed edges into the source chain cannot send a trace
    sideways, and — the reason the direction split exists — cannot revisit a node it
    has already proven and loop forever.
14. **[built]** The shape of each hop is already checkable. Each relation declares the
    `SourceType` it may point at, so `DERIVED_FROM` must land on an
    `AFFECTED_TRANSACTION`, `RECONCILED_WITH` on a `CANONICAL_RECORD`,
    `SOURCED_FROM` on a `SOURCE_ROW` and `BELONGS_TO_FILE` on a `SOURCE_FILE`. A chain
    with its middle hops transposed is refused rather than displayed.
15. **[planned]** Deduplication is by `(node_type, node_id)`, not by node id: two node
    types may legitimately share a UUID, and collapsing them would merge an opportunity
    with a file.
16. **[planned]** The walk terminates when it reaches a node whose type satisfies
    `SourceType.isSourceLevel()`. Before accepting that node as proof, the service
    confirms it with `SourceLocation.matches(...)` against the `SourceReference` the
    canonical record already carries — file *and* row, both required. A reference that
    omits either cannot confirm a locator, because "somewhere in that system" is not
    evidence that a specific line was read from a specific upload.
17. **[planned]** The result is `requireTraceableToSource`: if a derived claim's chain
    never reaches a source-level node, the claim is refused. This is the enforcement
    `EvidenceType.isDerived()` already points at and the only thing that stops a
    plausible-looking figure from shipping with no path behind it.
18. **[planned]** The reverse question — "what can I show a reviewer for this claim?" —
    is walked the other way, following only `Direction.TOWARD_EVIDENCE` relations to
    `SUPPORTED_BY_EVIDENCE` and `SUPPORTED_BY_SNAPSHOT`, and rendered through
    `LineageResponse`.

Steps 1, 5, 8, 10–13 and 15–18 have no implementation: the three services and the three
repositories are placeholders. Steps 2–4, 6–7 and 14 are built and constrain whatever
the services will do.

### Flow of implementation

**The hashing strategy.** One algorithm, chosen once, in `shared.util.HashUtils`:
SHA-256. The choice is dictated by the question the digest answers — "is this the same
content I saw before?" — for ingestion de-duplication, snapshot identity, and proving a
stored artifact was unaltered. All three need a fast, collision-resistant digest that is
reproducible on every machine forever, which is the *opposite* of what password hashing
optimises for. It is deliberately unsalted: a checksum nobody holding the file can
recompute is not a checksum. `ContentHash` then makes the digest a value type, because
`CHAR(64)` pads rather than truncates and a padded value read back would look valid
while deduplicating nothing. Three guarantees follow from that wrapping:

- **Shape is validated once, at the boundary.** `of(String)` accepts 64 hex characters
  in any case, trims `CHAR` padding, lower-cases with `Locale.ROOT`, and rejects
  anything else. `ofText` and `ofBytes` skip re-validation because `HashUtils` produces
  that shape by construction.
- **Determinism is pinned against the platform.** UTF-8 is stated explicitly in
  `ofText`, and `Locale.ROOT` is used in both `ContentHash.of` and
  `SourceType.of`. A default-charset or default-locale dependency would make the same
  snapshot hash differently on a differently-configured server — intermittently, in
  production, and only there.
- **The digest covers content, never identity.** Renaming an artifact leaves the hash
  alone; changing one byte anywhere in the stored object changes it. That is what makes
  it usable as an integrity *claim* rather than as a pointer.

**Immutability and integrity invariants.** `Evidence` is immutable in the sense that
matters for a citation: `evidence_type` and the source locator never change after
registration, so a figure cited from a row can never be silently re-pointed at different
data while keeping its old citation. Three narrow transitions are permitted — attach the
artifact once, clear the pointer once when the object is destroyed under a retention
policy, restate the digest when a stored object is re-verified — and each advances
`version`, which is the V7 optimistic-lock counter, so a lost update is detectable
rather than silently overwriting a storage pointer somebody else has already read.
`withoutStorage` is deliberately idempotent: a second call returns `this` rather than a
copy with a bumped version, so making a no-op look like an edit would train callers to
expect a version conflict where none occurred. The digest is *retained* when the pointer
is cleared, which is what allows a later re-upload of the same bytes to be recognised as
the same content and preserves the record of what the object was.

**Row numbering is the quiet killer.** `sourceRowNumber` must be 1-based and positive,
refused in `Evidence`, in `SourceLocation`, and (in the shared helper)
`Preconditions.requirePositive`. A 0-based coordinate stored beside a 1-based parser
produces an off-by-one that points a reviewer at the wrong line and looks entirely
correct while doing it — the single most expensive failure mode this module has,
because it survives review.

**The graph model.** Nodes are `(node_type, node_id)` pairs, edges are
`(from_node_id, to_node_id, relation_type)` with the relation declaring both its
permitted target type and its direction. Three design decisions carry the whole model:

- **The vocabulary is half-open.** `EvidenceType` and `LineageRelationType` are enums
  because every variant is a bare `VARCHAR` with no behaviour beyond a column width and
  two predicates — and a closed set means adding a state forces every handler to be
  revisited at compile time. `SourceType` is deliberately *not* an enum, because V7
  stores it as free text with no lookup table and subjects owned by other modules
  legitimately appear there. A closed enum would require editing this module every time
  another module grows a subject — exactly the coupling the module boundary exists to
  prevent.
- **Direction is declared, never inferred.** Three directions rather than two, because
  calculation-directed edges run *upward* out of a claim and are not part of the source
  walk. Keeping them in one closed set means every edge has an explicitly declared
  direction and none defaults to "toward source" — which is what makes the traversal's
  cycle guarantee a property of the vocabulary rather than of the walk's bookkeeping.
- **Shape is checkable per edge, completeness is not.** `accepts(SourceType)` validates
  one edge; it cannot verify that a *chain* is complete. That residual is exactly what
  step 17's `requireTraceableToSource` has to cover, and why the two are not collapsed
  into one check.

**Referential integrity across module boundaries.** `Evidence.sourceFileId` is a plain
`UUID`, not a reference to an ingestion type, because `evidences.source_file_id` is a
foreign key onto a table another module owns. The cost is stated rather than hidden:
this type cannot prove the file exists or belongs to the same tenant, and that check
belongs to the persistence pass because it needs the query, not the value. The same
reasoning explains `SubjectRef`'s open `SourceType` and its deliberate lack of a
tenant check. Tenancy is always carried by the enclosing row and enforced by the query,
never by the pointer.

**Why the key lines exist.** The two `switch` blocks in `EvidenceType` list every
constant explicitly rather than using a `default`, so an omitted variant is a compile
error — the omission would otherwise read as "this new type needs no source file",
which is precisely the claim the method exists to force someone to make on purpose.
`SourceType.bearsSourceCoordinates()` and `isSourceLevel()` are byte-identical today and
are kept apart deliberately: one states an obligation at construction, the other
classifies a position in the walk, and merging them would make a future type that is
source-level without bearing coordinates impossible to express. `belongsTo` compares by
code rather than by identity because a hand-built instance from a raw database row has
the same code and a different identity, and `==` there would silently report a source row
as not being source-level. `SourceLocation.matches` returns `false` on *any* missing
component rather than a partial match, because a partial match is the dangerous outcome:
it would confirm a row that was actually read from a different upload of the same file.

### Known inaccuracies found in the built code, recorded not edited

Per the instruction never to reword existing comments, four statements in this slice are
inaccurate and are recorded here instead:

1. `Evidence`'s class Javadoc links `{@link #withStorageKey}`, a method renamed to
   `withArtifact`. Both describe the same single transition; the javadoc now carries a
   short note pointing this out in place of a silent fix.
2. `model/package-info.java` describes `EvidenceArtifact` as carrying "a storage key, a
   content type, a length and a checksum". The record carries a storage key, a content
   hash and an instant — content type and length live in
   `platform.storage.StorageObject`.
3. `Evidence.register`'s Javadoc says the row has "no source locator yet", but the
   factory accepts `sourceFileId` and `sourceRowNumber` and passes them straight
   through. The added inline comment clarifies that the sentence describes the *artifact*
   side only; a `SOURCE_ROW` can and normally does arrive already pointing at its row.
4. `Evidence` imports `com.fintech.cfo.shared.validation.Preconditions` but calls it
   fully-qualified on all three lines. Harmless, but inconsistent with the rest of the
   codebase; left untouched because it is executable text.

Two `{@link}` targets point at types that do not exist at all, and both are deliberate
forward references to contracts the traversal pass must satisfy: a `LineageStep` that
would shape the chain (`LineageRelationType`'s Javadoc) and
`LineageService#requireTraceableToSource` (`EvidenceType.isDerived`'s Javadoc). The
`enums/package-info.java` now says so explicitly.

### Key comments added

**`ContentHash`** — why SHA-256 and why unsalted (fast and reproducible forever is the
requirement; a checksum nobody can recompute is not a checksum), and why the private
constructor exists so no caller can assert a digest and pass it off as one this module
computed; why normalisation trims before lower-casing and uses `Locale.ROOT` (`CHAR`
pads, and a locale-dependent digest breaks deduplication intermittently in production);
why the alphabet is checked in `of` but skipped in `ofText`/`ofBytes`; why `ofBytes`
hashes the stored bytes as written and never a decoded-then-re-encoded copy (a digest
taken over a re-encoding would report a mismatch on a file nobody altered).

**`Evidence`** — what "immutable" means precisely, and which three transitions are
permitted; why referential integrity for `sourceFileId` is a query's job and not a value
object's; why `register` starts at version 0 with `updatedAt == createdAt`; why
`withArtifact` refuses a *different* key but permits the same one; why `withoutStorage`
is idempotent and why the digest survives it; why `withContentHash` bumps the version
even when the hash is unchanged; why `auditDetail` is assembled from `sourceLocator()`
so the log line cannot disagree with the domain view of the row.

**`EvidenceArtifact`** — why `storedAt` is the caller's instant (a re-verification months
later must distinguish the original upload from a restore), why the width check measures
the trimmed value, and what `pointsAtSameObject` deliberately ignores: a differing digest
under the same key is a tampering signal to report, not a difference of identity.

**`SourceLocation`** — that the row number stays optional while a non-positive one is
refused; why `matches` requires *both* sides complete and returns `false` rather than a
partial match; why the file id is compared in its string form (the two types disagree on
how to spell it, and this type should not need a parsing dependency to answer a yes/no
question).

**`SubjectRef`** — that there is deliberately no tenancy check, because whether a
subject exists and belongs to the caller is a question only a scoped query can answer;
why `wellKnownType` returns the type or `null` rather than a boolean, so the transition
from unknown to known subject stays visible in the type.

**`EvidenceType`** — why all four predicates use exhaustive switches with every constant
listed and no `default`; why `isDerived` and `isIndependentlyVerifiable` are not
complements (`INTEGRITY_ATTESTATION` is derived yet fully machine-checkable); why
`INVOICE_LINE` is source-bearing rather than derived; that `fromCode` re-checks the
column width on the way *out*, and that an unknown code raises rather than falling back
to a plausible variant.

**`LineageRelationType`** — that `isTowardSource` is a property of the relation, not of
the edge row, which is what keeps a trace from wandering sideways or revisiting a node it
has already proven; why the chain has four hops and why all four point toward source;
why `Direction` has three members rather than two; that `accepts` validates one edge and
cannot validate a complete chain.

**`SourceType`** — why `of` normalises before matching and checks width only in the
non-well-known branch; why `belongsTo` compares codes rather than identities (a value
read straight from a database has the same code and a different identity); why
`bearsSourceCoordinates` and `isSourceLevel` are identical today and are kept apart on
purpose.

**`CodedEnum`** — why normalisation uses `Locale.ROOT` (a Turkish locale maps `i` to
`İ`, so the same stored code would resolve to a different variant on a differently
configured server), and why an unrecognised code fails rather than being guessed at.

**`enums/package-info.java` and `model/package-info.java`** — what these pure types can
and cannot verify (a well-formed code is not an existing, tenant-owned row); which
invariant is *enforced today* and which is merely stated because the graph types do not
exist yet; and why `evidence_snapshots.content` is a consistent exception to the
no-bytes rule rather than a contradiction of it.
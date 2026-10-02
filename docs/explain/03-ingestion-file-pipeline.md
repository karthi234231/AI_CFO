# Module explanations

## 3. Ingestion — secure file pipeline

### Module goal

Ingestion turns an untrusted file — a bank statement, an ERP invoice export, a
finance team's spreadsheet — into rows that the rest of the system is willing to
treat as financial evidence. It exists because that conversion is the single
point where attacker-controlled bytes become numbers somebody will later report
to a board, so the module is built as a series of gates rather than as a parser:
nothing may look at the *content* of an upload until its *name*, its
*signatures*, its *declared type* and its *actual type* have all been shown to
agree, and no row is ever trusted on the strength of having been read
successfully. Three properties are treated as non-negotiable throughout and are
the reason for most of the structure below. **Traceability** — every row carries
the file, sheet and 1-based row it came from, so any rupee can be traced back to
the line that produced it. **Honesty about failure** — a row that is refused
always says why, and a file that was only partly read is never reported as fully
read. **Reproducibility** — no wall clock, no randomness, no locale-dependent
parsing and no coercion, so re-running the same upload months later yields the
same rows, the same findings and the same rejection reasons.

### File inventory

All paths are relative to `src/main/java/com/fintech/cfo/`.

#### `ingestion/controller`

- **`ingestion/controller/IngestionController.java`** — Placeholder for the HTTP
  surface: accept an upload, start a run, report status, list a run's errors.
  Intended as a thin adapter over the service layer; the pipeline below it is
  plain objects with no Spring annotations and does not depend on this class.

#### `ingestion/dto`

- **`ingestion/dto/UploadedFileResponse.java`** — The file-security gate's verdict:
  declared and detected type reported *separately*, both filenames (original for
  audit, sanitised for reuse), and the SHA-256 digest. Never the bytes.
- **`ingestion/dto/StartIngestionRequest.java`** — What a client supplies to start
  a run, notably the schema. The schema arrives with the request rather than
  being inferred from the file, because a schema read out of the upload could
  describe itself and then satisfy its own validation.
- **`ingestion/dto/IngestionResponse.java`** — Projection of
  `IngestionProcessingResult` for a client that started the run. Rejected rows
  are listed individually with coordinates and reasons, not as a count, because
  "3 rows were rejected" does not let anyone fix the export.
- **`ingestion/dto/IngestionStatusResponse.java`** — Snapshot for status polling,
  carrying the V3 `version` counter so a client can distinguish a newer state
  from a stale one. Deliberately carries no row values.
- **`ingestion/dto/IngestionErrorResponse.java`** — One structured error.
  `rowNumber` is nullable, and that is the point: a corrupt container has no row,
  and inventing one would put a false row number into an audit record.
- **`ingestion/dto/package-info.java`** — Package contract: pure records with
  `from` projections, no framework annotations, and no response echoes uploaded
  content.

#### `ingestion/enums`

- **`ingestion/enums/FileType.java`** — Formats the module can read, plus
  `UNSUPPORTED` (a recognised format deliberately refused) and `UNKNOWN` (a typo).
  Declared type and detected type are recorded separately and compared.
- **`ingestion/enums/FileSecurityStatus.java`** — `PENDING` / `PASSED` /
  `REJECTED`, persisted to `source_files.security_status`. Only `PASSED` allows
  parsing; there is no "proceed anyway" state.
- **`ingestion/enums/ParseStatus.java`** — How far a parse got. `PARTIAL` is the
  load-bearing constant: rows were recovered but the file was not fully seen.
- **`ingestion/enums/IngestionStatus.java`** — Run lifecycle.
  `COMPLETED_WITH_REJECTIONS` exists so a run that processed every row but
  refused some can never be reported as a clean run.
- **`ingestion/enums/IngestionStage.java`** — The stage a run has reached,
  persisted to `ingestion_runs.stage`, so a stalled run names the exact gate it
  stalled at.
- **`ingestion/enums/IngestionErrorType.java`** — Classification of a problem
  (file security, type, parse, schema, required field, data type, quality,
  formula injection, duplicate, limit, internal). What a client filters on.
- **`ingestion/enums/RejectionReason.java`** — The exhaustive vocabulary an
  operator reads, grouped file / schema / row / field / security / quality.
  Deliberately specific: "row 4, column amount, INVALID_AMOUNT" is actionable in
  a way "invalid file" never is.
- **`ingestion/enums/ValidationSeverity.java`** — `INFO` / `WARNING` / `ERROR`.
  Only `ERROR` blocks a row; warnings are recorded but still processed.
- **`ingestion/enums/RowStatus.java`** — `ACCEPTED` / `REJECTED` / `SKIPPED`.
  There is no fourth silent bucket: a row that is not accepted always has a
  reason attached.
- **`ingestion/enums/ColumnType.java`** — What the *destination* expects a column
  to be. Deliberately a different vocabulary from `FieldType`, so the validator
  can report "the workbook says text, the schema says amount" instead of
  coercing one into the other.
- **`ingestion/enums/FieldType.java`** — What a reader *observed* in a cell.
  Recorded rather than collapsed into a string because the difference between a
  genuine `-1000` and an injected formula payload is only visible if the type was
  kept.
- **`ingestion/enums/HeaderMode.java`** — How the header row is located:
  heuristic, pinned to the first record, or absent (positional column names).
- **`ingestion/enums/package-info.java`** — Package contract: every constant is
  written to a V3 column, so the string forms and count are a storage contract.

#### `ingestion/model`

- **`ingestion/model/IngestionRequest.java`** — Everything needed for one upload
  in one immutable value. Carries buffered bytes rather than a stream because the
  gate, the checksum and the parser all need the same content, and injects
  `uploadedAt` and `asOfDate` instead of reading a clock.
- **`ingestion/model/IngestionLimits.java`** — The hard ceilings: file size, rows,
  columns, sheets, inflated bytes, archive entries, compression ratio, cell
  length, header scan window. Explicit and injected so a test can lower them and
  prove a guard fires.
- **`ingestion/model/UploadMetadata.java`** — What is known about an upload after
  the gates pass, mirroring the `source_files` columns. Keeps the original and
  the sanitised filename as separate fields, because conflating them is how path
  traversal gets in.
- **`ingestion/model/SanitisedFilename.java`** — A filename proven safe to use as
  a leaf name. A type rather than a string callers are asked to be careful with,
  because its constructor rejects separators, dot segments, whitespace, control
  characters and Windows device names.
- **`ingestion/model/FileChecksum.java`** — SHA-256 fingerprint. Exists for
  reproducibility and because V3 makes `(organization_id, checksum_sha256)` a
  unique key: the same bytes uploaded twice are one source file, not two.
- **`ingestion/model/FileSecurityResult.java`** — Outcome of the pre-parse gates.
  `allowsParsing()` is the only question a parser asks and is false for
  everything except a clean pass.
- **`ingestion/model/FileParseResult.java`** — Everything one parser produced:
  rows read, rows refused and why, and whether the whole file was seen. Keeps
  accepted / rejected / skipped as three separate counts so a run that quietly
  ignored 400 blank rows cannot hide it.
- **`ingestion/model/ParsedRow.java`** — One successfully read row, bound to its
  coordinate. Cells keep header order and their observed type, and `rawPayload`
  is a deterministic dump suitable for a future `source_records.raw_payload`.
- **`ingestion/model/ParsedCell.java`** — One cell as the reader saw it. Keeps
  `text` (cached result) and `rawText` (the formula) apart, which is what lets an
  auditor trace a number to the expression that produced it without the module
  ever evaluating untrusted spreadsheet code.
- **`ingestion/model/RowCoordinate.java`** — File, sheet and 1-based row. The
  hard product requirement behind traceability; row numbers are counted exactly as
  a human would count them in the source.
- **`ingestion/model/Rejection.java`** — Sealed union of everything ingestion
  refused, so the orchestration layer can talk about refusals without caring which
  stage produced them.
- **`ingestion/model/FileRejection.java`** — A whole-file refusal. Has no
  coordinates and does not need them; inventing a row number for a file that
  could not be opened would be false evidence.
- **`ingestion/model/RejectedRow.java`** — A refused row, with the reason, the
  coordinates and — where the problem is narrower — the column, plus the raw
  payload so finance can see what was actually in the row.
- **`ingestion/model/ValidationFinding.java`** — A single structured observation.
  No boolean "invalid" is returned anywhere in the module; findings are also what
  an `ingestion_errors` insert writes verbatim.
- **`ingestion/model/ValidationResult.java`** — Accumulated findings plus the rows
  that survived them. Holds both together because sanitising changes the row, and
  a findings-only object would invite the caller to use the un-neutralised version.
- **`ingestion/model/IngestionProcessingResult.java`** — Terminal output of the
  in-memory flow. Its builder derives the final status from the evidence rather
  than letting a caller choose, so a run with rejections cannot be labelled
  `COMPLETED`.
- **`ingestion/model/IngestionSchema.java`** — The expected shape of a file.
  Supplied by the caller, never inferred from the upload.
- **`ingestion/model/ColumnSchema.java`** — One expected column: name, declared
  type, and whether a row may omit it.
- **`ingestion/model/IngestionRun.java`** — Immutable value mirroring one
  `ingestion_runs` row, with terminal-state guards so a closed run cannot be
  reopened. Not a JPA entity.
- **`ingestion/model/IngestionError.java`** — Immutable value mirroring one
  `ingestion_errors` row, built from a finding so the structured observation and
  the persistable record cannot describe different problems.
- **`ingestion/model/SourceFile.java`** — Immutable value mirroring one
  `source_files` row, enforcing V3's column widths itself so a mapping mistake
  surfaces before an insert rather than as a truncation failure.
- **`ingestion/model/SourceRecord.java`** — Immutable value mirroring one
  `source_records` row: a row exactly as read, with its validity and reasons.
  This type *is* the storage shape of "evidence is a row, never a copy of the
  document".
- **`ingestion/model/package-info.java`** — Package contract: immutable
  throughout, defensively copied, every row carrying a `RowCoordinate`.

#### `ingestion/security`

- **`ingestion/security/UploadAuthorizationService.java`** — Whether the
  authenticated caller may upload for a given organization. Two separate checks
  — the `ingestion:upload` authority *and* a tenant match — because holding the
  authority without the tenant check is the classic cross-tenant leak.
- **`ingestion/security/MalwareScanService.java`** — An offline content screen
  that names dangerous file signatures (PE, ELF, Mach-O, Java class, OLE2, PDF,
  script shebang, NUL smuggling). Explicitly **not** antivirus, and must not be
  described as such: it closes the renamed-payload path without claiming a
  guarantee it cannot keep.
- **`ingestion/security/FilenameSanitiser.java`** — Rebuilds an untrusted
  filename from an allow-list into a `SanitisedFilename`. Rebuilding rather than
  escaping means the output is safe by construction rather than only as safe as
  every future consumer.
- **`ingestion/security/FileUploadSecurityService.java`** — The untrusted-input
  gate, ordered to touch the bytes as little as possible: traversal check,
  signature scan, filename sanitisation, then the type gate.
- **`ingestion/security/package-info.java`** — Package contract: untrusted input
  leaves as a proven type, and this is a content screen, not antivirus.

#### `ingestion/parser`

- **`ingestion/parser/FileParser.java`** — The port every format reader
  implements. Two guarantees every implementation must honour: every row is
  traceable, and nothing is dropped quietly.
- **`ingestion/parser/ParseRequest.java`** — One parse request. Requires
  `asOfDate` and buffers the stream under a hard ceiling, reading one byte past
  the limit so "exactly at the limit" and "one byte over" cannot be confused.
- **`ingestion/parser/ParseOutcome.java`** — The four states a parse can be in,
  as a sealed hierarchy so the compiler forces callers to distinguish "rows read"
  from "rows read from a file we only partly saw".
- **`ingestion/parser/CsvFileParser.java`** — Delimited-text reader on Commons
  CSV. Tokenises *headerless* so a ragged row is recoverable rather than fatal,
  isolates every record in its own try/catch, and reports a mid-file lexer fault
  as `PARTIAL` instead of pretending to have read the rest.
- **`ingestion/parser/ExcelFileParser.java`** — Workbook reader on Apache POI.
  Walks the container before POI sees it, reads each row in isolation, and
  refuses a formula with no cached result rather than fabricating a zero.
- **`ingestion/parser/ExcelCellReader.java`** — Turns one POI cell into a
  `ParsedCell` while preserving its type. Never evaluates a formula, converts
  numeric cells through `BigDecimal` so binary floating point cannot reach the
  money layer, and treats a missing cached result as a refusal.
- **`ingestion/parser/ArchiveGuard.java`** — Decompression-bomb defence for OOXML
  containers, walking the zip under entry-count, inflated-byte and ratio ceilings
  and aborting the moment one is crossed.
- **`ingestion/parser/HeaderDetector.java`** — Chooses where the header row is and
  turns header cells into unique column names. Shared by both readers so the same
  content is treated the same way regardless of format.
- **`ingestion/parser/TextDecoding.java`** — BOM-aware decoding. Detects and
  strips the mark, and refuses invalid bytes under the chosen charset rather than
  substituting replacement characters and calling the result readable.
- **`ingestion/parser/CsvParseOptions.java`** — Delimiter, quote, escape, header
  mode and tolerance for the CSV reader, as an immutable record so the dialect a
  file was read with travels with the result.
- **`ingestion/parser/ExcelParseOptions.java`** — Header mode, hidden-sheet
  handling, sheet allow-list and date-epoch override for the workbook reader.
- **`ingestion/parser/package-info.java`** — Package contract: parsers are the
  first code to look at content, so they only ever run behind the security gate.

#### `ingestion/validator`

- **`ingestion/validator/UploadFileValidator.java`** — The gate a file must pass
  before any parser sees it, requiring the extension, the declared content type,
  the sniffed bytes and the size to agree. A name that says CSV while the bytes
  are a ZIP container is refused rather than coerced.
- **`ingestion/validator/ContentSniffer.java`** — Identifies what a file actually
  is from its leading bytes. A filename is a claim; this is the evidence.
- **`ingestion/validator/SchemaValidator.java`** — Checks the header against the
  expected schema, producing file-level findings only: if a required column is
  absent, every row lacks it, so the upload is refused once rather than
  row-by-row.
- **`ingestion/validator/RequiredFieldValidator.java`** — Checks that columns the
  schema marks required carry a value. Always an error, never a warning: a
  ledger row without a date or amount cannot be posted.
- **`ingestion/validator/DataTypeValidator.java`** — Checks each value can be
  read as its column's declared type. Never coerces, never defaults, never
  truncates.
- **`ingestion/validator/TypedValueParser.java`** — Strict, locale-free parsing of
  decimals, dates and currencies. Refuses ambiguous forms and excess precision
  rather than guessing or rounding.
- **`ingestion/validator/DataQualityValidator.java`** — Well-typed but
  implausible values: dates before the accounting epoch (error) and after the
  reporting cut-off (warning). The cut-off is injected, never read from a clock.
- **`ingestion/validator/FormulaInjectionSanitiser.java`** — Neutralises
  spreadsheet formula injection with the leading apostrophe the spreadsheet
  applications themselves understand, while leaving signed decimals such as
  `-1000` untouched because those are ordinary amounts.
- **`ingestion/validator/DuplicateValidator.java`** — Refuses a row whose
  canonicalised business content already appeared on the same sheet. Stateful by
  design, because a duplicate is a property of the set of rows, not of one row.
- **`ingestion/validator/package-info.java`** — Package contract: nothing here
  coerces a value to make it fit, and amounts are parsed through `BigDecimal`
  only — no `double` or `float` appears in the package.

#### `ingestion/service`

- **`ingestion/service/IngestionService.java`** — The end-to-end in-memory flow
  and the only place the stages are sequenced: gate, parse, validate, count.
  Every input row ends up exactly one of accepted, rejected or skipped.
- **`ingestion/service/FileSecurityService.java`** — The pre-parse gate: may this
  caller upload, is this name safe, are these bytes what the name claims — in
  that order, touching the fewest things first. Turns a screened upload into the
  `UploadMetadata` the rest of the pipeline works from, and computes the
  checksum over the bytes that actually passed.
- **`ingestion/service/IngestionOrchestrator.java`** — Routes an admitted upload
  to the reader for its declared type via an `EnumMap` keyed by `FileType`, so no
  parser can be reached for a type it did not claim. Always returns an outcome;
  never throws for bad input.
- **`ingestion/service/FileValidationService.java`** — The validation facade:
  three ordered gates — may the bytes be parsed at all, does the header match,
  and per-row sanitisation, required fields, types, plausibility, duplicates. The
  row it accepts is the sanitised row, never the raw one.
- **`ingestion/service/IngestionStatusService.java`** — Projects a finished run
  onto the `ingestion_runs` and `ingestion_errors` values. Derives error
  identifiers from the run id and the finding's position rather than randomly, so
  re-projecting the same run yields the same primary keys.
- **`ingestion/service/package-info.java`** — Package contract for the
  orchestration layer.

#### `ingestion/repository`

- **`ingestion/repository/IngestionRepository.java`** — Placeholder for
  `ingestion_runs` persistence. Empty because the producing code is complete and
  only the insert, the optimistic `version` check and the transaction boundary
  remain.
- **`ingestion/repository/IngestionErrorRepository.java`** — Placeholder for
  `ingestion_errors` persistence, which will be a batch write: a 100k-row import
  can carry thousands of findings.
- **`ingestion/repository/SourceFileRepository.java`** — Placeholder for
  `source_files` persistence, which must honour the V3 unique index so
  re-uploading identical bytes returns the existing row rather than creating a
  second source file.

#### `processing/ingestion`

- **`processing/ingestion/IngestionJobConfiguration.java`** — Placeholder for
  the Spring Batch job definition. When implemented it should express the same
  stages as the in-memory flow as one job instance per run, with chunk
  boundaries so a failure costs one chunk rather than the whole import.
- **`processing/ingestion/IngestionJobLauncher.java`** — Placeholder for the
  entry point that starts a job for an admitted upload. Must refuse to launch
  anything the gate did not admit — the reason it sits downstream of the security
  gate rather than in front of it.
- **`processing/ingestion/IngestionProcessor.java`** — Placeholder for the item
  processor, one row per call. Rejections are expected in normal operation, so it
  must record a rejection and return rather than throw.
- **`processing/ingestion/IngestionWriter.java`** — Placeholder for the item
  writer that persists one processed row as a `source_records` row. No monetary
  interpretation happens here; that belongs downstream.

### Flow of journey

The end-to-end path an upload takes today, in order. Steps 1–2 are the intended
web path; `IngestionController` is still a placeholder, so the flow is driven
today by calling `IngestionService.ingest(...)` directly.

1. **Upload endpoint** — `IngestionController` (placeholder) receives the upload
   and the caller's declared schema (`StartIngestionRequest`) and builds an
   `IngestionRequest`. The bytes arrive as a buffered `byte[]`, already bounded by
   the configured ceiling.
2. **Upload authorisation** — `IngestionService.ingest` calls
   `FileSecurityService.admit`, which first asks `UploadAuthorizationService`.
   The caller must present the `ingestion:upload` authority *and* a tenant equal
   to the target organization. Failure is reported as
   `RejectionReason.ACCESS_DENIED`, not as a file problem, so an audit can tell
   "this user may not upload" from "this file is bad".
3. **Malware scan** — `FileUploadSecurityService.screen` runs
   `MalwareScanService` over the bytes, refusing PE, ELF, Mach-O, Java class,
   OLE2, PDF, shebang and NUL-smuggling signatures. This is a content screen, not
   antivirus; it names a signature and never echoes the bytes.
4. **Filename sanitisation** — `FilenameSanitiser` rebuilds the name from an
   allow-list into a `SanitisedFilename`. A name that carried a directory
   component, a drive letter or a `..` segment was already *refused* rather than
   stripped, by `containsTraversal`, because a genuine upload never has one and
   the attempt is worth recording. Everything downstream receives the sanitised
   name; the raw string never leaves the gate.
5. **Type gate and checksum** — `UploadFileValidator` compares four independent
   claims (extension, declared content type, sniffed content via
   `ContentSniffer`, size) and requires the first three to agree.
   `FileSecurityService` then computes `FileChecksum.sha256` over exactly the
   bytes that passed and emits `UploadMetadata` with status `PASSED`.
6. **Parse** — `IngestionOrchestrator` looks the declared `FileType` up in an
   `EnumMap` and calls the matching `FileParser`, handing it the *sanitised*
   display name so a row coordinate can never quote a traversal path back to a
   client.
   - *CSV*: `CsvFileParser` buffers under the size ceiling, decodes
     BOM-aware and refuses bytes that are not valid text, tokenises with Commons
     CSV in headerless mode, and materialises each record inside its own
     try/catch.
   - *XLSX*: `ExcelFileParser` runs `ArchiveGuard.inspect` **first** — walking the
     zip under entry-count, inflated-byte and ratio ceilings and aborting on the
     first breach — then rejects anything that is not a spreadsheet package
     before `WorkbookFactory` is allowed to inflate it.
7. **Header detection** — `HeaderDetector` locates the header within
   `IngestionLimits.headerScanWindow()` and produces unique column names
   (`column_n` for blanks, `name_2` for repeats). Rows above the header are
   counted as skipped and reported, never silently dropped. Callers who know
   their export can pin the answer with `HeaderMode`.
8. **Validate** — `FileValidationService.validate` runs the schema gate
   (file-level: missing header, missing required column, duplicate column), then
   per row: `FormulaInjectionSanitiser` on every cell, `RequiredFieldValidator`,
   `DataTypeValidator`, `DataQualityValidator`, and finally `DuplicateValidator`.
   The first `ERROR`-severity finding refuses that row alone.
9. **Batch persist** — the Spring Batch path in `processing/ingestion`: the
   launcher starts a job for an admitted run, the job configuration defines the
   stages and chunk boundaries, the processor handles one row per call (recording
   rejections rather than throwing), and the writer persists each row as a
   `source_records` row. All four are placeholders today.
10. **Record the run and status polling** — `IngestionStatusService.toRun` and
    `toErrors` project `IngestionProcessingResult` onto the `IngestionRun` and
    `IngestionError` values that mirror V3, using request-supplied timestamps and
    position-derived error ids. `IngestionStatusResponse.from(run)` is what a
    polling client reads: status, stage, the three row counts, failure reason and
    the `version` counter — and deliberately no row values.

### Flow of implementation

#### The security model

The pipeline is a chain of gates that narrow what the next stage has to consider,
and the ordering is the design. `FileSecurityService` performs three questions
"in the order that touches the fewest things": may this caller do this at all
(no bytes read), is this name safe (string inspection only), are these bytes what
the name claims (first real content inspection). Only after all three does a
parser exist.

Four properties carry the weight:

- **Proven types, not conventions.** Untrusted input leaves the security package
  as a `SanitisedFilename` and a `FileSecurityResult`. There is no code path
  where a caller can forget to sanitise, because the unsanitised value is never
  passed on. `SanitisedFilename`'s own constructor re-validates, so even a
  hand-constructed instance is safe.
- **Refuse, don't normalise, when the attempt itself is the signal.** A filename
  with a directory component is refused, not stripped: no honest upload has one.
  A filename that merely needed folding is normalised *and* the normalisation is
  reported as a `UNSAFE_FILENAME` warning, so the audit trail records it.
- **Three independent claims must agree.** Extension, declared MIME type and
  sniffed bytes. `ContentSniffer` recognises only what it can positively
  identify, and a generic `application/octet-stream` is treated as "unspecified"
  rather than as a lie, because browsers send it for many honest uploads.
- **Untrusted spreadsheet code is never executed.** `ExcelCellReader` reads a
  formula cell's *cached result* and keeps the expression as raw text. Evaluating
  an uploaded formula would mean running attacker-supplied expressions through
  the JVM's formula engine, and would make results depend on the evaluator rather
  than on the file.

A note on naming: `MalwareScanService` is a content screen, not antivirus. It
holds no signature database, calls no engine and makes no network call. Both it
and its package are documented as such because the class name invites the
stronger claim.

#### Limits enforced

`IngestionLimits` is a record of hard ceilings, injected rather than ambient so a
test can lower one and prove the guard fires. The defaults are an order of
magnitude above a full ERP invoice export for a mid-sized tenant and an order of
magnitude below anything that pressures the heap.

| Limit | Default | Enforced in | Failure mode |
|---|---|---|---|
| `maxFileBytes` | 20 MB | `ParseRequest.readContent` (buffers one byte past), `UploadFileValidator` | `SIZE_LIMIT_EXCEEDED` |
| `maxRowsPerFile` | 100 000 | both parsers' row loops | stop reading, status `PARTIAL` |
| `maxColumns` | 512 | `CsvFileParser.materialise`, `ExcelFileParser.readRow` | row rejected, never truncated |
| `maxSheets` | 32 | `ExcelFileParser.readWorkbook` | stop reading, status `PARTIAL` |
| `maxUncompressedBytes` | 200 MB | `ArchiveGuard.inspect` | `ARCHIVE_LIMIT_EXCEEDED` |
| `maxArchiveEntries` | 2 000 | `ArchiveGuard.inspect` | `ARCHIVE_LIMIT_EXCEEDED` |
| `maxCompressionRatio` | 200× | `ArchiveGuard.exceedsRatio` | `ZIP_BOMB_SUSPECTED` |
| `ratioCheckMinBytes` | 4 096 | `ArchiveGuard.exceedsRatio` | ratio not enforced below this size |
| `maxCellTextLength` | 4 096 | both parsers, `DataQualityValidator` | `VALUE_TOO_LONG`, row rejected |
| `headerScanWindow` | 10 | `HeaderDetector.detect` | header not found, positional names |

Two of these deserve their reasoning stated. **The compression ratio is only
enforced from 4 KB upwards**, because a legitimate small workbook can easily
compress 20:1 and refusing it would train users to bypass the check.
**Declared zip entry sizes are not trusted** — a zip bomb lies in its headers —
so `ArchiveGuard` actually inflates and measures, aborting the moment a ceiling
is crossed. That costs at most the configured ceiling, which is the point.

On zip-slip specifically: this module is entirely in-memory and extracts
nothing, so there is no extraction step for a crafted entry name to attack. Entry
names are still normalised (separators unified, control characters removed,
lower-cased) so a crafted name cannot forge a log line and so the
package-shape test compares against real OOXML paths.

#### Partial-success strategy

Rejections are the normal case, not the exception — a 100k-row export with three
bad rows is a success with three findings. The design therefore makes a bad row
*impossible to lose* rather than trying to prevent it:

- **Exactly one of row or rejection.** Both parsers materialise each record
  through a `RowOutcome` that can only ever produce a `ParsedRow` or a
  `RejectedRow`. There is no third state in which a row vanishes without a
  trace.
- **Row-level isolation.** Each record is read inside its own `try/catch`. A
  wrong field count, an unreadable cell, an over-long value or a formula with no
  cached result rejects that row alone; the next row is read normally.
- **Honest partial reads.** A fault *inside the lexer* (an unterminated quote
  mid-file) cannot be resumed from, because the token stream is already
  consumed. Rather than pretend otherwise, the records read so far are kept, the
  status becomes `PARTIAL`, and the fault is reported with a content-free reason.
  Collapsing that into `SUCCESS` would overstate how much was read; into `FAILED`
  would discard good rows.
- **Three separate counts.** `totalRowCount()` is
  `accepted + rejected + skipped`, and skipped covers positions that genuinely
  carried no data — blank lines, styled-but-empty spreadsheet rows, preamble
  lines above the header. A run that quietly ignored 400 rows is a run whose row
  numbering nobody can reproduce, so skips are counted and, where they hide
  something (a preamble), reported.
- **Findings derived from rejections.** `ValidationResult.Builder.reject` records
  the rejection *and* derives its finding in one step, so a rejected row with no
  matching finding cannot let a caller report a clean run.
- **Status derived from evidence.** `IngestionProcessingResult.Builder.build`
  computes the terminal status from what was accumulated, and `IngestionRun`
  independently maps any non-zero rejection count to
  `COMPLETED_WITH_REJECTIONS`. Neither a caller nor a job can label a lossy run
  `COMPLETED`.

#### Design decisions and why key lines exist

**The security gate is separate from parsing, and `allowsParsing()` is the only
bridge.** Once a file reaches a parser it has already been proven to be a
readable tabular file. `FileSecurityResult` carries one boolean that is false for
everything except a clean pass, which is the whole reason the gates are split.

**Parser selection is by enumeration, not `instanceof`.** `IngestionOrchestrator`
keeps an `EnumMap<FileType, FileParser>` and rejects two parsers claiming the
same type at construction. No parser can ever be reached for a type it did not
claim, and adding a format is a new parser on the constructor and nothing else.
The orchestrator's contract is that it always returns an outcome: a type with no
reader, or a reader that throws unexpectedly, becomes a stated refusal whose
reason names the exception class — a diagnostic, not file content.

**The CSV reader tokenises without registering a header.** This is the single
most consequential line in the file. Because no header is given to Commons CSV, a
record of the wrong width comes back as a plain list instead of throwing — which
is what makes ragged rows recoverable instead of fatal, and why one bad row
cannot abort a 100k-row file.

**CSV cells arrive typed as `STRING` even when they look numeric.** A delimited
file carries no type information, so typing a cell at read time would bake an
inference into the evidence. `DataTypeValidator` types each column against the
*declared* schema instead, which also lets it report the disagreement.

**Header detection is deliberately conservative.** A candidate row is a header
only if it has ≥2 non-blank cells, at least one matching a letter-initial label
pattern, and at least half its cells label-shaped. Requiring a letter-initial
cell is what stops a headerless numeric export like `INV-1,2024-01-31,100.50` from
having its own first data row promoted to a header and silently lost. A false
negative degrades to positional column names, which is recoverable; a false
positive loses a data row. Where the heuristic is wrong for a given export, the
caller pins it with `HeaderMode` rather than relying on it.

**Duplicate detection canonicalises rather than compares text.** `100` and
`100.00`, `31/01/2024` and `2024-01-31`, and narrations differing only in
repeated spaces are the same row. This targets the more common and more damaging
*false negative*, where the same entry exported twice with different formatting
slips through. Comparison is scoped per sheet, because the same transaction
legitimately appears on two tabs of a location breakdown. The key is joined with
ASCII Unit Separator, which cannot occur in text that survived sanitisation, so
no two genuinely different rows collide into a false rejection.

**Nothing is coerced, defaulted or rounded.** Amounts go through `BigDecimal`
only — no `double` or `float` appears anywhere in the validator package, because
binary floating point cannot represent a rupee exactly. POI's numeric cells are
converted via `new BigDecimal(Double.toString(value))`, the shortest decimal that
round-trips, rather than `new BigDecimal(double)`, which would hand the money
layer `1234.5600000000001`. More than four fraction digits is a *refusal*, not a
rounding, because rounding a source figure during ingestion would change the
audited number with nothing downstream able to tell.

**Ambiguity is refused, never guessed.** `1,234.56` and `1.234,56` are two
different numbers and neither is recoverable from the string alone, so grouping
separators are rejected rather than interpreted. Dates parse with
`ResolverStyle.STRICT` so `2024-02-30` fails instead of becoming 1 March, and only
unambiguous layouts are accepted — `MM/dd/yyyy` is deliberately absent.

**Formula-injection sanitisation distinguishes a signed decimal from a payload.**
Any cell starting with `=`, `+`, `-` or `@` is a formula to Excel, and a leading
tab, CR or LF is a bypass in its own right. But `-1000` and `+4.5` are ordinary
amounts, so a `+`/`-` cell that is not a plain decimal is the only signed case
that gets escaped. Escaping uses the leading apostrophe the spreadsheet
applications themselves understand. Bidi and format characters are removed
outright; embedded newlines are left alone, because a quoted CSV field may
legitimately contain one and silently folding it would change the evidence.

**The clock and randomness are injected.** `asOfDate`, `uploadedAt` and
`ParseRequest`'s timestamp all come from the caller. This is why
`DataQualityValidator` takes `asOfDate` rather than calling `LocalDate.now()`:
an import re-run next month must reach the same verdict about a forward-dated
entry. Similarly, `IngestionStatusService.errorIdFor` derives error ids from the
run id and the finding's position via `UUID.nameUUIDFromBytes` rather than
generating random UUIDs, so re-projecting the same run yields the same
`ingestion_errors.id` values — which is what will make idempotent re-ingestion
possible.

**Content never reaches a message or a log (rule 5).** Findings carry a reason
and a content-free explanation. The archive guard's messages state only the limit
and the observed figure, never entry content. `FileRejection` and
`ValidationFinding.toPersistableMessage()` both truncate to the V3 column width,
so a long diagnostic can never fail the insert and lose the error record entirely.

**Why there is no persistence in this module.** Every value here is immutable and
none is a JPA entity, and the three repositories are placeholders. That is what
lets the entire pipeline — gate, archive walk, both parsers, all five validators —
be exercised as a unit test with no database, no network and no Spring context.
`IngestionStatusService` sits in between: it produces the `IngestionRun` and
`IngestionError` values that a repository will insert, but owns no insert, no
version check and no transaction, because those belong to the wiring milestone.

### Key comments added

Additive only — no existing comment was deleted or reworded, and no executable
statement, import, signature, annotation or indentation was changed. Tab
indentation, `/** */` Javadoc, sentence-case prose.

**Role documentation for the eight placeholder files**, so an unimplemented file
still states its intent rather than only its TODO: `IngestionController` (the
intended HTTP surface and the four DTOs it would use), `IngestionRepository`,
`IngestionErrorRepository`, `SourceFileRepository`, and the four Spring Batch
files `IngestionJobConfiguration`, `IngestionJobLauncher`, `IngestionProcessor`,
`IngestionWriter`. Each records what the type is *for*, which already-implemented
code it would consume, and the constraint a future implementer must not lose — the
tenant boundary on every run lookup, the uniqueness constraint on re-uploads, that
the writer interprets nothing monetary, and that the launcher must refuse anything
the gate did not admit.

**Per-constant documentation for the enum groups.** `RejectionReason` gained a
one-line meaning for all 33 constants across its six existing groups;
`ColumnType`, `FieldType`, `FileType`, `FileSecurityStatus`, `ParseStatus`,
`RowStatus`, `ValidationSeverity`, `IngestionStatus` and `IngestionStage` gained
per-constant entries. The groups and their rationale were already documented and
were left as written.

**Inline comments at the non-obvious security and accounting decisions:**

- `ContentSniffer.isPlausibleText` — why the head-of-file sample is bounded to
  4 KB, why a NUL is an immediate rejection, why tab/LF/CR are the only
  legitimate control characters, and why the control-character tolerance is 1%
  rather than zero.
- `MalwareScanService.looksLikeNulSmuggling` — why only the opening 4 KB is
  sampled, and why *any* NUL counts.
- `FormulaInjectionSanitiser` — why leading tab/CR/LF are triggers in their own
  right; why a signed decimal is data and not a payload; why stripping precedes
  the payload test (a value that only *looked* dangerous because of a hidden
  character is judged on what will actually be stored); and why NUL and bidi
  characters are dropped rather than escaped.
- `DuplicateValidator` — why ASCII Unit Separator is the key delimiter (it cannot
  occur in sanitised text, so two different rows cannot collide into a false
  rejection), and why `Set.add` returning false makes the *first* occurrence the
  winner.
- `HeaderDetector.looksLikeHeader` — what each of the three counted quantities
  contributes, and why the asymmetry is deliberate: a false negative degrades to
  positional names and is recoverable, a false positive silently loses a data row.
- `FileParseResult.Builder.build` — that the status is derived from what was
  accumulated rather than chosen by the caller, and that a failed result must
  always carry a reason.
- `TextDecoding.decode` — why each BOM reports a body offset so the caller never
  strips twice, and why the no-mark path uses the configured default without
  guessing at it.
- `FilenameSanitiser.sanitise` — the step ordering (leaf name, then character
  folding, then separator collapse, then extension split) and why each step can
  assume the previous one held.

---

**Scope note.** This module currently has **no importers outside
`com.fintech.cfo.ingestion`.** All 348 import statements referencing it come from
within the module (plus its own tests). The contract for downstream consumers is
therefore the declared one, not an observed one: the V3 tables
(`source_files`, `ingestion_runs`, `ingestion_errors`, `source_records`) and
`RowCoordinate.toSourceReference(...)`, which projects an ingested row onto
`shared.domain.SourceReference` so downstream financial modules can link a
calculated variance back to the row that produced it without re-deriving
coordinates.

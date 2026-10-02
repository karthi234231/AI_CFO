## 4. Financial data — master data & transactions

### Module goal

`com.fintech.cfo.financial` owns the canonical, source-independent financial record: the six V4 tables
`customers`, `products`, `accounting_periods`, `invoices`, `invoice_lines` and `financial_transactions`,
plus the closed sets of codes that describe them and the mapping layer that projects them to an API.
Its job is to take rows that arrived in whatever dialect the upstream accounting system used — Tally
with lakh/crore grouping, an ERP with western grouping, a bank feed with no invoice at all — and turn
them into one shape in which a reported rupee can be traced back to the uploaded row that produced it.

Three commitments run through every file. **Lineage is part of the shape, not an annotation**: every
canonical record except `AccountingPeriod` carries a `SourceReference`, and a canonical amount without
a pointer back to its source row is not evidence. **Raise, never convert**: there is no exchange rate
anywhere in this module, so a mixed-currency invoice produces a `CURRENCY_MISMATCH` reconciliation
outcome rather than a converted total. **Reported and recomputed figures are kept apart**: the three
totals a source claimed are stored as facts, the totals derivable from the lines are derived on demand,
and `ReconciliationStatus` records which comparison held. Nothing in this module overwrites a source
figure to make a total balance, because that would destroy the evidence of the discrepancy.

A structural caveat that shapes everything below: of the 42 files in the slice, **26 carry real code
and 16 are unimplemented placeholders** — every controller, every normalizer, every repository and
every service. The domain, the enums, the DTOs and the mappers are built; the runtime wiring that would
connect them is not. The journeys below therefore describe the contract the built code encodes and the
placeholders now document, not a path that executes today.

### File inventory

#### `controller/` — HTTP boundary (all three are placeholders)

| File | Goal |
| --- | --- |
| `controller/CustomerController.java` | Intended read entry point for canonical customer master data. Documents why no write endpoint may accept a caller-supplied `sourceSystem`/`externalKey` pair, and why tenant scoping must come from the security principal rather than a request parameter. |
| `controller/InvoiceController.java` | Intended read entry point for the invoice aggregate. Its key rule: an invoice is never served without its `reconciliationStatus`, and no write endpoint accepts caller-computed totals. |
| `controller/ProductController.java` | Intended read entry point for the catalogue. Records that a product's `currency` is a label only and never a conversion authority for a billed line. |

#### `dto/` — wire contracts (all built, all fully Javadoc'd at component level)

| File | Goal |
| --- | --- |
| `dto/CustomerResponse.java` | Flat projection of `Customer`. Currency is an ISO-4217 string rather than an object so the wire shape does not change when currencies gain attributes. |
| `dto/InvoiceLineResponse.java` | Flat projection of one invoice line. Exposes four amounts plus one shared `currency` scalar instead of four `Money` objects, which is what prevents a client assembling a line whose amounts span currencies. |
| `dto/InvoiceResponse.java` | Flat projection of the invoice header, carrying the reported totals, the lines, and the reconciliation status together so a reviewer can compare both sides. Its compact constructor defensively copies the line list. |
| `dto/ProductResponse.java` | Flat projection of a catalogue entry. `sku` and `externalKey` are independently nullable; `currency` is informational. |
| `dto/TransactionResponse.java` | Flat projection of a transaction. Returns the signed amount exactly as stored and exposes only the type *code*, not the cash-flow classification. |

#### `enums/` — closed code sets (all built)

| File | Goal |
| --- | --- |
| `enums/package-info.java` | States the package rule: every type here is a closed set, so adding a member forces every `switch` over it to be revisited at compile time. |
| `enums/AccountingPeriodStatus.java` | The `OPEN`/`CLOSED`/`LOCKED` period lifecycle and the posting gate. Sealed because whether a period still accepts postings is a business decision, not a display string. |
| `enums/InvoiceStatus.java` | Seven invoice lifecycle states with two derived predicates the service layer asks: `requiresSettlement()` and `isTerminal()`. |
| `enums/ReconciliationStatus.java` | The five outcomes of comparing reported totals against recomputed ones, with `isBalanced()`. Each mismatch kind is distinct so a reviewer sees which figure disagreed. |
| `enums/SourceSystem.java` | The five supported upstream systems. A real `enum` (not a sealed interface) because it is a bare persisted code; its constructor asserts the code equals the constant name and fits `VARCHAR(64)`. |
| `enums/SourceSystemFamily.java` | The number-formatting family a source belongs to, and the two digit-grouping styles the amount parser can validate against. |
| `enums/TransactionType.java` | Ten movement classifications with `affectsCashFlow()` and `expectsPositiveAmount()`. Sealed so classification is never inferred from the sign of an amount. |

#### `mapper/` — projection layer (all built)

| File | Goal |
| --- | --- |
| `mapper/FinancialMappingSupport.java` | The `@MapperConfig` holding every conversion between the shared domain value types and the flat DTO shapes. Each method exists because its source type is a hand-written immutable class, not a JavaBean, so MapStruct's implicit accessor strategy would silently drop it. |
| `mapper/CustomerMapper.java` | Projects `Customer` to `CustomerResponse`. Explicitly does **not** project the resolution key or the external key: both are ingestion concerns. |
| `mapper/ProductMapper.java` | Projects `Product` to `ProductResponse`, under the same all-or-nothing qualification contract. |
| `mapper/InvoiceMapper.java` | Projects the invoice aggregate. Takes the `ReconciliationStatus` as a third explicit source parameter because it is not derivable from the header record — it depends on the lines. |
| `mapper/TransactionMapper.java` | Projects `FinancialTransaction`. The stored sign is returned verbatim; a mapper that normalised it would make an inverted-sign audit finding unreproducible. |
| `mapper/SourceSystemMapper.java` | Projects a `SourceSystem` to its `SourceSystemFamily` with an exhaustive `@ValueMapping`, so a newly added source system cannot silently inherit a parsing profile. |

#### `model/` — canonical records (all built)

| File | Goal |
| --- | --- |
| `model/package-info.java` | States that these are immutable records mirroring a V4 table column for column, with no framework types, and that the migration's constraints are enforced in compact constructors. |
| `model/AccountingPeriod.java` | The tenant's reporting calendar. The one canonical record with no lineage, because a period is a decision made in this system rather than a row imported from one. |
| `model/Customer.java` | Canonical customer master row. Nullable `externalKey` mirrors the partial unique index, so `resolutionKey()` returns `null` rather than manufacturing an identity the database would not enforce. |
| `model/Product.java` | Canonical catalogue entry. Deduplicated exactly as `Customer` is; `sku` is deliberately *not* part of the identity. |
| `model/Invoice.java` | Invoice header carrying the three *reported* totals. Explicitly does not enforce `total == subtotal + tax`, because that relationship is the reconciliation verdict rather than an invariant of the stored value. |
| `model/InvoiceLine.java` | Invoice line, and the module's central arithmetic decision: round the gross once, HALF_UP, at the money scale, and expose the residual against the literal V4 CHECK rather than hiding it. |
| `model/FinancialTransaction.java` | Canonical transaction. The optional `invoiceId`/`customerId`/`accountingPeriodId` and the preserved sign are both load-bearing. |

#### `normalization/` — identity and shaping (one built, four placeholders)

| File | Goal |
| --- | --- |
| `normalization/EntityResolutionKey.java` | The `(organizationId, sourceSystem, externalKey)` triple an upstream record is deduplicated under, mirroring the V4 partial unique indexes. All three components are mandatory; there is no "no external key" representation. |
| `normalization/FinancialDataNormalizer.java` | Intended facade composing the per-entity normalizers. Owns source-profile selection, period resolution, and the ordering of identity decisions. |
| `normalization/CustomerNormalizer.java` | Intended raw-row → `Customer` converter. Decides customer identity once and keeps it; raises on a blank name or unknown currency rather than repairing them. |
| `normalization/ProductNormalizer.java` | Intended raw-row → `Product` converter. Resolves on external key only, never on SKU or name, because the schema deliberately keeps two rows sharing a SKU apart. |
| `normalization/InvoiceNormalizer.java` | Intended header + lines → `Invoice` + `InvoiceLine` converter, and the producer of the `ReconciliationStatus` comparing reported against recomputed totals. |

#### `repository/` — persistence boundary (all five are placeholders)

| File | Goal |
| --- | --- |
| `repository/CustomerRepository.java` | Intended access to `customers`. Its one write-shaped query is the resolution-key lookup, shaped to make `ux_customers_org_source_key` usable and to refuse a wildcard for a null key. |
| `repository/ProductRepository.java` | Intended access to `products`, with the same identity rules and SKU exposed only as a convenience lookup. |
| `repository/InvoiceRepository.java` | Intended access to `invoices`. Documents that the persisted natural key `(org, source_system, invoice_number)` is *not* the same thing as `Invoice.resolutionKey()`, and that both lookups are needed. |
| `repository/InvoiceLineRepository.java` | Intended access to `invoice_lines`, written in terms of "all lines of this invoice" because a line's identity is positional (`invoice_id, line_number`) and has no external key. |
| `repository/FinancialTransactionRepository.java` | Intended access to `financial_transactions`, the highest-volume table. Every documented query is index-shaped, and the period gate is a precondition of the write. |

#### `service/` — orchestration (all four are placeholders)

| File | Goal |
| --- | --- |
| `service/FinancialDataService.java` | Intended module entry point and the only place allowed to move a transaction into an accounting period. Owns the tenant, the transaction boundary, and the period/reconciliation invariants. |
| `service/InvoiceService.java` | Intended invoice orchestration. Header and lines are written or not written together; reconciliation is computed from the lines actually stored. |
| `service/CustomerService.java` | Intended customer orchestration. Resolves the identity key, then updates or inserts inside one transaction, so a duplicate import is idempotent rather than a failed batch. |
| `service/ProductService.java` | Intended catalogue orchestration. Refuses to delete an entry that invoice lines still reference, because the reference is the evidence of what was billed. |

### Flow of journey

The five stages below are the contract the code encodes. Stages 1, 3 and 4 are implemented; stages 2
and 5 exist only as documented intent.

#### Invoices

1. **Controller** — `InvoiceController` receives a read for a tenant. It resolves no figures of its own
   and returns only through `InvoiceMapper`, which is the single place a canonical record becomes a
   wire shape. A read either asks for lines or does not; "did not ask" is an empty list, never null.
2. **Service** — `InvoiceService` derives the tenant from the security principal, loads the header,
   and (when lines were requested) loads them through `InvoiceLineRepository` **in `lineNumber` order**.
   It then computes the reconciliation outcome from the lines it actually loaded and hands all three to
   the mapper. It owns the transaction boundary, so the header and its line set commit together.
3. **Normalizer** — `FinancialDataNormalizer` resolves the batch's `SourceSystem` and projects it to a
   `SourceSystemFamily` through `SourceSystemMapper`; that family fixes digit grouping and date format
   for every amount and date in the batch. `InvoiceNormalizer` then trims the invoice number (never
   reformatting it, since it is half the natural key), keeps a null due date null, builds each
   `InvoiceLine` through `InvoiceLine` so the HALF_UP rounding happens exactly once, and compares the
   reported subtotal/tax/total against the sums recomputed from the lines.
4. **Mapper** — `InvoiceMapper.toResponse(invoice, lines, reconciliation)` qualifies the tenant, the
   three `Money` amounts, the status code and the four lineage fields through
   `FinancialMappingSupport`, maps `lines` element-wise to the single-argument overload, and takes the
   reconciliation outcome as an explicit third source. It never recomputes a total and never defaults
   the status to `MATCHED`.
5. **Repository** — `InvoiceRepository` resolves the existing header by whichever key the source
   supports: the natural key `(org, source_system, invoice_number)` per `ux_invoices_org_source_number`,
   or the `EntityResolutionKey` when the source supplied an external key and no trustworthy number.
   `InvoiceLineRepository` replaces the invoice's line set as a unit. Both writes are tenant-prefixed in
   the `WHERE` clause.

#### Transactions

1. **Controller** — the read path returns `TransactionResponse` through `TransactionMapper`. There is no
   write endpoint that would let a caller place a transaction into a period of their choosing.
2. **Service** — `FinancialDataService` resolves the accounting period from the transaction date and
   asks `AccountingPeriod.acceptsPostingOn(date)` before anything is written. That is the conjunction of
   two independent questions: is the date inside the window, and does the period still accept postings.
   A date inside a *locked* period is still refused, because the report containing it is signed.
3. **Normalizer** — `FinancialDataNormalizer` supplies the same source profile, then classifies the
   movement into a `TransactionType`. Classification comes from the type, never from the sign. The
   stored sign is carried through verbatim: a source that inverted one is an audit finding, not a
   formatting detail. `financial_transactions.accounting_period_id` stays nullable — a periodless posting
   is legitimate input and is resolved or rejected by the service, not hidden by the model.
4. **Mapper** — `TransactionMapper` unwraps the `Money` to a `BigDecimal` with its sign intact, takes the
   currency from the `Money` itself so the two can never drift, and exposes only the type code so a
   reporting rule can change without a data migration.
5. **Repository** — `FinancialTransactionRepository` resolves the existing row by
   `EntityResolutionKey` per `ux_financial_tx_org_source_key`. When the key is `null` the row is
   outside the index, and the documented rule is explicit: do **not** fall back to a date-and-amount
   match, which would collapse two identical payments on the same day into one.

### Flow of implementation

**Data model decisions.** Every canonical record is an immutable `record` mirroring one V4 table
column for column — no `@Entity`, no lazy proxies, no framework types. The migration's constraints are
enforced in compact constructors rather than deferred to the database, so an invalid row cannot exist
in memory even before persistence arrives: `ck_accounting_periods_range` becomes a constructor check,
`VARCHAR` widths become named constants such as `InvoiceLine.AMOUNT_SCALE`, and the currency agreement
across a header or a line becomes a private `requireSameCurrency` proof. `created_at`/`updated_at` are
deliberately absent from every record — V4 gives them database defaults, and adding them would mean
calling `now()` inside the domain, which the determinism rule forbids.

Currency is carried *inside* the amounts rather than beside them. `Invoice` has no `currency`
component; its three `Money` values each carry one and the constructor proves they agree, and
`Invoice.currency()` reads it off `subtotalAmount`. `InvoiceLine` does the same across four amounts.
There is therefore no state in which an amount and a currency column could disagree — and no path by
which two currencies could ever be combined, because `Money` itself refuses arithmetic across them.

**Entity-resolution strategy.** Identity is `(organizationId, sourceSystem, externalKey)`, matching the
V4 partial unique indexes `ux_customers_org_source_key` and `ux_products_org_source_key`. The tenant is
part of the key rather than a filter around it, so two tenants may legitimately carry the same external
identifier. `EntityResolutionKey` has no representation for "no external key": its three components are
all mandatory, and `Customer`, `Product` and `FinancialTransaction` return `null` from
`resolutionKey()` in exactly the case where the partial index does not apply. That null is load-bearing
— it is the signal to the ingestion layer that the row must not be matched against anything. Case is
trimmed but never folded, because the source system code is an exact identifier.

`Invoice` is the deliberate exception and the one place the strategy needs care. Its persisted identity
is the V4 natural key `ux_invoices_org_source_number (organization_id, source_system, invoice_number)`,
which is not the same thing as `Invoice.resolutionKey()`, which is built from the nullable
`external_key`. A source that supplies a number and no external key is therefore fully dedupable yet has
no resolution key at all. Both lookups exist and the repository must expose both; using one where the
other was meant either duplicates a re-numbered invoice or merges two invoices that share a number.
`Product.sku` is likewise excluded from identity even though `ix_products_org_sku` exists, because the
schema keeps two rows sharing a SKU apart on purpose.

**Period and reconciliation invariants.** A period is the only canonical record with no lineage, and
the only one whose key is a plain `String` rather than an `EntityResolutionKey` — it is a decision made
in this system. Two questions about a posting date are kept deliberately separate: `contains(date)`
answers the window question from the dates alone, and `AccountingPeriodStatus.acceptsPostingOn(date)`
answers the permission question. `AccountingPeriod.acceptsPostingOn` is their conjunction. Only `Locked`
overrides the date-level answer, and it does so to `false` even for a date inside the window, because
the closed report containing it is already signed.

Reconciliation preserves two independent sets of figures. `Invoice` stores the totals the source
reported and does *not* enforce `total == subtotal + tax` — real sources drift, and overwriting the
reported total with the recomputed one would destroy the evidence of that drift. `ReconciliationStatus`
has five distinct members rather than one generic mismatch, so a reviewer sees which figure disagreed;
`CURRENCY_MISMATCH` is a first-class outcome because a mixed-currency invoice cannot be compared at all
without a rate this system does not have. `InvoiceMapper` takes the outcome as an explicit third
parameter rather than deriving it, so a response can never claim `MATCHED` for money nobody verified.

`InvoiceLine` holds the module's one rounding decision. V4 declares
`CHECK (line_total = (quantity * unit_price) - discount_amount + tax_amount)` with six-decimal
`quantity` and `unit_price` and a four-decimal `line_total`, which the column cannot represent exactly.
The record therefore exposes three related figures: `grossAtSourcePrecision()` (unrounded),
`policyLineTotal()` (round once, HALF_UP, then discount, then tax), and `schemaCheckVariance()` (the
residual against the literal expression, bounded by half the smallest stored unit).
`satisfiesSchemaCheck()` returning `false` is not an error — it is the documented signal that the
persistence pass must relax that CHECK to a rounded comparison. HALF_UP is chosen over HALF_EVEN
because banker's rounding would shave half a unit off every other line in the counterparty's favour,
and this system bills the figure it computes.

**Why the key lines exist.** `FinancialMappingSupport` has ten methods that look like boilerplate and
are not: the domain types are hand-written immutable classes whose accessors (`CurrencyCode.value()`,
`Money.amount()`, the five `SourceReference` accessors) are neither `getX()` nor record components, so
MapStruct's implicit accessor strategy finds nothing and produces `null` without failing. Declaring
each conversion is what stops a silently dropped amount from reaching a report. The four
`SourceReference` methods are `@Named` because four methods converting the same type to the same type
would otherwise be ambiguous. The pom compiles with `-Amapstruct.unmappedTargetPolicy=ERROR` for the
same reason: an unmapped target must break the build, not ship as a null.

`TransactionType.affectsCashFlow()` exists so that cash-flow totals can exclude the
`OPENING_BALANCE`/`CLOSING_BALANCE` snapshots structurally, rather than relying on every caller to
remember the filter; counting a period's own opening and closing balances as movement double-counts
the period. `TransactionType.expectsPositiveAmount()` is a statement of expectation only — the stored
sign is never rewritten to match it. Every `fromCode` in the enums is an exhaustive `switch` on the
normalised string with no default branch, so adding a constant without a case is a compile error rather
than a period silently defaulting to `OPEN` or a transaction to `ADJUSTMENT`. All of them normalise with
`Locale.ROOT`, because under a Turkish default locale `toUpperCase()` maps `i` to `İ` and a lowercase
code would silently fail to match. `SourceSystem.fromCode` never falls back to `GENERIC` either:
`GENERIC` is an explicit assertion that a profile was *assumed*, recorded in the `SourceReference`,
whereas an unrecognised code is a data error.

### Key comments added

**Enums.** Per-constant meaning for all three `AccountingPeriodStatus` states and all ten
`TransactionType` classifications, which previously carried no Javadoc of their own; `@param`,
`@return` and `@throws` on every `fromCode`. Inline notes on the three decisions each one encodes: why
`CLOSED` and `LOCKED` differ even though both refuse postings; why the switch is exhaustive rather
than a map lookup; why `Locale.ROOT` is a correctness requirement and not a style choice.

**`SourceSystem`.** The constructor's two class-init assertions explained — the code-equals-name check
keeps `code()` and `fromCode` from ever disagreeing, and the width check matters because the code is
part of every `EntityResolutionKey` and would be truncated by the database. `fromCode` documents why an
unknown code is never mapped to `GENERIC`, with the concrete failure it prevents.

**`Invoice`.** The compact constructor annotated line by line, most importantly *what is not
enforced*: no `total == subtotal + tax` identity, because overwriting the reported figure is the one
thing reconciliation exists to prevent. Notes on why `invoiceNumber` is trimmed but never reformatted
(half the natural key), why a null `dueDate` must stay null (defaulting invents a collection deadline),
and why the currency proof is pairwise. `resolutionKey()` carries the warning that invoices have two
identities and the repository must expose both.

**`InvoiceLine`.** Every arithmetic method given `@param`/`@return` plus the reasoning behind it:
why `grossAtSourcePrecision()` is deliberately unrounded, why rounding happens exactly once and only
on the gross, and why `satisfiesSchemaCheck()` returning `false` is a documented state rather than an
error. The rounding-mode constant explains the HALF_UP choice in terms of the counterparty's favour.

**`AccountingPeriod`.** `contains` versus `acceptsPostingOn` split out explicitly, the inclusive-end
reading of the window (an exclusive one would silently drop every transaction on the final day), and
why a period's resolution key is a `String` rather than an `EntityResolutionKey`.

**`Customer`, `Product`, `FinancialTransaction`.** Compact constructors annotated, with the
nullable-versus-required decisions spelled out — in particular why an unmapped `productId` and an
unlinked `invoiceId` must both be storable, because dropping either loses a real charge. `resolutionKey()`
annotated on each with why the `null` branch is the load-bearing case.

**Mappers.** `FinancialMappingSupport` documented method by method with `@param`/`@return`, including
why the `SourceReference` methods must be `@Named` and why `amount` deliberately drops the currency.
`InvoiceMapper` carries the mapping contract in annotated form: every target qualified because no
domain accessor is a JavaBean getter, the reconciliation outcome injected rather than derived, an empty
line list meaning "not requested", and the absence of a per-line reconciliation field. `CustomerMapper`
and `ProductMapper` record what is deliberately *not* projected — the resolution key and the external
key. `TransactionMapper` records that the sign is never normalised and that the type is exposed as a
code only. `SourceSystemMapper` notes that it is the module's only Spring-component mapper and why a
family is not one-to-one with a system.

**`EntityResolutionKey`.** Why there is deliberately no "no external key" representation, why components
are trimmed but never case-folded, and why a blank external key is rejected — every unkeyed record
would otherwise collapse into one under the index.

**DTOs.** Short notes on the design each shape enforces: the `List.copyOf` in `InvoiceResponse` and why
an empty list is legitimate, the absence of a per-line reconciliation field and of `sourceFileId` on
the line, the informational-only catalogue currency, and the preserved transaction sign.

**Placeholders.** All sixteen stub files — every controller, normalizer, repository and service — now
carry a class-level Javadoc stating the type's role, its key design decision, and the specific
invariants it must honour when implemented: index-shaped and tenant-prefixed repository queries,
header-and-lines-atomic writes, tenant derived from the principal rather than a parameter, and no
currency arithmetic or clock access in any service. The existing `TODO` comments were left in place
below them.
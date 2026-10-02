-- V4: canonical financial model (source-independent internal representation)
--
-- Purpose: the normalized internal truth about money, independent of any source system. Everything
--   downstream - the truth engine, contracts, opportunities - reads these tables and never the raw
--   ingestion tables, so a change of supplier system does not reach past normalization.
-- Owning module: financial (com.fintech.cfo.financial).
-- Design decisions:
--   * Money is NUMERIC(20,4) everywhere; quantity and unit price are NUMERIC(20,6) because a rate
--     or a count with six decimal places is a real input even though the result is four. Never
--     floating point: a financial figure that does not survive a round trip through a double is
--     not a figure.
--   * Every monetary amount is paired with an explicit currency column. Amounts are never summed
--     across currencies; a total that would require conversion is a missing conversion rate, and
--     the schema refuses to imply otherwise.
--   * organization_id is denormalised onto every table so that tenant scoping never depends on a
--     join, and every unique constraint includes it.
--   * source_system plus external_key is the idempotency key for imported records: re-running an
--     import must update the existing canonical row rather than create a second one.

CREATE TABLE customers (
    id              UUID PRIMARY KEY,
    organization_id UUID        NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    -- The supplier's own identifier. Nullable because a customer may be created by hand before any
    -- external reference exists; the uniqueness predicate below keeps such rows out of the rule.
    external_key    VARCHAR(255),
    name            VARCHAR(255) NOT NULL,
    email           VARCHAR(320),
    tax_identifier  VARCHAR(120),
    -- NOT NULL and without a default: a customer record in an unknown currency is unusable, and
    -- defaulting it to the tenant's base currency would be a silent assumption about money.
    currency        CHAR(3)      NOT NULL,
    source_system   VARCHAR(64)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version         BIGINT       NOT NULL DEFAULT 0
);

-- Entity resolution across re-imports: the same customer from the same system must map to one row.
-- organization_id and source_system are both in the key because two tenants may legitimately use
-- the same external identifier, and because one tenant may pull from two systems that number
-- independently. The predicate allows manually created customers, whose external_key is NULL, to
-- coexist freely - without it PostgreSQL would still permit them, since NULLs are distinct, but the
-- predicate makes that intention explicit rather than incidental.
CREATE UNIQUE INDEX ux_customers_org_source_key
    ON customers (organization_id, source_system, external_key) WHERE external_key IS NOT NULL;

-- Matches the customer picker and the per-customer search in the UI. Name leads because the search
-- always filters by tenant first.
CREATE INDEX ix_customers_org_name ON customers (organization_id, name);

CREATE TABLE products (
    id              UUID PRIMARY KEY,
    organization_id UUID        NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    external_key    VARCHAR(255),
    sku             VARCHAR(120),
    name            VARCHAR(255) NOT NULL,
    description     VARCHAR(2000),
    unit_of_measure VARCHAR(32),
    currency        CHAR(3)      NOT NULL,
    source_system   VARCHAR(64)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version         BIGINT       NOT NULL DEFAULT 0
);

-- Same idempotency rule as customers, applied to the product catalogue.
CREATE UNIQUE INDEX ux_products_org_source_key
    ON products (organization_id, source_system, external_key) WHERE external_key IS NOT NULL;

-- SKU lookup, which is how a price resolution finds the product a term applies to.
CREATE INDEX ix_products_org_sku ON products (organization_id, sku);

-- An accounting period is the tenant's own reporting bucket. It is a business concept rather than a
-- calendar range, because a tenant closes a period explicitly and everything in that period
-- becomes immutable for reporting.
CREATE TABLE accounting_periods (
    id              UUID PRIMARY KEY,
    organization_id UUID         NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    code            VARCHAR(32)  NOT NULL,
    start_date      DATE         NOT NULL,
    end_date        DATE         NOT NULL,
    status          VARCHAR(32)  NOT NULL DEFAULT 'OPEN',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version         BIGINT       NOT NULL DEFAULT 0,
    -- Encodes the date invariant that cannot be left to application code alone: a period whose end
    -- precedes its start is always a defect, and the database is the only place that can refuse it
    -- for every writer including a direct SQL fix.
    CONSTRAINT ck_accounting_periods_range CHECK (end_date >= start_date)
);

-- The period code is the tenant's own label (FY25-Q1), so uniqueness is per tenant, not global.
CREATE UNIQUE INDEX ux_accounting_periods_org_code ON accounting_periods (organization_id, code);

-- The header. subtotal/tax/total are all stored rather than derived at read time because the value
-- the supplier sent is itself evidence: recomputing it would destroy the ability to detect that the
-- supplier's arithmetic disagreed with its line items.
CREATE TABLE invoices (
    id                 UUID PRIMARY KEY,
    organization_id    UUID          NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    customer_id        UUID          NOT NULL REFERENCES customers (id),
    -- Nullable: an invoice may arrive before its period is assigned, and an open item is still a
    -- real invoice. No cascade, because deleting a period must not delete invoices.
    accounting_period_id UUID        REFERENCES accounting_periods (id),
    invoice_number     VARCHAR(120)  NOT NULL,
    invoice_date       DATE          NOT NULL,
    due_date           DATE,
    status             VARCHAR(32)   NOT NULL,
    currency           CHAR(3)       NOT NULL,
    subtotal_amount    NUMERIC(20, 4) NOT NULL DEFAULT 0,
    tax_amount         NUMERIC(20, 4) NOT NULL DEFAULT 0,
    total_amount       NUMERIC(20, 4) NOT NULL DEFAULT 0,
    external_key       VARCHAR(255),
    source_system      VARCHAR(64)   NOT NULL,
    -- Lineage to the ingested file, deliberately without ON DELETE CASCADE on the invoice side: an
    -- invoice survives its source file so that a retention purge of raw uploads cannot silently
    -- alter a financial record.
    source_file_id     UUID          REFERENCES source_files (id),
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version            BIGINT        NOT NULL DEFAULT 0
);

-- Invoice numbers are unique per source system, not globally: two suppliers both number from 1.
CREATE UNIQUE INDEX ux_invoices_org_source_number
    ON invoices (organization_id, source_system, invoice_number);

-- Period and aging queries, both of which order by date descending.
CREATE INDEX ix_invoices_org_date ON invoices (organization_id, invoice_date DESC);

-- The customer statement view, which is also the main reconciliation fan-out.
CREATE INDEX ix_invoices_customer ON invoices (customer_id, invoice_date DESC);

-- The line. This table carries the schema's most consequential constraint, so the arithmetic it
-- encodes is stated here in full: quantity NUMERIC(20,6) x unit_price NUMERIC(20,6) produces up
-- to twelve decimal places, but line_total is NUMERIC(20,4) and can hold only four. The exact
-- product therefore cannot always be represented in the stored column, and ck_invoice_lines_total
-- below compares against an expression that may be unrepresentable. InvoiceLine (Java) rounds the
-- gross to the money scale once, before discount and tax, and exposes schemaCheckVariance() to
-- report the residual rather than hide it.
CREATE TABLE invoice_lines (
    id              UUID PRIMARY KEY,
    organization_id UUID          NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    invoice_id      UUID          NOT NULL REFERENCES invoices (id) ON DELETE CASCADE,
    -- Nullable on purpose: an unmapped line is still a charge that was billed. Dropping it would
    -- lose revenue; guessing a product would misstate what was sold.
    product_id      UUID          REFERENCES products (id),
    -- 1-based. With no external key on the line, position is its identity, and re-importing an
    -- invoice replaces the line set by position.
    line_number     INT           NOT NULL,
    description     VARCHAR(1000),
    quantity        NUMERIC(20, 6) NOT NULL,
    unit_price      NUMERIC(20, 6) NOT NULL,
    discount_amount NUMERIC(20, 4) NOT NULL DEFAULT 0,
    tax_amount      NUMERIC(20, 4) NOT NULL DEFAULT 0,
    line_total      NUMERIC(20, 4) NOT NULL DEFAULT 0,
    currency        CHAR(3)       NOT NULL,
    -- Direct pointer to the source row, so a disputed line total can be shown against the
    -- supplier's own value without searching.
    source_row_number BIGINT,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version         BIGINT        NOT NULL DEFAULT 0,
    -- The line-total invariant. It states that the stored total is not an independent figure: it is
    -- derived from quantity, price, discount and tax. Enforcing it in the schema means no writer,
    -- including a batch import and a hand-written fix, can store a total that contradicts its own
    -- components. Note the two known limits: (1) the left-hand side is NUMERIC(20,4) while the
    -- product needs up to twelve decimals, so the comparison fails by up to half the smallest
    -- stored unit whenever the product is not exact at four places, which the persistence pass is
    -- expected to relax to a rounded comparison; (2) there is no non-negativity rule here, because a
    -- credit note legitimately carries negative amounts and a blanket CHECK would forbid it.
    CONSTRAINT ck_invoice_lines_total CHECK (line_total = (quantity * unit_price) - discount_amount + tax_amount)
);

-- A re-import of the same invoice replaces its lines by position rather than appending duplicates.
-- invoice_id is sufficient without organization_id: the invoice already fixes the tenant, and
-- adding it would only lengthen the key.
CREATE UNIQUE INDEX ux_invoice_lines_invoice_line ON invoice_lines (invoice_id, line_number);

-- Product-level revenue and variance analysis.
CREATE INDEX ix_invoice_lines_product ON invoice_lines (product_id);

-- The cash and journal movement. Deliberately more permissive than invoices: an entry may exist
-- without either an invoice or a customer (a bank charge, an accrual), which is exactly why it
-- carries its own customer_id rather than deriving one.
CREATE TABLE financial_transactions (
    id                 UUID PRIMARY KEY,
    organization_id    UUID          NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    invoice_id         UUID          REFERENCES invoices (id),
    customer_id        UUID          REFERENCES customers (id),
    accounting_period_id UUID        REFERENCES accounting_periods (id),
    transaction_date   DATE          NOT NULL,
    transaction_type   VARCHAR(32)   NOT NULL,
    -- No non-negativity CHECK, unlike value_attributions in V9: a reversal or a refund is a
    -- negative transaction by nature, and the sign carries meaning here rather than indicating an
    -- error.
    amount             NUMERIC(20, 4) NOT NULL,
    currency           CHAR(3)       NOT NULL,
    description        VARCHAR(1000),
    external_key       VARCHAR(255),
    source_system      VARCHAR(64)   NOT NULL,
    source_file_id     UUID          REFERENCES source_files (id),
    source_row_number  BIGINT,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version            BIGINT        NOT NULL DEFAULT 0
);

-- Transaction imports are the most re-run path in the system, so the idempotency key must be
-- airtight: a retried import must not duplicate money movements.
CREATE UNIQUE INDEX ux_financial_tx_org_source_key
    ON financial_transactions (organization_id, source_system, external_key) WHERE external_key IS NOT NULL;

-- Ledger queries, ordered by date descending to match how they are read.
CREATE INDEX ix_financial_tx_org_date ON financial_transactions (organization_id, transaction_date DESC);

-- Settles an invoice, which is the first question asked about any transaction.
CREATE INDEX ix_financial_tx_invoice ON financial_transactions (invoice_id);
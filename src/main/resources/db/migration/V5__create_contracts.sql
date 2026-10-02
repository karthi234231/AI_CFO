-- V5: contract / commercial terms
-- Version columns make historical calculations reproducible: a calculation
-- always records which pricing-term version it evaluated.
--
-- Purpose: the terms a customer negotiated. This is the input side of the truth engine - it answers
--   "what should this line have cost?" as opposed to V4's "what did it cost?".
-- Owning module: contract (com.fintech.cfo.contract).
-- Design decisions:
--   * Terms are bitemporal in shape: an inclusive effective_from with a nullable effective_to means
--     "open ended", which is the normal case for commercial terms and the case a non-null
--     end-date column would make inconvenient to express.
--   * Supersession is modelled by closing one row and opening another, never by updating a live
--     term. A calculation therefore always has a specific row to point at.
--   * term_version (int, business versioning) and version (bigint, JPA optimistic locking) are two
--     different things and are deliberately both present. The first is a commercial fact that goes
--     into an audit answer; the second is a concurrency mechanism that must never be read.
--   * No unique constraint on the term tables. Terms legitimately overlap - a general discount and
--     a product-specific one apply to the same contract at the same time - and the resolver layers
--     them by specificity.

CREATE TABLE contracts (
    id                 UUID PRIMARY KEY,
    organization_id    UUID         NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    -- Nullable: a contract may be signed with a customer that has not yet been created as a
    -- canonical record, and losing the contract because the customer is missing would be worse than
    -- an unresolved link.
    customer_id        UUID         REFERENCES customers (id),
    contract_number    VARCHAR(120) NOT NULL,
    title              VARCHAR(500),
    status             VARCHAR(32)  NOT NULL,
    currency           CHAR(3)      NOT NULL,
    effective_from     DATE         NOT NULL,
    effective_to       DATE,
    signed_at          TIMESTAMPTZ,
    -- Where the executed document lives, as a storage key rather than a URL or a path, so the
    -- document cannot become unreachable if a bucket or volume is renamed.
    document_reference VARCHAR(1024),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version            BIGINT       NOT NULL DEFAULT 0,
    -- The same date rule as accounting_periods in V4, applied to the commercial window. A contract
    -- that expires before it starts is a data error, and it would silently exclude every date from
    -- every term lookup that consults it.
    CONSTRAINT ck_contracts_range CHECK (effective_to IS NULL OR effective_to >= effective_from)
);

-- Contract numbers are tenant-scoped, not global: numbering conventions are per customer.
CREATE UNIQUE INDEX ux_contracts_org_number ON contracts (organization_id, contract_number);

-- The "which contract was in force on date X" lookup, which drives every effective-term resolution.
-- effective_from and effective_to are both in the key so the range predicate can be served from the
-- index rather than by fetching every version of the contract.
CREATE INDEX ix_contracts_org_dates ON contracts (organization_id, effective_from, effective_to);

-- A named commercial provision - a payment term, a rebate, a liability cap - held as data rather
-- than as a column, so new term kinds do not need a migration.
CREATE TABLE contract_terms (
    id              UUID PRIMARY KEY,
    organization_id UUID         NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    contract_id     UUID         NOT NULL REFERENCES contracts (id) ON DELETE CASCADE,
    term_type       VARCHAR(48)  NOT NULL,
    description     VARCHAR(2000),
    effective_from  DATE         NOT NULL,
    effective_to    DATE,
    -- Business versioning: incremented when a term is superseded, so a stored calculation result can
    -- name the exact wording it was evaluated against. Starts at 1 rather than 0 because 1 reads as
    -- "the first version" in an audit answer.
    term_version    INT          NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    version         BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT ck_contract_terms_range CHECK (effective_to IS NULL OR effective_to >= effective_from)
);

-- Matches the resolver query exactly: all terms of one kind for one contract, newest effective date
-- first. No uniqueness here because a contract may legitimately hold several terms of one type
-- covering different windows.
CREATE INDEX ix_contract_terms_contract ON contract_terms (contract_id, term_type, effective_from);

-- The agreed price. product_id and customer_id are both nullable and both meaningful: a null means
-- the term applies at contract level rather than to one product or one customer. Together they
-- express specificity, and the resolver prefers the most specific match.
CREATE TABLE pricing_terms (
    id                UUID PRIMARY KEY,
    organization_id   UUID          NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    contract_id       UUID          NOT NULL REFERENCES contracts (id) ON DELETE CASCADE,
    product_id        UUID          REFERENCES products (id),
    customer_id       UUID          REFERENCES customers (id),
    pricing_type      VARCHAR(32)   NOT NULL,
    -- 6 decimals here even though the money scale is 4: a unit rate is a rate, not a payable
    -- amount, and rates legitimately carry more precision than the amounts computed from them.
    -- unit_price is nullable because a pricing_type such as TIERED carries its bands in
    -- commercial_rules rather than a single figure.
    unit_price        NUMERIC(20, 6),
    price_minimum     NUMERIC(20, 6),
    price_maximum     NUMERIC(20, 6),
    currency          CHAR(3)       NOT NULL,
    effective_from    DATE          NOT NULL,
    effective_to      DATE,
    term_version      INT           NOT NULL DEFAULT 1,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version           BIGINT        NOT NULL DEFAULT 0
);

-- The hot path of the whole system: given a contract, a product and a date, find the price. Every
-- column of that predicate is in the key, in predicate order, so the lookup is a single index scan
-- rather than a scan of the contract's terms filtered in memory.
CREATE INDEX ix_pricing_terms_lookup
    ON pricing_terms (organization_id, contract_id, product_id, effective_from, effective_to);

-- Discount terms mirror pricing terms. discount_value is 6-decimal for the same reason as
-- unit_price: a percentage or a rate is not an amount.
CREATE TABLE discount_terms (
    id                UUID PRIMARY KEY,
    organization_id   UUID          NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    contract_id       UUID          NOT NULL REFERENCES contracts (id) ON DELETE CASCADE,
    product_id        UUID          REFERENCES products (id),
    customer_id       UUID          REFERENCES customers (id),
    discount_type     VARCHAR(32)   NOT NULL,
    discount_value    NUMERIC(20, 6) NOT NULL,
    -- Optional cap in money terms. Nullable because most discounts are uncapped; where a cap
    -- exists it must be enforced, and DiscountService refuses rather than silently exceeding it.
    max_discount_amount NUMERIC(20, 4),
    -- Nullable unlike every other money column in the schema: a percentage discount has no
    -- currency. Forcing one here would require inventing a currency for a rate that has none.
    currency          CHAR(3),
    effective_from    DATE          NOT NULL,
    effective_to      DATE,
    term_version      INT           NOT NULL DEFAULT 1,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version           BIGINT        NOT NULL DEFAULT 0
);

-- Same access pattern as pricing terms, so the same index shape serves both and the resolver can
-- compare specificity across the two tables without one of them degrading to a scan.
CREATE INDEX ix_discount_terms_lookup
    ON discount_terms (organization_id, contract_id, product_id, effective_from, effective_to);

-- Machine-evaluable rules. expression holds the formula and parameters its JSON configuration, so a
-- rule can change without a deployment; rule_type selects the evaluator that understands it.
-- contract_id is nullable because some rules are organization-wide policies.
CREATE TABLE commercial_rules (
    id                UUID PRIMARY KEY,
    organization_id   UUID          NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    contract_id       UUID          REFERENCES contracts (id) ON DELETE CASCADE,
    rule_code         VARCHAR(64)   NOT NULL,
    rule_type         VARCHAR(48)   NOT NULL,
    expression        VARCHAR(2000),
    parameters        TEXT,
    effective_from    DATE          NOT NULL,
    effective_to      DATE,
    term_version      INT           NOT NULL DEFAULT 1,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version           BIGINT        NOT NULL DEFAULT 0
);

-- rule_code is the identifier a calculation result cites when it explains which rule produced a
-- variance, so it must be unique per tenant and stable. Global uniqueness would make an identical
-- policy in two tenants a migration.
CREATE UNIQUE INDEX ux_commercial_rules_org_code ON commercial_rules (organization_id, rule_code);

-- Which rules apply to a contract, ordered so the most recently effective is evaluated first.
CREATE INDEX ix_commercial_rules_contract ON commercial_rules (contract_id, effective_from);
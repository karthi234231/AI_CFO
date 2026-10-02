# Contract selection: which price applies on a date

## 1. WHY
One contract holds many overlapping rows: product-only,
customer-only, both, neither (default), each versioned.
Picking wrong row = billing wrong customer at wrong price.

## 2. HOW
Eligibility (drop others' deals) -> specificity (bespoke
beats default) -> version (newest wins) -> resolve+clamp.

## 3. FUNCTION-BY-FUNCTION

### 3a. TermSelector.candidatesInForce(terms, product, customer, date)
File: .../contract/service/TermSelector.java

- [L56-57] resolver.inForceOn(terms, date, eligible?):
  window contains date AND product-eligible AND
  customer-eligible. Eligibility [L118-131]: scope null
  = "any" (stays, acts as default); scope non-null and
  != request = someone else's deal (dropped, never
  ranked). UUID equals by value, not ==.
- [L59-64] sort bySpecificityThenVersion: specificity
  FIRST (-score so most-specific sorts first), version
  second via resolver precedence. Order is the point: a
  v1 bespoke rate MUST beat a v3 default, else agreed
  price silently reverts to list on every default edit.
- specificity() [L96-109]: both match=3, product=2,
  customer=1, default=0. Ineligible rows never reach
  here (scored 0 but already dropped).
- selectInForce() [L72-75]: first of sorted, or empty =
  "contract says nothing here" (caller throws, never
  defaults).

### 3b. PriceResolutionService.resolve(term, currency, date)
- requireInForce + requireCurrency (no FX, refuse).
- Reject no-price-no-bounds (nothing to charge).
- MonetaryScale.price() to 6dp, clamp floor-then-ceil
  into [min,max], return ResolvedPrice(clamped,
  declared, min, max, wasClamped, id, type, version).
  wasClamped=true surfaces author's inconsistency.

### 3c. DiscountService.evaluateFor(terms, product, customer, gross, date)
- Select ONE term via selector (no stacking: V5 has no
  stacking flag, compounding would be invented).
- Percentage: gross/100*value at 4dp HALF_UP.
  Percentage applies to CALLER's gross (explicit
  compounding by caller, visible in grossAmount field).
- Fixed: currency must match, else refuse.
- Caps in order: maxDiscountAmount, then gross itself
  (discount bigger than gross would make supplier owe
  money). reason None/Max/Gross + capAmount reported.

### 3d. Shared kernel: Money, CurrencyCode, DateRange
- Money: final, BigDecimal, same-currency guard,
  divide needs scale+mode, equals via compareTo
  (100 == 1E+2), hash via stripTrailingZeros.
- CurrencyCode.of [L58-74]: trim->upper(ROOT locale,
  avoids Turkish-i)->len+range check (no regex, hot
  path)->map get then computeIfAbsent (bounded 26^3).
- DateRange [record]: compact ctor rejects
  end<start; days()=between+1 (inclusive, single day=1
  not 0); contains inclusive both ends; overlaps
  touching=true.

## 4. WHAT BREAKS
- Version-before-specificity sort = bespoke rate loss.
- Silent FX or silent zero price = fabricated revenue.
- Stacking two discounts = invented compounding.
- Rounding twice (normalizer + service) = drift; round
  once per line (InvoiceLine HALF_UP).

Next: 12-stubs tells what each TODO file must become.

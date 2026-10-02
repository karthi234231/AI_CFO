package com.fintech.cfo.contract.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.fintech.cfo.contract.dto.ResolvedPrice;
import com.fintech.cfo.contract.model.PricingTerm;
import com.fintech.cfo.contract.model.ScopedTerm;
import com.fintech.cfo.shared.domain.CurrencyCode;
import com.fintech.cfo.shared.domain.Money;
import com.fintech.cfo.shared.exception.BusinessRuleException;
import com.fintech.cfo.shared.exception.ValidationException;

/**
 * Turns "what is this product worth on this date under this contract" into one
 * number, with the reasoning attached.
 *
 * <p><strong>Clamping.</strong> A candidate price is clamped into the term's
 * {@code price_minimum} / {@code price_maximum} band. Three cases, all reachable,
 * all distinguished on the result:
 *
 * <ul>
 * <li>A published {@code unit_price} outside its own band is clamped to the bound.
 * The row is internally inconsistent, but clamping gives the contract author the
 * benefit of the bound they wrote, and {@code clamped} is set so the inconsistency
 * surfaces instead of being silently absorbed.</li>
 * <li>A band-only row, where {@code unit_price} is null, leaves an offered price
 * untouched when it is inside the band and clamps it to the nearest bound when it
 * is not. This is how a tiered or floor-and-ceiling price is meant to be applied.</li>
 * <li>A row with no {@code unit_price} and no bounds has no opinion and is not a
 * price at all; it is rejected rather than resolved to zero.</li>
 * </ul>
 *
 * <p><strong>Currency is never converted.</strong> The offered price and the term's
 * currency must agree, and a mismatch is refused. This module has no FX component,
 * and inventing a rate would put an unreproducible number into a priced result.
 *
 * <p>Stateless.
 */
public final class PriceResolutionService {

	private final EffectiveTermResolver resolver;

	private final TermSelector selector;

	public PriceResolutionService(EffectiveTermResolver resolver, TermSelector selector) {
		this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
		this.selector = Objects.requireNonNull(selector, "selector must not be null");
	}

	/**
	 * The price the term publishes, clamped into its own bounds.
	 *
	 * @throws BusinessRuleException if the term publishes no price and has no
	 * bounds to clamp into, so there is nothing to charge
	 */
	public ResolvedPrice resolve(PricingTerm term, CurrencyCode currency, LocalDate asOfDate) {
		Objects.requireNonNull(term, "term must not be null");
		Objects.requireNonNull(currency, "currency must not be null");
		Objects.requireNonNull(asOfDate, "asOfDate must not be null");
		requireInForce(term, asOfDate);
		requireCurrency(term.currency(), currency, "pricing term " + term.id());
		// The row publishes nothing at all - no price and no bounds. Rejected rather
		// than resolved to zero: zero is a defensible charge for a free item and an
		// indefensible accident for a missing row.
		if (term.unitPrice() == null && term.priceMinimum() == null && term.priceMaximum() == null) {
			throw new BusinessRuleException("pricing term " + term.id() + " has neither a unit price nor a bound");
		}
		// Price scale is the `NUMERIC(20,6)` of the pricing columns, not the
		// `NUMERIC(20,4)` of an amount column. Getting this wrong would round a
		// unit price to four places and make it disagree with the stored row on
		// re-read.
		Money declared = term.unitPrice() == null ? null : MonetaryScale.price(term.unitPrice());
		Money minimum = term.priceMinimum() == null ? null : MonetaryScale.price(term.priceMinimum());
		Money maximum = term.priceMaximum() == null ? null : MonetaryScale.price(term.priceMaximum());
		// A band-only row clamps against its floor as the candidate, so the result
		// is at least the published minimum even with no offered price to judge.
		Money offered = declared == null ? minimum : declared;
		Money clamped = clamp(offered, minimum, maximum, term.id());
		// `clamped` is only set when a published price actually moved. A band-only
		// row that resolves to its own floor is reported as unclamped, because the
		// floor was the band speaking, not the price being overridden.
		return new ResolvedPrice(clamped, declared, minimum, maximum,
				declared != null && !clamped.equals(declared), term.id(), term.pricingType(), term.termVersion(),
				term.effectiveWindow());
	}

	/**
	 * Applies a term's band to a price the caller already has, such as a negotiated
	 * or imported one. The offered price wins when it is inside the band, so a band
	 * is a floor and a ceiling rather than a replacement price.
	 *
	 * <p>Two refusals happen before any arithmetic, because both would otherwise
	 * produce a number nobody agreed to:
	 *
	 * <ul>
	 * <li><strong>An offered price is only an input where the type says so.</strong>
	 * {@link PricingType#acceptsCandidatePrice()} is true only for
	 * {@link PricingType#Tiered}, whose rate is computed elsewhere from a volume
	 * band. A {@code FIXED_UNIT}, {@code USAGE_BASED} or {@code FLAT_FEE} row
	 * publishes an agreed price, and quietly clamping an offer against it would let
	 * a caller charge something the contract never agreed to. This check was
	 * missing, which left {@code acceptsCandidatePrice()} documented and
	 * unenforced - the offered price simply won the band comparison.</li>
	 * <li><strong>The offered price has to be in the contract's currency.</strong>
	 * Checked here rather than left to the comparison, so a cross-currency offer is
	 * reported as a refused request with both currencies named instead of surfacing
	 * as an {@code IllegalArgumentException} from deep inside a
	 * {@code Money.compareTo}.</li>
	 * </ul>
	 */
	public ResolvedPrice clampTo(PricingTerm term, Money offeredPrice, CurrencyCode currency, LocalDate asOfDate) {
		Objects.requireNonNull(term, "term must not be null");
		Objects.requireNonNull(offeredPrice, "offeredPrice must not be null");
		Objects.requireNonNull(currency, "currency must not be null");
		Objects.requireNonNull(asOfDate, "asOfDate must not be null");
		requireInForce(term, asOfDate);
		requireCurrency(term.currency(), currency, "pricing term " + term.id());
		if (!term.pricingType().acceptsCandidatePrice()) {
			throw new BusinessRuleException("pricing term " + term.id() + " is " + term.pricingType().code()
					+ ", which publishes its own price and does not accept a candidate price");
		}
		if (!offeredPrice.currency().equals(currency)) {
			throw new BusinessRuleException("offered price is in " + offeredPrice.currency().value()
					+ " and does not match term currency " + currency.value());
		}
		Money normalised = MonetaryScale.price(offeredPrice);
		Money minimum = term.priceMinimum() == null ? null : MonetaryScale.price(term.priceMinimum());
		Money maximum = term.priceMaximum() == null ? null : MonetaryScale.price(term.priceMaximum());
		Money clamped = clamp(normalised, minimum, maximum, term.id());
		return new ResolvedPrice(clamped, normalised, minimum, maximum, !clamped.equals(normalised), term.id(),
				term.pricingType(), term.termVersion(), term.effectiveWindow());
	}

	/**
	 * Selects the applicable price line and resolves it, all in one step.
	 *
	 * @throws BusinessRuleException if no price line applies, which is deliberately
	 * fatal: a missing price has no correct default, and guessing one invents a
	 * revenue number
	 */
	public ResolvedPrice resolveFor(List<PricingTerm> terms, @Nullable UUID productId, @Nullable UUID customerId,
			CurrencyCode currency, LocalDate asOfDate) {
		PricingTerm term = this.selector.selectInForce(terms, productId, customerId, asOfDate)
				.orElseThrow(() -> new BusinessRuleException("no pricing term in force on " + asOfDate
						+ " for product " + (productId == null ? "-" : productId)));
		return resolve(term, currency, asOfDate);
	}

	/**
	 * Every applicable price line, most specific first, resolved independently.
	 *
	 * <p>Used when the losing candidates matter too, for instance to show what a
	 * negotiated rate was overriding.
	 */
	public List<ResolvedPrice> resolveAll(List<PricingTerm> terms, @Nullable UUID productId,
			@Nullable UUID customerId, CurrencyCode currency, LocalDate asOfDate) {
		return this.selector.candidatesInForce(terms, productId, customerId, asOfDate).stream()
				.map(term -> resolve(term, currency, asOfDate))
				.toList();
	}

	/**
	 * Clamps into the band. Bounds are applied in the order floor then ceiling, and
	 * {@code PricingTerm} already guarantees minimum does not exceed maximum, so the
	 * result cannot depend on which order the caller checks them in.
	 */
	private static Money clamp(@Nullable Money offered, @Nullable Money minimum, @Nullable Money maximum, UUID termId) {
		if (offered == null) {
			// Unreachable from resolve()/clampTo(), which always supply a candidate;
			// kept so a future caller passing null gets a reason rather than a
			// NullPointerException from the comparisons below.
			throw new BusinessRuleException("pricing term " + termId + " offers no price to clamp");
		}
		Money result = offered;
		// Floor first, ceiling second. PricingTerm already guarantees
		// minimum <= maximum, so the two checks cannot fight over the result.
		if (minimum != null && result.compareTo(minimum) < 0) {
			// Strictly below, so a price exactly on the bound is not reported as
			// clamped - it was inside the band.
			result = minimum;
		}
		if (maximum != null && result.compareTo(maximum) > 0) {
			result = maximum;
		}
		return result;
	}

	private static void requireCurrency(@Nullable CurrencyCode termCurrency, CurrencyCode requested, String subject) {
		// A null term currency is tolerated only for the shape V5 allows; a
		// non-null mismatch is always refused. No conversion path exists anywhere in
		// this module, so this is the boundary at which a cross-currency request
		// stops.
		if (termCurrency != null && !termCurrency.equals(requested)) {
			throw new BusinessRuleException(subject + " is in " + termCurrency.value() + " but was requested in "
					+ requested.value());
		}
	}

	/**
	 * A term is only usable if its own window covers the date. Checked inside every
	 * entry point so a caller that filtered terms by hand cannot reach a different
	 * answer here than {@link #resolveFor} would have given.
	 */
	private static void requireInForce(ScopedTerm term, LocalDate asOfDate) {
		if (!term.effectiveWindow().contains(asOfDate)) {
			throw new BusinessRuleException("pricing term " + term.id() + " is not in force on " + asOfDate);
		}
	}

}
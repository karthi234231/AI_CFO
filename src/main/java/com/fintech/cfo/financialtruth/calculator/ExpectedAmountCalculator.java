package com.fintech.cfo.financialtruth.calculator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import com.fintech.cfo.financialtruth.model.DiscountTerm;
import com.fintech.cfo.financialtruth.model.InvoiceLineInput;
import com.fintech.cfo.financialtruth.model.PricingTerm;
import com.fintech.cfo.financialtruth.model.RoundingPolicy;
import com.fintech.cfo.shared.domain.CurrencyCode;
import com.fintech.cfo.shared.domain.Money;
import com.fintech.cfo.shared.exception.BusinessRuleException;
import com.fintech.cfo.shared.exception.ValidationException;

/**
 * Turns contract terms into the amount that should have been charged.
 *
 * <p>Pure and side-effect free: the same line and the same terms always produce the
 * same {@link Money}, and no method here reads a clock or touches a repository.
 *
 * <h2>Rounding</h2>
 * Every amount returned has already been rounded exactly once, to
 * {@link RoundingPolicy#MONETARY_SCALE} decimal places with
 * {@link RoundingPolicy#ROUNDING_MODE}, after its operands have been normalised to
 * their storage scales. Nothing is left for a caller to round later, so no caller
 * can round differently.
 *
 * <h2>What this class refuses to do</h2>
 * It never invents a price, never defaults a missing term to zero, and never
 * converts between currencies. Each of those raises.
 */
public final class ExpectedAmountCalculator {

	private static final BigDecimal HUNDRED = new BigDecimal("100");

	private final int monetaryScale;
	private final RoundingMode roundingMode;

	public ExpectedAmountCalculator() {
		this(RoundingPolicy.MONETARY_SCALE, RoundingPolicy.ROUNDING_MODE);
	}

	/**
	 * @param monetaryScale decimal places every produced amount is rounded to
	 * @param roundingMode  the single rounding mode this calculator applies
	 */
	public ExpectedAmountCalculator(int monetaryScale, RoundingMode roundingMode) {
		if (monetaryScale < 0) {
			throw new ValidationException("monetaryScale must not be negative");
		}
		if (roundingMode == null) {
			throw new ValidationException("roundingMode must not be null; implicit rounding is not permitted");
		}
		this.monetaryScale = monetaryScale;
		this.roundingMode = roundingMode;
	}

	/**
	 * Contract gross for a line: contracted unit price multiplied by quantity.
	 *
	 * @throws BusinessRuleException if the contract price is outside the bounds the
	 *                               contract itself declares, or the quantity is
	 *                               unusable
	 */
	public Money expectedGrossAmount(InvoiceLineInput line, PricingTerm term) {
		requireUsableQuantity(line);
		if (term == null) {
			throw new BusinessRuleException(
					"no contract pricing term is in force; refusing to invent an expected price");
		}
		if (term.unitPrice() == null) {
			throw new BusinessRuleException("pricing term " + term.termId() + " carries no unit price");
		}
		term.assertWithinDeclaredBounds();
		// Normalise both operands to their storage scales before multiplying. Doing it
		// here rather than leaving it to the product is what keeps the arithmetic
		// independent of how many decimal places the source happened to supply.
		BigDecimal unitPrice = RoundingPolicy.roundUnitPrice(term.unitPrice());
		BigDecimal quantity = RoundingPolicy.roundQuantity(line.quantity());
		// The single rounding step for this component, applied at the point the amount
		// is produced. Nothing downstream re-rounds, so no caller can round differently.
		return Money.of(unitPrice, term.currency()).multiply(quantity).withScale(this.monetaryScale, this.roundingMode);
	}

	/**
	 * The discount one term entitles for a given contract gross.
	 *
	 * <p>A percentage is applied to the gross at {@link RoundingPolicy#DISCOUNT_RATE_SCALE}
	 * before the single final rounding step, so the recurring expansion of rates such
	 * as one third of a percent cannot decide the answer. A fixed amount is taken as
	 * written. Either way the result is capped at the gross, because a discount can
	 * never drive a payable below zero.
	 *
	 * <p>A percentage discount always inherits the currency of the gross it reduces,
	 * which is why no separate line currency is needed here.
	 *
	 * @throws BusinessRuleException on an unusable term value or a currency conflict
	 */
	public Money expectedDiscountAmount(Money gross, DiscountTerm term) {
		if (gross == null) {
			throw new ValidationException("gross amount must not be null");
		}
		if (term == null) {
			throw new ValidationException("discount term must not be null");
		}
		BigDecimal value = term.discountValue();
		if (value == null || value.signum() < 0) {
			throw new BusinessRuleException("discount term " + term.termId() + " carries no usable discount value");
		}
		Money discount = switch (term.discountType()) {
			case PERCENTAGE -> {
				if (value.compareTo(HUNDRED) > 0) {
					throw new BusinessRuleException("discount term " + term.termId() + " entitles "
							+ value.toPlainString() + "% which exceeds the gross it is applied to");
				}
				// Divided to DISCOUNT_RATE_SCALE, not to monetary scale. A rate such as
				// 1/3 percent is a recurring expansion; cutting it to 4dp first would let
				// the rounding of the rate decide the rounding of the money.
				BigDecimal rate = value.divide(HUNDRED, RoundingPolicy.DISCOUNT_RATE_SCALE, this.roundingMode);
				// Inherits the gross's currency, which is why no line currency is needed.
				yield gross.multiply(rate).withScale(this.monetaryScale, this.roundingMode);
			}
			case FIXED_AMOUNT -> {
				// A fixed credit is an amount, not a rate: taken exactly as contracted,
				// then validated against the currency it will be combined with.
				requireSameCurrency(gross, term.currency(), "discount term " + term.termId());
				yield Money.of(value, term.currency()).withScale(this.monetaryScale, this.roundingMode);
			}
		};
		return clamp(discount, term, gross);
	}

	/**
	 * The entitlement for every discount term in force, applied in the order supplied
	 * and summed from already-rounded components.
	 *
	 * @throws BusinessRuleException if any term in the stack is unusable
	 * @return the summed entitlement in the gross's currency
	 */
	public Money expectedDiscountAmount(Money gross, List<DiscountTerm> terms) {
		Money total = Money.zero(gross.currency());
		// Applied in the fixed order CalculationInput already established, folding
		// already-rounded per-term amounts. Each term is clamped against the same gross
		// rather than against the running total, so the sequence cannot change the
		// per-term entitlements themselves.
		for (DiscountTerm term : terms) {
			total = total.add(expectedDiscountAmount(gross, term));
		}
		// Final rounding is a settle only; summing 4dp values yields at most 4dp.
		return total.withScale(this.monetaryScale, this.roundingMode);
	}

	/**
	 * What the customer should have paid: gross less the entitled discount, plus tax.
	 *
	 * @throws BusinessRuleException if any component is in a different currency
	 */
	public Money expectedNetAmount(Money gross, Money discount, Money tax) {
		// gross - discount first, then tax added. Tax is a pass-through, so it never
		// influences the variance; it is only part of the payable.
		Money net = gross.subtract(requireSameCurrency(gross, discount, "expected discount"));
		if (tax == null) {
			return net;
		}
		return net.add(requireSameCurrency(gross, tax, "expected tax"))
				.withScale(this.monetaryScale, this.roundingMode);
	}

	/**
	 * Applies a term's own monetary cap, then the hard ceiling that a discount cannot
	 * exceed the gross. Clamping rather than failing is deliberate: contracts
	 * routinely promise a credit greater than a particular line, and the payable
	 * still has to be arithmetically sound. The caller records the clamp in the
	 * derivation so a reader can see the contract asked for more than the line carried.
	 */
	private Money clamp(Money discount, DiscountTerm term, Money gross) {
		Money clamped = discount;
		if (term.maxDiscountAmount() != null) {
			Money cap = Money.of(term.maxDiscountAmount().setScale(this.monetaryScale, this.roundingMode),
					requireSameCurrency(gross, term.currency(), "maximum discount on term " + term.termId()));
			if (cap.amount().signum() < 0) {
				throw new BusinessRuleException("discount term " + term.termId() + " has a negative maximum discount");
			}
			if (clamped.compareTo(cap) > 0) {
				clamped = cap;
			}
		}
		if (clamped.compareTo(gross) > 0) {
			// Hard ceiling. A discount that exceeds the gross would make the net payable
			// negative, which is arithmetically absurd even if a contract asked for it.
			return gross;
		}
		// Re-applied so the returned scale matches every other component, whichever
		// branch produced the value above.
		return clamped.withScale(this.monetaryScale, this.roundingMode);
	}

	private static void requireUsableQuantity(InvoiceLineInput line) {
		if (line == null) {
			throw new ValidationException("line must not be null");
		}
		if (!line.hasUsableQuantity()) {
			throw new BusinessRuleException("line " + line.lineNumber()
					+ " has no usable quantity; an expected amount cannot be derived from it");
		}
	}

	private static Money requireSameCurrency(Money reference, Money candidate, String what) {
		if (candidate == null) {
			throw new ValidationException(what + " must not be null");
		}
		if (!reference.currency().equals(candidate.currency())) {
			throw new BusinessRuleException(what + " is in " + candidate.currency().value()
					+ " but the amount it is combined with is in " + reference.currency().value()
					+ "; this module never converts between currencies");
		}
		return candidate;
	}

	/**
	 * The same currency guard for a contract term, which names a currency rather than
	 * carrying an amount. A fixed discount or a discount cap in another currency cannot
	 * be combined with a gross in this one, so the conflict raises rather than being
	 * silently dropped.
	 */
	private static CurrencyCode requireSameCurrency(Money reference, CurrencyCode candidate, String what) {
		if (candidate == null) {
			throw new ValidationException(what + " must carry a currency");
		}
		if (!reference.currency().equals(candidate)) {
			throw new BusinessRuleException(what + " is in " + candidate.value()
					+ " but the amount it is combined with is in " + reference.currency().value()
					+ "; this module never converts between currencies");
		}
		return candidate;
	}

}
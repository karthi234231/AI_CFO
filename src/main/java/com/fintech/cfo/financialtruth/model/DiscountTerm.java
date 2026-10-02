package com.fintech.cfo.financialtruth.model;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.fintech.cfo.financialtruth.enums.DiscountType;
import com.fintech.cfo.shared.domain.CurrencyCode;
import com.fintech.cfo.shared.exception.ValidationException;

/**
 * A versioned contract discount term: the authority for how much a line should
 * have been discounted on a given business date.
 *
 * <p>The currency is only meaningful for {@link DiscountType#FIXED_AMOUNT}; a
 * percentage inherits the currency of the amount it is applied to. A term that
 * declares a monetary cap must declare a currency too, because a cap without a
 * currency is a number with no meaning.
 *
 * @param productKey product or SKU this discount applies to; {@code null} means the
 *                   contract-wide discount
 */
public record DiscountTerm(
		String productKey,
		String termId,
		DiscountType discountType,
		BigDecimal discountValue,
		BigDecimal maxDiscountAmount,
		CurrencyCode currency,
		LocalDate effectiveFrom,
		LocalDate effectiveTo,
		int termVersion) {

	private static final BigDecimal HUNDRED = new BigDecimal("100");

	public DiscountTerm {
		termId = requireText(termId, "termId");
		if (discountType == null) {
			throw new ValidationException("discountType must not be null for term " + termId);
		}
		if (effectiveFrom == null) {
			throw new ValidationException("effectiveFrom must not be null for term " + termId);
		}
		if (effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) {
			throw new ValidationException("effectiveTo must not be before effectiveFrom for term " + termId);
		}
		if (termVersion <= 0) {
			throw new ValidationException("termVersion must be greater than 0 for term " + termId);
		}
		if (discountType == DiscountType.FIXED_AMOUNT && currency == null) {
			throw new ValidationException("a FIXED_AMOUNT discount term must carry a currency: " + termId);
		}
		// A cap is a monetary figure, so it carries the same currency requirement as a
		// fixed amount even when the discount itself is a percentage.
		if (maxDiscountAmount != null && currency == null) {
			throw new ValidationException("a capped discount term must carry a currency: " + termId);
		}
		// Negative credits are rejected at construction. A negative discount is not a
		// surcharge - it would be one, silently, under a name that promises a benefit.
		if (discountValue != null && discountValue.signum() < 0) {
			throw new ValidationException("discountValue must not be negative for term " + termId);
		}
	}

	/**
	 * Builds a percentage term. The currency is deliberately null: a percentage inherits
	 * the currency of the gross it reduces, so declaring one would be asserting
	 * something the contract does not say.
	 *
	 * @param percent the rate as a decimal string, parsed to avoid binary floating point
	 */
	public static DiscountTerm percentage(String productKey, String termId, String percent, LocalDate effectiveFrom,
			LocalDate effectiveTo, int termVersion) {
		return new DiscountTerm(productKey, termId, DiscountType.PERCENTAGE, new BigDecimal(percent), null, null,
				effectiveFrom, effectiveTo, termVersion);
	}

	/**
	 * Builds a fixed-amount term, which unlike a percentage must declare its currency.
	 *
	 * @param amount the credit as a decimal string, parsed to avoid binary floating point
	 */
	public static DiscountTerm fixedAmount(String productKey, String termId, String amount, CurrencyCode currency,
			LocalDate effectiveFrom, LocalDate effectiveTo, int termVersion) {
		return new DiscountTerm(productKey, termId, DiscountType.FIXED_AMOUNT, new BigDecimal(amount), null, currency,
				effectiveFrom, effectiveTo, termVersion);
	}

	/** As {@link PricingTerm#isEffectiveOn}, inclusive at both ends of the window. */
	public boolean isEffectiveOn(LocalDate asOf) {
		if (asOf == null) {
			throw new ValidationException("asOf date must not be null");
		}
		// effectiveTo null means open-ended, which is why the upper bound test is
		// guarded rather than compared against a sentinel date.
		return !asOf.isBefore(this.effectiveFrom) && (this.effectiveTo == null || !asOf.isAfter(this.effectiveTo));
	}

	/** The proof recorded on a result so a historical figure can be re-derived. */
	public TermEvaluation toEvaluation() {
		return new TermEvaluation(this.termId, this.discountType.name(), this.termVersion, this.effectiveFrom,
				this.effectiveTo);
	}

	/**
	 * Whether the term actually says anything usable. A percentage with no value, or
	 * a value above 100%, cannot be honoured; the rule reports that as incomplete
	 * input rather than applying a discount nobody agreed to.
	 */
	public boolean hasUsableValue() {
		if (this.discountValue == null) {
			return false;
		}
		if (this.discountType == DiscountType.FIXED_AMOUNT) {
			// No ceiling check: a fixed credit is bounded by the gross itself, which the
			// calculator clamps against after the term is applied.
			return true;
		}
		// Above 100% a percentage would credit more than was charged. That may be a
		// contract error rather than an intent, so it is reported as unusable input
		// rather than honoured.
		return this.discountValue.compareTo(HUNDRED) <= 0;
	}

	private static String requireText(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new ValidationException(field + " must not be blank");
		}
		return value.trim();
	}

}
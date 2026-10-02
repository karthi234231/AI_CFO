package com.fintech.cfo.contract.service;

import java.math.RoundingMode;
import java.util.Objects;

import com.fintech.cfo.shared.domain.Money;

/**
 * The single declaration of this module's money scale and rounding policy.
 *
 * <p>Scales come straight from V5: {@code pricing_terms.unit_price},
 * {@code price_minimum} and {@code price_maximum} are {@code NUMERIC(20,6)} while
 * amount columns such as {@code discount_terms.max_discount_amount} are
 * {@code NUMERIC(20,4)}. A value is handed out at the scale of the column it would
 * be persisted to, so a result that round-trips through this module and back into
 * the database is identical, and a re-run reproduces it exactly.
 *
 * <p><strong>Rounding is HALF_UP at every hand-out.</strong> The alternatives were
 * rejected deliberately:
 *
 * <ul>
 * <li>{@code HALF_EVEN} is unbiased in aggregate, which sounds ideal, but it
 * rounds an exact half downwards and so systematically favours the customer over
 * the supplier on every half unit. For output that becomes billed amounts that is
 * the wrong direction.</li>
 * <li>{@code FLOOR} and {@code CEILING} keep the half unit but move the whole
 * sub-unit remainder, which biases the discount path towards over-charging in
 * aggregate.</li>
 * <li>{@code UNNECESSARY} is not available for money: it throws, and a calculation
 * must not fail because of a rounding artifact.</li>
 * </ul>
 *
 * <p>Rounding happens exactly once per value, at the end of the arithmetic.
 * Intermediates are kept at working precision, so a hand-out rounding is never
 * preceded by a hidden second one.
 */
public final class MonetaryScale {

	/**
	 * Scale of {@code NUMERIC(20,6)} price columns: unit prices and price bounds.
	 */
	public static final int PRICE_SCALE = 6;

	/**
	 * Scale of {@code NUMERIC(20,4)} amount columns: gross, discount and net
	 * amounts, discount caps, and monetary rule parameters.
	 */
	public static final int AMOUNT_SCALE = 4;

	/**
	 * Working precision for intermediate multiplication. Applied once, and always
	 * paired with a {@link #AMOUNT_SCALE} hand-out.
	 *
	 * <p>DECIMAL128 rather than unlimited precision: it is enough that no practical
	 * discount calculation loses a meaningful digit, and unlike an unlimited
	 * MathContext it cannot allocate unbounded working memory for a hostile input.
	 */
	public static final java.math.MathContext WORKING_PRECISION = java.math.MathContext.DECIMAL128;

	/**
	 * The rounding applied together with an explicit scale. Never used on its own.
	 */
	public static final RoundingMode ROUNDING_MODE = RoundingMode.HALF_UP;

	private MonetaryScale() {
	}

	/**
	 * Normalises a price to {@link #PRICE_SCALE}.
	 */
	public static Money price(Money amount) {
		Objects.requireNonNull(amount, "amount must not be null");
		return amount.withScale(PRICE_SCALE, ROUNDING_MODE);
	}

	/**
	 * Normalises an amount to {@link #AMOUNT_SCALE}.
	 */
	public static Money amount(Money amount) {
		Objects.requireNonNull(amount, "amount must not be null");
		return amount.withScale(AMOUNT_SCALE, ROUNDING_MODE);
	}

}

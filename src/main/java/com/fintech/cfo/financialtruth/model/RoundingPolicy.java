package com.fintech.cfo.financialtruth.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.fintech.cfo.shared.domain.Money;

/**
 * The module's single, deliberately chosen rounding and scale policy.
 *
 * <h2>The policy</h2>
 * <ol>
 * <li>Unit prices and quantities are normalised to their storage scale first:
 * {@link #UNIT_PRICE_SCALE} and {@link #QUANTITY_SCALE} decimal places, matching
 * {@code NUMERIC(20,6)} in {@code V4__create_financial_data.sql} and
 * {@code V5__create_contracts.sql}.</li>
 * <li>Every <em>line component</em> (gross, discount, tax, net) is rounded exactly
 * once, at the point it is produced, to {@link #MONETARY_SCALE} decimal places
 * with {@link #ROUNDING_MODE}.</li>
 * <li>Totals are the plain sum of already-rounded line components. Nothing is
 * rounded again at aggregation time, because re-rounding an already-rounded sum
 * would make a total depend on the order its lines were presented in.</li>
 * </ol>
 *
 * <h2>Why these choices</h2>
 * <ul>
 * <li>{@link #MONETARY_SCALE} of 4 matches {@code NUMERIC(20,4)} for every money
 * column in {@code V6__create_calculations.sql}, so a stored figure is byte-identical
 * to the figure that was calculated. No precision is lost on the way to the
 * database and none has to be invented on the way back.</li>
 * <li>{@link RoundingMode#HALF_UP} is the commercial norm and, crucially, is
 * symmetric about zero and independent of magnitude. It does not introduce the
 * systematic upward drift that {@code CEILING} would, nor the downward drift of
 * {@code DOWN}.</li>
 * <li>Rounding per component rather than only at the end means the arithmetic is
 * associative: {@code (a + b) + c} and {@code a + (b + c)} are identical, so an
 * invoice's total never depends on line ordering.</li>
 * </ul>
 */
public final class RoundingPolicy {

	/** Decimal places for any monetary amount, matching {@code NUMERIC(20,4)}. */
	public static final int MONETARY_SCALE = 4;

	/** Decimal places for a quantity, matching {@code NUMERIC(20,6)}. */
	public static final int QUANTITY_SCALE = 6;

	/** Decimal places for a unit price, matching {@code NUMERIC(20,6)}. */
	public static final int UNIT_PRICE_SCALE = 6;

	/** The one rounding mode used by every calculation in this module. */
	public static final RoundingMode ROUNDING_MODE = RoundingMode.HALF_UP;

	/**
	 * Extra digits kept while a percentage rate is turned into a multiplier.
	 *
	 * <p>Dividing by 100 introduces a recurring expansion for rates such as 1/3
	 * percent. Ten extra digits push that error far below {@link #MONETARY_SCALE}
	 * so the subsequent single rounding step is not deciding the answer.
	 */
	public static final int DISCOUNT_RATE_SCALE = 10;

	/**
	 * Marker for an absent value in a canonical form.
	 *
	 * <p>One token for both {@code null} and blank text, so a checksum cannot change
	 * merely because an optional field was read as {@code null} one time and as
	 * {@code ""} another. The dash is deliberately not a digit, so an absent value
	 * can never collide with a numeric field.
	 */
	private static final String ABSENT = "-";

	private RoundingPolicy() {
	}

	/** Applies the monetary policy to an already-computed amount. */
	public static Money round(Money amount) {
		return amount.withScale(MONETARY_SCALE, ROUNDING_MODE);
	}

	/** Normalises a quantity to {@link #QUANTITY_SCALE}, before it multiplies anything. */
	public static BigDecimal roundQuantity(BigDecimal quantity) {
		return quantity.setScale(QUANTITY_SCALE, ROUNDING_MODE);
	}

	/** Normalises a unit price to {@link #UNIT_PRICE_SCALE}, before it multiplies anything. */
	public static BigDecimal roundUnitPrice(BigDecimal unitPrice) {
		return unitPrice.setScale(UNIT_PRICE_SCALE, ROUNDING_MODE);
	}

	/**
	 * Scale-insensitive textual form of a number, used when building checksums.
	 *
	 * <p>A checksum that changed because a value was re-read from the database as
	 * {@code 920.0} instead of {@code 920.00} would report a spurious input change
	 * and break reproducibility proofs, so trailing zeros are removed before
	 * hashing. Exponent notation is never produced because
	 * {@link BigDecimal#toPlainString()} is used rather than {@code toString()}.
	 */
	public static String canonicalNumber(BigDecimal value) {
		return value == null ? ABSENT : value.stripTrailingZeros().toPlainString();
	}

	/** Scale-insensitive textual form of money, always carrying its currency. */
	public static String canonicalMoney(Money value) {
		if (value == null) {
			return ABSENT;
		}
		// Amount first, currency second, space separated. The order is fixed so the
		// digest of two money fields cannot be rearranged into a collision.
		return canonicalNumber(value.amount()) + " " + value.currency().value();
	}

	/** Canonical form of optional text; blank and {@code null} collapse to one marker. */
	public static String canonicalText(String value) {
		return value == null || value.isBlank() ? ABSENT : value;
	}

	/** Canonical form of an optional date. */
	public static String canonicalDate(java.time.LocalDate value) {
		return value == null ? ABSENT : value.toString();
	}

}
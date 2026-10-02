package com.fintech.cfo.shared.domain;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Immutable monetary amount bound to a single currency.
 *
 * <p>This is the foundational type of the financial truth engine. It is
 * deliberately strict: arithmetic is only permitted between values of the
 * same currency, division requires an explicit scale and rounding mode, and
 * {@code double}/{@code float} are never used.
 *
 * <p>A final class rather than a record because it carries arithmetic that must be
 * guarded. A record would auto-generate {@code equals}/{@code hashCode} over
 * {@link BigDecimal}, which compares {@code 100} and {@code 1E+2} as unequal and
 * would make two representations of the same amount compare unequal in a
 * collection.
 */
public final class Money implements Serializable, Comparable<Money> {

	private static final long serialVersionUID = 1L;

	private final BigDecimal amount;
	private final CurrencyCode currency;

	private Money(BigDecimal amount, CurrencyCode currency) {
		this.amount = Objects.requireNonNull(amount, "amount must not be null");
		this.currency = Objects.requireNonNull(currency, "currency must not be null");
	}

	/**
	 * @param amount   amount, retained at whatever scale the caller supplied
	 * @param currency currency the amount is denominated in
	 */
	public static Money of(BigDecimal amount, CurrencyCode currency) {
		return new Money(amount, currency);
	}

	/**
	 * Parses the amount from text.
	 *
	 * <p>Trims before parsing so a value carried over from a spreadsheet cell
	 * (a very common ingestion source) does not fail on surrounding whitespace.
	 *
	 * @param amount   decimal text
	 * @param currency currency the amount is denominated in
	 * @throws NumberFormatException if the text is not a decimal
	 */
	public static Money of(String amount, CurrencyCode currency) {
		Objects.requireNonNull(amount, "amount must not be null");
		return new Money(new BigDecimal(amount.trim()), currency);
	}

	/** @return a zero amount in the given currency; the additive identity for that currency */
	public static Money zero(CurrencyCode currency) {
		return new Money(BigDecimal.ZERO, currency);
	}

	/** @return the unsigned numeric value, with the scale this instance was built with */
	public BigDecimal amount() {
		return this.amount;
	}

	/** @return the currency this amount is denominated in */
	public CurrencyCode currency() {
		return this.currency;
	}

	/**
	 * @param other amount in the same currency
	 * @return a new sum; never mutates either operand
	 * @throws IllegalArgumentException if the currencies differ
	 */
	public Money add(Money other) {
		return new Money(this.amount.add(requireSameCurrency(other).amount()), this.currency);
	}

	/**
	 * @param other amount in the same currency
	 * @return a new difference; never mutates either operand
	 * @throws IllegalArgumentException if the currencies differ
	 */
	public Money subtract(Money other) {
		return new Money(this.amount.subtract(requireSameCurrency(other).amount()), this.currency);
	}

	/**
	 * Multiplies without an explicit {@link MathContext}.
	 *
	 * <p>Exact: the result carries the sum of the operand scales, so a discount
	 * applied this way is not silently rounded away.
	 *
	 * @param multiplier factor to apply
	 * @return a new scaled amount
	 */
	public Money multiply(BigDecimal multiplier) {
		Objects.requireNonNull(multiplier, "multiplier must not be null");
		return new Money(this.amount.multiply(multiplier), this.currency);
	}

	/**
	 * Multiplies under an explicit {@link MathContext}, for paths that accept a
	 * loss of precision in exchange for a bounded digit count.
	 *
	 * @param multiplier factor to apply
	 * @param context    precision and rounding to apply
	 * @return a new amount
	 */
	public Money multiply(BigDecimal multiplier, MathContext context) {
		Objects.requireNonNull(multiplier, "multiplier must not be null");
		Objects.requireNonNull(context, "context must not be null");
		return new Money(this.amount.multiply(multiplier, context), this.currency);
	}

	/**
	 * Division with mandatory scale and rounding.
	 *
	 * <p>There is deliberately no {@code divide(BigDecimal)}: {@code BigDecimal}
	 * division is exact or an exception, so a caller who omits the rounding mode
	 * gets an {@link ArithmeticException} instead of a financial number.
	 *
	 * @param divisor     factor to divide by
	 * @param scale       result scale
	 * @param roundingMode how to discard a remainder
	 * @return a new amount
	 * @throws IllegalArgumentException if the divisor is zero or the scale is negative
	 */
	public Money divide(BigDecimal divisor, int scale, RoundingMode roundingMode) {
		Objects.requireNonNull(divisor, "divisor must not be null");
		Objects.requireNonNull(roundingMode, "roundingMode must not be null");
		if (divisor.signum() == 0) {
			throw new IllegalArgumentException("divisor must not be zero");
		}
		if (scale < 0) {
			throw new IllegalArgumentException("scale must not be negative");
		}
		return new Money(this.amount.divide(divisor, scale, roundingMode), this.currency);
	}

	/**
	 * @return a new amount with the sign flipped
	 */
	public Money negate() {
		return new Money(this.amount.negate(), this.currency);
	}

	/**
	 * @return a new amount with the sign removed; the magnitude is what caps and
	 *         variance magnitudes compare against
	 */
	public Money abs() {
		return new Money(this.amount.abs(), this.currency);
	}

	/** @return true when the amount is exactly zero, regardless of scale */
	public boolean isZero() {
		return this.amount.signum() == 0;
	}

	/**
	 * @return true when strictly greater than zero. The zero case is excluded
	 *         deliberately: a free line item is not a discount.
	 */
	public boolean isPositive() {
		return this.amount.signum() > 0;
	}

	/** @return true when strictly less than zero */
	public boolean isNegative() {
		return this.amount.signum() < 0;
	}

	/**
	 * @param scale        target scale
	 * @param roundingMode how to discard a remainder
	 * @return a new amount rescaled; this is where a rate or a unit price is
	 *         finally brought to currency precision
	 */
	public Money withScale(int scale, RoundingMode roundingMode) {
		Objects.requireNonNull(roundingMode, "roundingMode must not be null");
		return new Money(this.amount.setScale(scale, roundingMode), this.currency);
	}

	/**
	 * Constrains this amount to {@code [lower, upper]}, inclusive.
	 *
	 * <p>Both bounds must already share this currency. This is the one place a bounded price
	 * or discount is applied, so it enforces the currency of the bounds before comparing rather
	 * than letting the mismatch surface as a raw failure from deep inside a comparison.
	 */
	public Money clamp(Money lower, Money upper) {
		Money low = requireSameCurrency(lower);
		Money high = requireSameCurrency(upper);
		if (low.compareTo(high) > 0) {
			throw new IllegalArgumentException(
					"lower bound " + low + " must not exceed upper bound " + high);
		}
		if (this.compareTo(low) < 0) {
			return low;
		}
		if (this.compareTo(high) > 0) {
			return high;
		}
		return this;
	}

	/**
	 * @param other comparable amount in the same currency
	 * @return the smaller of the two amounts, or this instance when equal
	 * @throws IllegalArgumentException if the currencies differ
	 */
	public Money min(Money other) {
		return this.compareTo(other) <= 0 ? this : other;
	}

	/**
	 * @param other comparable amount in the same currency
	 * @return the larger of the two amounts, or this instance when equal
	 * @throws IllegalArgumentException if the currencies differ
	 */
	public Money max(Money other) {
		return this.compareTo(other) >= 0 ? this : other;
	}

	/** Whether this amount falls within {@code [lower, upper]} inclusive, without allocating. */
	public boolean isBetween(Money lower, Money upper) {
		Money low = requireSameCurrency(lower);
		Money high = requireSameCurrency(upper);
		return this.compareTo(low) >= 0 && this.compareTo(high) <= 0;
	}

	/**
	 * @param other amount to compare by value
	 * @return negative, zero or positive as this amount is less than, equal to
	 *         or greater than the other
	 * @throws IllegalArgumentException if the currencies differ
	 */
	@Override
	public int compareTo(Money other) {
		// compareTo, not equals: BigDecimal.equals is scale-sensitive, so using it
		// here would make 100 and 1E+2 order differently from how they compare.
		return this.amount.compareTo(requireSameCurrency(other).amount());
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof Money that)) {
			return false;
		}
		// compareTo == 0 rather than BigDecimal.equals, so 100 and 100.00 are
		// equal; that is what the financial truth engine expects from an amount.
		return this.amount.compareTo(that.amount) == 0 && this.currency.equals(that.currency);
	}

	/**
	 * Scale-insensitive, matching {@link #equals(Object)}.
	 *
	 * <p>{@code Objects.hash} is avoided deliberately: it allocates a varargs {@code Object[]}
	 * on every call, and this runs for every amount added to a hash collection during variance
	 * aggregation.
	 */
	@Override
	public int hashCode() {
		return 31 * this.amount.stripTrailingZeros().hashCode() + this.currency.hashCode();
	}

	@Override
	public String toString() {
		// toPlainString, not toString: toString switches to scientific notation
		// (1E+2), which is unreadable in an audit record or a log line.
		return this.amount.toPlainString() + " " + this.currency.value();
	}

	/**
	 * The single gate every two-operand operation passes through, so a currency
	 * mismatch is always reported the same way regardless of which method
	 * noticed it.
	 *
	 * @param other operand that must share this amount's currency
	 * @return {@code other}, unchecked
	 * @throws IllegalArgumentException if the currencies differ
	 */
	private Money requireSameCurrency(Money other) {
		Objects.requireNonNull(other, "other money must not be null");
		if (!this.currency.equals(other.currency)) {
			throw new IllegalArgumentException(
					"currency mismatch: " + this.currency.value() + " vs " + other.currency.value());
		}
		return other;
	}

}

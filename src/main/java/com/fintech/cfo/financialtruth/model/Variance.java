package com.fintech.cfo.financialtruth.model;

import java.math.BigDecimal;
import java.util.Optional;

import com.fintech.cfo.financialtruth.enums.ImpactDirection;
import com.fintech.cfo.financialtruth.enums.VarianceType;
import com.fintech.cfo.shared.domain.Money;
import com.fintech.cfo.shared.exception.ValidationException;

/**
 * The difference between what was charged and what was owed.
 *
 * <h2>Sign convention: variance = actual - expected</h2>
 *
 * <p>This module uses one convention everywhere, without exception:
 *
 * <ul>
 * <li><strong>Positive</strong> means the customer was charged <em>more</em> than
 * the contract entitles. The money is recoverable by the customer. Examples: a unit
 * price above the contracted price, or a discount granted that the contract did not
 * authorise.</li>
 * <li><strong>Negative</strong> means the customer was charged less than owed
 * (an undercharge, favourable to the customer).</li>
 * <li><strong>Zero</strong> means the invoice matched the contract exactly.</li>
 * </ul>
 *
 * <p>Note the deliberate asymmetry for {@link VarianceType.Discount}: that component
 * is measured on the discount itself, so a positive value there means the customer
 * received <em>more</em> discount than entitled. Because the net payable subtracts
 * the discount, a positive discount component reduces the net variance. The net
 * figure - {@code pricingVariance - discountVariance} - always keeps the convention
 * above. Keeping both in one class, with the type attached, prevents a reader from
 * applying the customer-facing reading to a component.
 *
 * <p>Variance is a final class rather than a record because it owns a derived
 * invariant a record cannot express: the amount is not a component, it is
 * {@code actual - expected} computed once, in {@code Money}, so that no caller can
 * hand it an amount that disagrees with its own expected and actual figures.
 */
public final class Variance {

	private final Money expected;
	private final Money actual;
	private final Money amount;
	private final VarianceType type;

	private Variance(Money expected, Money actual, VarianceType type) {
		this.expected = expected;
		this.actual = actual;
		this.type = type;
		// Money.subtract raises IllegalArgumentException on a currency mismatch, which
		// is exactly the required behaviour: never convert, never mix.
		this.amount = actual.subtract(expected);
	}

	/**
	 * @param expected what the contract entitles
	 * @param actual   what was charged
	 * @param type     which component this is; carried so a reader cannot misread a
	 *                 discount component as a customer-facing net figure
	 * @return the variance, whose amount is {@code actual - expected}
	 * @throws ValidationException when either amount or the type is absent
	 */
	public static Variance of(Money expected, Money actual, VarianceType type) {
		if (expected == null || actual == null) {
			throw new ValidationException("expected and actual amounts are both required to state a variance");
		}
		if (type == null) {
			throw new ValidationException("variance type must not be null");
		}
		return new Variance(expected, actual, type);
	}

	/** The figure the contract entitles. Carried so the variance can be re-derived. */
	public Money expected() {
		return this.expected;
	}

	/** The figure actually charged. */
	public Money actual() {
		return this.actual;
	}

	/**
	 * The signed difference, {@code actual - expected}.
	 *
	 * <p>Computed once in the constructor and never reassigned, so it cannot drift away
	 * from the two figures it is supposed to be derived from.
	 */
	public Money amount() {
		return this.amount;
	}

	public VarianceType type() {
		return this.type;
	}

	/** True when the customer owes more than the contract says. */
	public boolean isOvercharge() {
		return this.amount.isPositive();
	}

	/** True when the customer owes less than the contract says. */
	public boolean isUndercharge() {
		return this.amount.isNegative();
	}

	public boolean isZero() {
		return this.amount.isZero();
	}

	/** Which way the money moved, derived from the sign rather than supplied. */
	public ImpactDirection direction() {
		return ImpactDirection.of(this.amount.amount());
	}

	/**
	 * Variance as a percentage of the expected amount, rounded to
	 * {@link RoundingPolicy#MONETARY_SCALE}.
	 *
	 * <p>Empty when the expected amount is zero: the ratio is undefined and
	 * reporting infinity would be a fabricated number.
	 */
	public Optional<BigDecimal> percentageOfExpected() {
		if (this.expected.amount().signum() == 0) {
			// Absent, not zero and not infinity. The ratio is undefined, and a number
			// standing in for it would read as "no material deviation".
			return Optional.empty();
		}
		// Multiplied by 100 before dividing, so the scale of the result is a percentage
		// rather than a ratio, and rounded once at monetary scale.
		BigDecimal percentage = this.amount.amount()
				.multiply(HUNDRED)
				.divide(this.expected.amount(), RoundingPolicy.MONETARY_SCALE, RoundingPolicy.ROUNDING_MODE);
		return Optional.of(percentage);
	}

	/**
	 * Fixed-order textual form used inside run and result fingerprints.
	 *
	 * <p>Includes the components as well as the amount, so two variances with the same
	 * amount reached from different inputs produce different fingerprints.
	 */
	public String canonicalForm() {
		return "variance=" + RoundingPolicy.canonicalMoney(this.amount)
				+ "|type=" + this.type.code()
				+ "|expected=" + RoundingPolicy.canonicalMoney(this.expected)
				+ "|actual=" + RoundingPolicy.canonicalMoney(this.actual);
	}

	/**
	 * Value equality across all four fields.
	 *
	 * <p>Compared by {@link Money#equals} rather than by the derived amount alone, so
	 * two variances that agree numerically but disagree on their expected/actual split
	 * are not treated as the same finding.
	 */
	@Override
	public boolean equals(Object other) {
		return other instanceof Variance that
				&& this.amount.equals(that.amount)
				&& this.expected.equals(that.expected)
				&& this.actual.equals(that.actual)
				&& this.type == that.type;
	}

	@Override
	public int hashCode() {
		return java.util.Objects.hash(this.expected, this.actual, this.amount, this.type);
	}

	@Override
	public String toString() {
		return canonicalForm();
	}

	private static final BigDecimal HUNDRED = new BigDecimal("100");

}
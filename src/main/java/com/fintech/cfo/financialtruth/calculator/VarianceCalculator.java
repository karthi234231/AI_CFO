package com.fintech.cfo.financialtruth.calculator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import com.fintech.cfo.financialtruth.enums.VarianceType;
import com.fintech.cfo.financialtruth.model.ActualValue;
import com.fintech.cfo.financialtruth.model.ExpectedValue;
import com.fintech.cfo.financialtruth.model.RoundingPolicy;
import com.fintech.cfo.financialtruth.model.Variance;
import com.fintech.cfo.shared.domain.CurrencyCode;
import com.fintech.cfo.shared.domain.Money;
import com.fintech.cfo.shared.exception.ValidationException;

/**
 * Produces variances and holds the module to its own sign convention.
 *
 * <p>The convention lives in {@link Variance}: {@code variance = actual - expected},
 * so a positive figure always means the customer was charged more than the contract
 * entitles. This calculator's job is to make sure nothing quietly breaks it.
 *
 * <p>{@link #assertReconciled} is the internal consistency guard. The net payable
 * variance must always equal the algebraic combination of its disjoint components.
 * If it does not, the engine has double-counted or dropped something, and the run
 * must fail loudly rather than publish a total nobody can decompose.
 */
public final class VarianceCalculator {

	private final int monetaryScale;
	private final RoundingMode roundingMode;

	public VarianceCalculator() {
		this(RoundingPolicy.MONETARY_SCALE, RoundingPolicy.ROUNDING_MODE);
	}

	public VarianceCalculator(int monetaryScale, RoundingMode roundingMode) {
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
	 * @param expected what the contract entitles
	 * @param actual   what was charged
	 * @param type     which component this is; the type travels with the figure so a
	 *                 reader cannot apply the customer-facing reading of a discount
	 *                 component to it
	 * @return the variance, signed {@code actual - expected}
	 */
	public Variance variance(Money expected, Money actual, VarianceType type) {
		return Variance.of(expected, actual, type);
	}

	/**
	 * Builds a variance from the two value carriers, so lineage travels with it.
	 *
	 * <p>Only the {@code Money} amounts are used; the derivation strings and evaluated
	 * terms are retained on the value carriers and copied onto the result row by
	 * {@code CalculationResult.fromRule}.
	 *
	 * @throws ValidationException when either side is absent; a variance cannot be
	 *                             stated from one figure
	 */
	public Variance variance(ExpectedValue expected, ActualValue actual, VarianceType type) {
		if (expected == null || actual == null) {
			throw new ValidationException("expected and actual values are both required to state a variance");
		}
		return Variance.of(expected.amount(), actual.amount(), type);
	}

	/**
	 * Verifies that {@code netVariance} is exactly {@code components} combined.
	 *
	 * <p>Comparison is by value, not by scale: {@code 100.0000} and {@code 100} are
	 * the same money. A mismatch means the net figure cannot be explained by its
	 * components, which for an audit product is unrecoverable - so it raises.
	 *
	 * @param netVariance the combined figure that will be reported
	 * @param components  the disjoint component figures it must equal
	 * @throws IllegalStateException when the net figure does not reconcile
	 */
	public void assertReconciled(Money netVariance, Money components) {
		if (netVariance == null || components == null) {
			throw new ValidationException("both the net and component variances are required for reconciliation");
		}
		if (!netVariance.currency().equals(components.currency())) {
			// Mismatched currencies would make the comparison below meaningless, so this
			// is checked before the amounts rather than folded into it.
			throw new IllegalStateException("net variance is in " + netVariance.currency().value()
					+ " but its components are in " + components.currency().value());
		}
		// compareTo, not equals: 100.0000 and 100 are the same money and must reconcile.
		if (netVariance.amount().compareTo(components.amount()) != 0) {
			throw new IllegalStateException("net variance " + netVariance.amount().toPlainString()
					+ " does not reconcile with its components " + components.amount().toPlainString()
					+ "; refusing to publish a total that cannot be decomposed");
		}
	}

	/**
	 * Folds disjoint component variances into the single net figure, respecting the
	 * sign flip the discount component needs: the net payable subtracts a discount, so
	 * a positive discount variance reduces the net variance.
	 *
	 * @param pricingVariances gross price deviations, summed directly
	 * @param discountVariances discount deviations, each negated before summing
	 * @param currency         currency of the result
	 * @return the folded net figure at {@link RoundingPolicy#MONETARY_SCALE}
	 */
	public Money netFromComponents(List<Variance> pricingVariances, List<Variance> discountVariances,
			CurrencyCode currency) {
		if (currency == null) {
			throw new ValidationException("currency must not be null; a total without a currency is a bug");
		}
		BigDecimal total = BigDecimal.ZERO;
		for (Variance pricing : pricingVariances) {
			// Straight sum. A positive pricing variance is money wrongly charged, so it
			// adds to what the customer is owed.
			total = total.add(pricing.amount().amount());
		}
		for (Variance discount : discountVariances) {
			// Subtracted, not added: the net payable already removed the discount, so a
			// positive discount variance (too much discount granted) reduces the amount
			// recoverable. This is the single sign flip in the module.
			total = total.subtract(discount.amount().amount());
		}
		// Rounded once, after the fold. The components were each already rounded, so
		// this only settles the addition, never the operands.
		return Money.of(total.setScale(this.monetaryScale, this.roundingMode), currency);
	}

}
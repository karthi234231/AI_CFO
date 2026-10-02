package com.fintech.cfo.financialtruth.rules;

import java.util.ArrayList;
import java.util.List;

import com.fintech.cfo.financialtruth.calculator.ActualAmountCalculator;
import com.fintech.cfo.financialtruth.calculator.ExpectedAmountCalculator;
import com.fintech.cfo.financialtruth.calculator.VarianceCalculator;
import com.fintech.cfo.financialtruth.enums.CalculationType;
import com.fintech.cfo.financialtruth.enums.VarianceType;
import com.fintech.cfo.financialtruth.model.ActualValue;
import com.fintech.cfo.financialtruth.model.DiscountTerm;
import com.fintech.cfo.financialtruth.model.ExpectedValue;
import com.fintech.cfo.financialtruth.model.InvoiceLineInput;
import com.fintech.cfo.financialtruth.model.TermEvaluation;
import com.fintech.cfo.financialtruth.model.Variance;
import com.fintech.cfo.shared.domain.Money;
import com.fintech.cfo.shared.exception.BusinessRuleException;

/**
 * Compares the discount actually granted against the discount the contract entitled.
 *
 * <h2>Scope and sign</h2>
 * This rule measures the <em>discount component</em> of the line, disjoint from
 * {@link PricingVarianceRule}, which measures the gross. A positive figure here means
 * the customer received <em>more</em> discount than contracted, so it works against
 * the supplier. The engine negates it when folding components into the net payable,
 * which keeps the module-wide convention intact: a positive net variance is always
 * money the customer is owed back.
 *
 * <h2>Version 1.0.0 arithmetic</h2>
 * <pre>
 * entitlement base = contract gross from PricingVarianceRule, or recomputed here
 * expected discount = per term: PERCENTAGE of base, or the FIXED_AMOUNT as written;
 *                    clamped by the term's own cap and then by the base itself;
 *                    terms applied in the fixed order recorded on the input
 * actual discount   = invoice_lines.discount_amount, HALF_UP to 4dp
 * variance          = actual discount - expected discount
 * </pre>
 *
 * <h2>Failure behaviour</h2>
 * <ul>
 * <li>No discount term in force: {@code NOT_APPLICABLE}. The contract entitles no
 * discount, which is a true statement rather than a zero variance. The engine then
 * treats the entitlement as zero, which is correct - an absent entitlement is not a
 * fabricated one.</li>
 * <li>A term exists but its value is missing, negative or above 100%:
 * {@code INCOMPLETE_INPUTS}, with the reason recorded.</li>
 * <li>No contract price in force, so there is no base to take a percentage of:
 * {@code INCOMPLETE_INPUTS}. Reached when this rule is used on its own; in a full
 * engine run {@link PricingVarianceRule} has already raised by this point.</li>
 * <li>A fixed-amount term in a currency other than the line's:
 * <strong>raises</strong> {@code BusinessRuleException}.</li>
 * </ul>
 */
public final class DiscountVarianceRule implements FinancialRule {

	public static final String CODE = "DISCOUNT_VARIANCE";

	public static final String VERSION = "1.0.0";

	private final ExpectedAmountCalculator expectedAmountCalculator;
	private final ActualAmountCalculator actualAmountCalculator;
	private final VarianceCalculator varianceCalculator;

	public DiscountVarianceRule(ExpectedAmountCalculator expectedAmountCalculator,
			ActualAmountCalculator actualAmountCalculator, VarianceCalculator varianceCalculator) {
		this.expectedAmountCalculator = expectedAmountCalculator;
		this.actualAmountCalculator = actualAmountCalculator;
		this.varianceCalculator = varianceCalculator;
	}

	@Override
	public String code() {
		return CODE;
	}

	@Override
	public String version() {
		return VERSION;
	}

	@Override
	public CalculationType calculationType() {
		return CalculationType.DISCOUNT_VARIANCE;
	}

	/**
	 * Applies only when the contract actually grants a discount.
	 *
	 * <p>An empty term list makes this rule genuinely irrelevant rather than
	 * zero-valued, which is why it returns {@code NOT_APPLICABLE} downstream.
	 */
	@Override
	public boolean appliesTo(RuleContext context) {
		return context != null && context.line() != null && !context.discountTerms().isEmpty();
	}

	@Override
	public RuleEvaluationResult evaluate(RuleContext context) {
		// Re-checked here even though the engine already tested appliesTo, so the rule
		// is correct when invoked directly - by a unit test, or by a future caller that
		// does not pre-filter.
		if (!this.appliesTo(context)) {
			return RuleEvaluationResult.notApplicable(this.calculationType(),
					"No discount term was in force for this product on " + context.asOfDate()
							+ ", so the contract entitles no discount");
		}
		InvoiceLineInput line = context.line();
		// Already filtered to the as-of date and already in the fixed application order
		// by CalculationInput. Re-deriving that order here would let a rule invent a
		// different stacking sequence from the one recorded on the snapshot.
		List<DiscountTerm> terms = context.discountTerms();
		if (!line.hasUsableQuantity()) {
			return RuleEvaluationResult.incompleteInputs(this.calculationType(),
					"Line " + line.lineNumber() + " has no usable quantity, so no discount entitlement can be derived");
		}
		if (context.pricingTerm() == null) {
			// A percentage has nothing to take a percentage of. Reported, not raised: in a
			// full engine run PricingVarianceRule has already raised before this point.
			return RuleEvaluationResult.incompleteInputs(this.calculationType(), "No contract pricing term was in force on "
					+ context.asOfDate() + ", so there is no contracted gross to take a discount entitlement against");
		}

		// All terms validated before any is applied. A partial stack would produce an
		// entitlement that is neither what the contract says nor zero.
		for (DiscountTerm term : terms) {
			if (!term.hasUsableValue()) {
				return RuleEvaluationResult.incompleteInputs(this.calculationType(), "Discount term " + term.termId()
						+ " carries no usable value, so the entitlement it describes cannot be applied");
			}
		}

		// The base is the contracted gross, recomputed from the same pricing term the
		// pricing rule used. A percentage discount on the invoiced gross would compare
		// the invoice against itself.
		Money base = this.expectedAmountCalculator.expectedGrossAmount(line, context.pricingTerm());
		Money expectedDiscount = this.expectedAmountCalculator.expectedDiscountAmount(base, terms);
		Money actualDiscount = this.actualAmountCalculator.actualDiscountAmount(line);
		// Measured on the discount itself, so a positive value here means the customer got
		// MORE discount than entitled. The engine negates it when folding into the net.
		Variance variance = this.varianceCalculator.variance(expectedDiscount, actualDiscount, VarianceType.Discount.INSTANCE);

		List<TermEvaluation> evaluated = new ArrayList<>();
		for (DiscountTerm term : terms) {
			evaluated.add(term.toEvaluation());
		}
		ExpectedValue expected = new ExpectedValue(expectedDiscount, this.calculationType(), evaluated,
				"Entitled discount on contracted gross " + base.amount().toPlainString() + " "
						+ base.currency().value() + " from " + terms.size() + " term(s) in force");
		ActualValue actual = ActualValue.of(actualDiscount, line.source(), "Discount granted on invoice line "
				+ line.lineNumber());
		String explanation = "Discount granted " + actualDiscount.amount().toPlainString() + " "
				+ actualDiscount.currency().value() + " against entitlement " + expectedDiscount.amount().toPlainString()
				+ " " + expectedDiscount.currency().value()
				// The clamp is disclosed rather than passed over in silence: a reader seeing an
				// entitlement exactly equal to the gross needs to know the contract asked for
				// more and the ceiling bound it. Guarded on non-zero so a genuine zero
				// entitlement is not described as a cap.
				+ (expectedDiscount.compareTo(base) == 0 && !expectedDiscount.isZero()
						? "; the entitlement exceeded the contracted gross and was capped at it" : "");
		return RuleEvaluationResult.evaluated(this.calculationType(), expected, actual, variance, evaluated,
				explanation);
	}

}
package com.fintech.cfo.financialtruth.rules;

import java.util.List;

import com.fintech.cfo.financialtruth.calculator.ActualAmountCalculator;
import com.fintech.cfo.financialtruth.calculator.ExpectedAmountCalculator;
import com.fintech.cfo.financialtruth.calculator.VarianceCalculator;
import com.fintech.cfo.financialtruth.enums.CalculationType;
import com.fintech.cfo.financialtruth.enums.PricingType;
import com.fintech.cfo.financialtruth.enums.VarianceType;
import com.fintech.cfo.financialtruth.model.ActualValue;
import com.fintech.cfo.financialtruth.model.ExpectedValue;
import com.fintech.cfo.financialtruth.model.InvoiceLineInput;
import com.fintech.cfo.financialtruth.model.PricingTerm;
import com.fintech.cfo.financialtruth.model.Variance;
import com.fintech.cfo.shared.domain.Money;
import com.fintech.cfo.shared.exception.BusinessRuleException;

/**
 * Compares the invoiced unit price against the contracted unit price for a line.
 *
 * <h2>Scope</h2>
 * This rule measures the <em>gross</em> amount only: {@code quantity x unit price}.
 * Discounts are measured separately by {@link DiscountVarianceRule}, and the engine
 * combines the two into the net payable. Keeping them disjoint is what stops the
 * same deviation being counted twice.
 *
 * <h2>Version 1.0.0 arithmetic</h2>
 * <pre>
 * expected gross = contract unit price (6dp) x quantity (6dp), HALF_UP to 4dp
 * actual gross   = invoiced unit price (6dp) x quantity (6dp), HALF_UP to 4dp
 * variance       = actual gross - expected gross   (positive = overcharge)
 * </pre>
 *
 * <h2>Failure behaviour</h2>
 * <ul>
 * <li>No contract price in force on the as-of date: <strong>raises</strong>
 * {@code BusinessRuleException}. Defaulting to the invoiced price would report zero
 * variance for an invoice nobody has checked against anything, which is the exact
 * false assurance this product must never give.</li>
 * <li>Term in a currency the line is not in: <strong>raises</strong>. There is no FX
 * component in this module and inventing a rate is not an option.</li>
 * <li>Pricing type this module cannot evaluate (list price, tiers): reported as
 * {@code INCOMPLETE_INPUTS}, because the contract may well be perfectly valid and
 * simply outside the implemented basis.</li>
 * <li>Missing or non-positive quantity: reported as {@code INCOMPLETE_INPUTS}.</li>
 * </ul>
 */
public final class PricingVarianceRule implements FinancialRule {

	public static final String CODE = "PRICING_VARIANCE";

	public static final String VERSION = "1.0.0";

	private final ExpectedAmountCalculator expectedAmountCalculator;
	private final ActualAmountCalculator actualAmountCalculator;
	private final VarianceCalculator varianceCalculator;

	public PricingVarianceRule(ExpectedAmountCalculator expectedAmountCalculator,
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
		return CalculationType.PRICING_VARIANCE;
	}

	/**
	 * A contract price either applies or it does not; there is no configuration in
	 * which this rule is irrelevant to a priced line.
	 */
	@Override
	public boolean appliesTo(RuleContext context) {
		return context != null && context.line() != null;
	}

	@Override
	public RuleEvaluationResult evaluate(RuleContext context) {
		InvoiceLineInput line = context.line();
		// Guard order matters: input usability, then contract authority, then contract
		// shape. Each early return names the specific reason, so the recorded row says
		// which of the three it was.
		if (!line.hasUsableQuantity()) {
			return RuleEvaluationResult.incompleteInputs(this.calculationType(),
					"Line " + line.lineNumber() + " has no usable quantity, so no expected price can be applied to it");
		}

		PricingTerm term = context.pricingTerm();
		if (term == null) {
			// Raises rather than returns. There is no defensible expected amount without a
			// contract price, and returning a zero variance here would report an invoice
			// as clean when in fact nothing was ever checked against anything.
			throw new BusinessRuleException("Line " + line.lineNumber() + " product " + line.productKey()
					+ ": no contract pricing term was in force on " + context.asOfDate()
					+ ". Refusing to reconcile against an invoiced price of zero.");
		}
		if (!term.currency().equals(context.currency())) {
			// Raises too. This module has no FX component, and inventing a rate would be
			// the most damaging thing it could do.
			throw new BusinessRuleException("Pricing term " + term.termId() + " is denominated in "
					+ term.currency().value() + " but invoice line " + line.lineNumber() + " is in "
					+ context.currency().value() + ". This module never converts between currencies.");
		}
		if (term.pricingType() != PricingType.FIXED_UNIT_PRICE) {
			// Not a raise: the contract may be perfectly valid, simply outside the basis
			// this module implements. Reported so the gap is visible, not hidden.
			return RuleEvaluationResult.incompleteInputs(this.calculationType(), "Pricing term " + term.termId()
					+ " is a " + term.pricingType() + " term; no deterministic expected amount can be derived from it yet");
		}

		// Both sides measured by their own calculator and rounded once each, so the
		// variance isolates the price difference rather than a difference in method.
		Money expectedGross = this.expectedAmountCalculator.expectedGrossAmount(line, term);
		Money actualGross = this.actualAmountCalculator.actualGrossAmount(line);
		// actual - expected: positive means the customer was overcharged.
		Variance variance = this.varianceCalculator.variance(expectedGross, actualGross, VarianceType.Pricing.INSTANCE);

		ExpectedValue expected = new ExpectedValue(expectedGross, this.calculationType(),
				List.of(term.toEvaluation()), "Contract unit price " + term.unitPrice().toPlainString() + " "
						+ term.currency().value() + " (term " + term.termId() + " v" + term.termVersion()
						+ ") x quantity " + line.quantity().toPlainString());
		ActualValue actual = ActualValue.of(actualGross, line.source(), "Invoiced unit price "
				+ line.unitPrice().amount().toPlainString() + " " + line.currency().value() + " x quantity "
				+ line.quantity().toPlainString());
		String explanation = "Invoiced gross " + actualGross.amount().toPlainString() + " "
				+ actualGross.currency().value() + " against contracted gross " + expectedGross.amount().toPlainString()
				+ " " + expectedGross.currency().value();
		return RuleEvaluationResult.evaluated(this.calculationType(), expected, actual, variance,
				expected.evaluatedTerms(), explanation);
	}

}
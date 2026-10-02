package com.fintech.cfo.financialtruth.model;

import com.fintech.cfo.financialtruth.enums.CalculationConfidence;
import com.fintech.cfo.financialtruth.enums.ImpactDirection;
import com.fintech.cfo.shared.domain.CurrencyCode;
import com.fintech.cfo.shared.domain.Money;
import com.fintech.cfo.shared.exception.ValidationException;

/**
 * What a set of variances is worth, in one currency, with a statement of how far it
 * can be trusted.
 *
 * <p>Always carries its currency. A total without a currency is the failure mode
 * this module exists to prevent.
 *
 * @param totalImpact          signed sum of the contributing variances
 * @param direction            which way the money moved, derived not asserted
 * @param confidence           weight the figure deserves given input completeness
 * @param evaluatedLineCount   lines that produced a monetary finding
 * @param unevaluatedLineCount lines that did not, so the total is a floor not a truth
 * @param rationale            human-readable statement of what was and was not included
 */
public record FinancialImpact(
		Money totalImpact,
		ImpactDirection direction,
		CalculationConfidence confidence,
		int evaluatedLineCount,
		int unevaluatedLineCount,
		String rationale) {

	public FinancialImpact {
		if (totalImpact == null) {
			throw new ValidationException("totalImpact must not be null");
		}
		if (confidence == null) {
			throw new ValidationException("confidence must not be null");
		}
		if (evaluatedLineCount < 0 || unevaluatedLineCount < 0) {
			throw new ValidationException("line counts must not be negative");
		}
	}

	/**
	 * @param totalImpact          the signed total, which must carry its currency
	 * @param confidence           weight the total deserves, reflecting any omitted lines
	 * @param evaluatedLineCount   lines that contributed a figure
	 * @param unevaluatedLineCount lines presented but not evaluated, so the reader knows
	 *                              the total is a floor
	 * @param rationale            plain statement of what was and was not included
	 * @return the impact, with direction derived from the sign of the total
	 */
	public static FinancialImpact of(Money totalImpact, CalculationConfidence confidence, int evaluatedLineCount,
			int unevaluatedLineCount, String rationale) {
		return new FinancialImpact(totalImpact, ImpactDirection.of(totalImpact.amount()), confidence, evaluatedLineCount,
				unevaluatedLineCount, rationale);
	}

	/** An explicit statement that nothing could be established, with no amount invented. */
	public static FinancialImpact notEvaluated(CurrencyCode currency, String rationale) {
		return of(Money.zero(currency), CalculationConfidence.LOW, 0, 0, rationale);
	}

	public String canonicalForm() {
		// The rationale is deliberately absent. It is prose assembled from counts, so it
		// would make the fingerprint sensitive to wording; the counts it is derived from
		// are hashed instead.
		return "impact=" + RoundingPolicy.canonicalMoney(this.totalImpact)
				+ "|direction=" + this.direction.name()
				+ "|confidence=" + this.confidence.name()
				+ "|evaluated=" + this.evaluatedLineCount
				+ "|unevaluated=" + this.unevaluatedLineCount;
	}

}
package com.fintech.cfo.financialtruth.dto;

import org.jspecify.annotations.Nullable;

import com.fintech.cfo.financialtruth.enums.CalculationConfidence;
import com.fintech.cfo.financialtruth.enums.ImpactDirection;
import com.fintech.cfo.financialtruth.model.FinancialImpact;

/**
 * The monetary impact of a calculation, in one currency, with the lines it could not
 * cover disclosed.
 *
 * @param rationale why this total carries this confidence; mandatory, because a
 *                  total whose limits are not stated cannot be defended
 */
public record FinancialImpactResponse(
		AmountResponse totalImpact,
		ImpactDirection direction,
		CalculationConfidence confidence,
		int evaluatedLineCount,
		int unevaluatedLineCount,
		String rationale) {

	/**
	 * Converts an impact, or returns null for a row that carries no impact.
	 *
	 * <p>Null for component rows, which is what keeps an impact reachable from exactly
	 * one place: the combined per-line row and the run-level totals.
	 */
	public static @Nullable FinancialImpactResponse from(@Nullable FinancialImpact impact) {
		if (impact == null) {
			return null;
		}
		return new FinancialImpactResponse(AmountResponse.from(impact.totalImpact()), impact.direction(),
				impact.confidence(), impact.evaluatedLineCount(), impact.unevaluatedLineCount(), impact.rationale());
	}

}
package com.fintech.cfo.financialtruth.dto;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

import com.fintech.cfo.financialtruth.enums.CalculationConfidence;
import com.fintech.cfo.financialtruth.enums.CalculationType;
import com.fintech.cfo.financialtruth.enums.RuleStatus;
import com.fintech.cfo.financialtruth.model.CalculationResult;

/**
 * One result row as reported to a client.
 *
 * <p>{@code ruleCode} and {@code ruleVersion} travel with every row because a figure
 * without the version that produced it cannot be defended later.
 *
 * @param expected  null when the rule established no expected amount
 * @param actual    null when the rule established no actual amount
 * @param variance  null for a row whose status carries no monetary claim
 * @param impact    null for any row other than the combined per-line figure
 */
public record CalculationResponse(
		CalculationType calculationType,
		RuleStatus status,
		String ruleCode,
		String ruleVersion,
		int lineNumber,
		@Nullable AmountResponse expected,
		@Nullable AmountResponse actual,
		@Nullable VarianceResponse variance,
		@Nullable FinancialImpactResponse impact,
		CalculationConfidence confidence,
		String explanation,
		Instant calculatedAt) {

	/**
	 * Converts a result row, or returns null when there is no row to report.
	 *
	 * <p>Null in, null out. The nullable sub-converters keep their own distinction, so a
	 * row whose status carries no monetary claim arrives with all three amounts null
	 * rather than zero-filled.
	 */
	public static @Nullable CalculationResponse from(@Nullable CalculationResult result) {
		if (result == null) {
			return null;
		}
		return new CalculationResponse(result.calculationType(), result.status(), result.ruleCode(),
				result.ruleVersion(), result.lineNumber(), AmountResponse.from(result.expectedAmount()),
				AmountResponse.from(result.actualAmount()), VarianceResponse.from(result.variance()),
				FinancialImpactResponse.from(result.impact()), result.confidence(), result.explanation(),
				result.calculatedAt());
	}

}
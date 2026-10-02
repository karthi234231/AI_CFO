package com.fintech.cfo.financialtruth.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.fintech.cfo.financialtruth.enums.CalculationStatus;
import com.fintech.cfo.financialtruth.enums.CalculationType;
import com.fintech.cfo.financialtruth.model.CalculationRun;

/**
 * A calculation run as reported to a client.
 *
 * <p>{@code inputChecksum} and {@code ruleVersion} are part of the response on
 * purpose: the pair is what makes the figures in {@code results} independently
 * checkable, and a client that cannot see it cannot reproduce anything.
 *
 * @param completedAt   null until the run completes
 * @param failureReason null unless the run failed
 */
public record CalculationRunResponse(
		UUID runId,
		CalculationType calculationType,
		CalculationStatus status,
		String ruleVersion,
		String inputChecksum,
		String deterministicFingerprint,
		@Nullable LocalDate periodStart,
		@Nullable LocalDate periodEnd,
		Instant startedAt,
		@Nullable Instant completedAt,
		@Nullable String failureReason,
		List<CalculationResponse> results,
		List<FinancialImpactResponse> impacts) {

	/**
	 * Converts a run, or returns null when there is no run to report.
	 *
	 * <p>The fingerprint is computed here rather than stored, so it can never drift from
	 * the results it describes. Results are mapped in the run's own order, preserving the
	 * sequence the reproducibility check compares positionally.
	 */
	public static @Nullable CalculationRunResponse from(@Nullable CalculationRun run) {
		if (run == null) {
			return null;
		}
		return new CalculationRunResponse(run.runId(), run.calculationType(), run.status(), run.ruleVersion(),
				run.inputChecksum(), run.deterministicFingerprint(), run.periodStart(), run.periodEnd(),
				run.startedAt(), run.completedAt(), run.failureReason(),
				run.results().stream().map(CalculationResponse::from).toList(),
				run.impacts().stream().map(FinancialImpactResponse::from).toList());
	}

}
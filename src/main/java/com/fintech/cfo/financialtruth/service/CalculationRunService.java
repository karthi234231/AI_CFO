package com.fintech.cfo.financialtruth.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.fintech.cfo.financialtruth.calculator.FinancialTruthEngine;
import com.fintech.cfo.financialtruth.enums.CalculationStatus;
import com.fintech.cfo.financialtruth.model.CalculationInput;
import com.fintech.cfo.financialtruth.model.CalculationRun;
import com.fintech.cfo.shared.domain.DateRange;
import com.fintech.cfo.shared.exception.BusinessRuleException;
import com.fintech.cfo.shared.exception.ValidationException;

/**
 * Lifecycle bookkeeping for a calculation run.
 *
 * <p>{@code calculation_runs} has a status column and integrity depends on it being
 * honest: a run that throws half way through must be recorded as {@link
 * CalculationStatus.Failed} with a reason, never left looking complete. This class
 * owns those transitions and nothing else.
 *
 * <p>The timestamps come from an injected {@link Clock} rather than
 * {@code Instant.now()}, so a lifecycle test - and a replay - produces identical
 * records.
 */
public final class CalculationRunService {

	private final FinancialTruthEngine engine;
	private final Clock clock;

	public CalculationRunService(FinancialTruthEngine engine, Clock clock) {
		this.engine = engine;
		if (clock == null) {
			throw new ValidationException("clock must not be null; run timestamps must not come from the system clock");
		}
		this.clock = clock;
	}

	/**
	 * Opens a run and records everything needed to reproduce it before any figure is
	 * produced: the checksum of the inputs, the rule-set fingerprint and the period.
	 *
	 * <p>No results are attached yet. A run that cannot even be opened should never
	 * reach the point of reporting a number.
	 */
	public CalculationRun start(CalculationInput input, UUID runId) {
		if (input == null) {
			throw new ValidationException("input must not be null");
		}
		if (runId == null) {
			throw new ValidationException("runId must be supplied so a replay can reconstruct the run");
		}
		Instant startedAt = this.clock.instant();
		DateRange period = input.period();
		return new CalculationRun(runId, input.organizationId(), input.calculationType(), CalculationStatus.Running.INSTANCE,
				this.engine.ruleSetVersion(), period.startDate(), period.endDate(), input.checksum(),
				input.triggeredBy(), startedAt, null, null, List.of(), List.of());
	}

	/** Closes a successfully evaluated run. */
	public CalculationRun complete(CalculationRun running, CalculationRun evaluated) {
		// Three guards before the close is accepted. The result must be COMPLETED, must
		// belong to this run id, and must have been computed from the inputs the run was
		// opened with - otherwise the run would carry results that cannot be defended.
		requireStatus(running, CalculationStatus.Running.INSTANCE);
		if (evaluated.status() != CalculationStatus.Completed.INSTANCE) {
			throw new ValidationException(
					"only a completed evaluation may close a run; got " + evaluated.status().code());
		}
		requireSameRun(running, evaluated);
		requireSameInputs(running, evaluated);
		// The evaluated run is returned as-is. This service owns the transition, not the
		// contents: recomputing or amending a figure here would desynchronise the record
		// from its own fingerprint.
		return evaluated;
	}

	/**
	 * Closes a run that could not produce a result.
	 *
	 * <p>The input checksum and rule version from {@code running} are preserved, so a
	 * failure can be re-driven later from the same snapshot and compared against the
	 * same fingerprint.
	 *
	 * @param cause the exception that ended the run; its message is truncated to what
	 *              {@code calculation_runs.failure_reason} can hold
	 */
	public CalculationRun fail(CalculationRun running, Throwable cause) {
		requireStatus(running, CalculationStatus.Running.INSTANCE);
		if (cause == null) {
			throw new ValidationException("cause must not be null; a failed run must record why it failed");
		}
		// Results and impacts are dropped, not partially retained. A half-written set of
		// rows alongside a FAILED status would invite a reader to sum figures the engine
		// never finished producing.
		DateRange period = DateRange.of(startOr(running.periodStart(), running.startedAt()),
				endOr(running.periodEnd(), running.startedAt()));
		return new CalculationRun(running.runId(), running.organizationId(), running.calculationType(),
				CalculationStatus.Failed.INSTANCE, running.ruleVersion(), period.startDate(), period.endDate(),
				running.inputChecksum(), running.triggeredBy(), running.startedAt(), this.clock.instant(),
				truncate(cause.getMessage()), List.of(), List.of());
	}

	/**
	 * Whether a completed run reproduced exactly: same checksum, same rule version,
	 * same results. Checked here as well as in {@link ReproducibilityService} so that
	 * closing a run cannot quietly accept an unreproducible result.
	 */
	public void requireReproducible(CalculationRun original, CalculationRun replay) {
		// Both sides must already be COMPLETED: comparing against a running or failed run
		// would report a difference that is only a lifecycle state, not a reproducibility
		// failure.
		requireStatus(original, CalculationStatus.Completed.INSTANCE);
		requireStatus(replay, CalculationStatus.Completed.INSTANCE);
		// Checksum first, then rule version, then fingerprint. The order is diagnostic:
		// a changed checksum explains itself, and only a caller that got past both
		// earlier checks needs to look at the results themselves.
		if (!original.inputChecksum().equals(replay.inputChecksum())) {
			throw new BusinessRuleException("input checksum changed between runs: " + original.inputChecksum()
					+ " then " + replay.inputChecksum() + "; the replay did not use the same inputs");
		}
		if (!original.ruleVersion().equals(replay.ruleVersion())) {
			throw new BusinessRuleException("rule version changed between runs: " + original.ruleVersion() + " then "
					+ replay.ruleVersion() + "; a replay must use the identical rule set");
		}
		if (!original.deterministicFingerprint().equals(replay.deterministicFingerprint())) {
			throw new BusinessRuleException("calculation run " + original.runId()
					+ " did not reproduce; the replay produced different results from identical inputs");
		}
	}

	private static void requireStatus(CalculationRun run, CalculationStatus expected) {
		if (run.status() != expected) {
			throw new ValidationException("expected run status " + expected.code() + " but found " + run.status().code());
		}
	}

	private static void requireSameRun(CalculationRun running, CalculationRun evaluated) {
		if (!running.runId().equals(evaluated.runId())) {
			throw new ValidationException("cannot close run " + running.runId() + " with results from run "
					+ evaluated.runId());
		}
	}

	private static void requireSameInputs(CalculationRun running, CalculationRun evaluated) {
		if (!running.inputChecksum().equals(evaluated.inputChecksum())) {
			throw new ValidationException(
					"run " + running.runId() + " was opened with a different input checksum than the results it was "
							+ "closed with; the results cannot belong to this run");
		}
	}

	/** {@code calculation_runs.failure_reason} is {@code VARCHAR(2000)}. */
	private static String truncate(String message) {
		// A null or blank message is replaced rather than stored: a failed run with an
		// empty reason is indistinguishable from one that was never attempted.
		String text = message == null || message.isBlank() ? "unspecified failure" : message;
		// Cut, not rejected. Losing the tail of a stack message is preferable to losing
		// the failure record itself.
		return text.length() <= 2000 ? text : text.substring(0, 2000);
	}

	/**
	 * Period start, falling back to the UTC date of the run's own start instant.
	 *
	 * <p>Fallbacks exist because a run opened without an explicit period must still close
	 * with a valid {@code DateRange}; UTC is chosen so the date does not shift with the
	 * server's zone.
	 */
	private static LocalDate startOr(LocalDate periodStart, Instant fallback) {
		return periodStart != null ? periodStart
				: java.time.LocalDate.ofInstant(fallback, java.time.ZoneOffset.UTC);
	}

	private static LocalDate endOr(LocalDate periodEnd, Instant fallback) {
		return periodEnd != null ? periodEnd : java.time.LocalDate.ofInstant(fallback, java.time.ZoneOffset.UTC);
	}

}
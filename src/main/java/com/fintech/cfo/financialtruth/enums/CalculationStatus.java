package com.fintech.cfo.financialtruth.enums;

import java.util.List;

import com.fintech.cfo.shared.exception.ValidationException;

/**
 * Lifecycle of a calculation run ({@code calculation_runs.status VARCHAR(32)}).
 *
 * <p>A sealed interface of records rather than an enum, so the service that moves a
 * run through its lifecycle is a {@code switch} the compiler keeps exhaustive: a run
 * cannot reach a state the service never decided how to handle.
 *
 * <p>A run only reaches {@link Completed} when every line it claimed to evaluate
 * really was evaluated; anything else must be recorded as {@link Failed} with a
 * reason, never quietly completed.
 */
public sealed interface CalculationStatus extends CodedEnum permits CalculationStatus.Pending, CalculationStatus.Running,
		CalculationStatus.Completed, CalculationStatus.Failed {

	/** Width of {@code calculation_runs.status} in V6. */
	int MAX_CODE_LENGTH = 32;

	/**
	 * Every variant, in a fixed order that never depends on declaration order.
	 *
	 * <p>A method rather than a constant: see {@link RuleStatus#all()} for why a
	 * static field holding nested {@code INSTANCE} references cannot initialise.
	 */
	static List<CalculationStatus> all() {
		return List.of(Pending.INSTANCE, Running.INSTANCE, Completed.INSTANCE, Failed.INSTANCE);
	}

	/**
	 * Resolves a stored {@code calculation_runs.status} value.
	 *
	 * @throws ValidationException if the value is blank or unknown
	 */
	static CalculationStatus fromCode(String code) {
		String normalized = CodedEnum.normalise(code, "CalculationStatus");
		for (CalculationStatus candidate : all()) {
			if (candidate.code().equals(normalized)) {
				candidate.requireColumnWidth(MAX_CODE_LENGTH);
				return candidate;
			}
		}
		throw new ValidationException("unknown calculation status code: " + code);
	}

	/**
	 * Whether the run's results are final and may be reported.
	 *
	 * <p>Only {@link Completed} qualifies. A {@link Failed} run produced no
	 * authoritative result, and a {@link Running} or {@link Pending} one has not
	 * produced any.
	 */
	default boolean isAuthoritative() {
		return switch (this) {
			case Pending pending -> false;
			case Running running -> false;
			case Completed completed -> true;
			case Failed failed -> false;
		};
	}

	/** Accepted but not started. */
	record Pending() implements CalculationStatus {

		public static final Pending INSTANCE = new Pending();

		@Override
		public String code() {
			return "PENDING";
		}

	}

	/** Evaluating lines. */
	record Running() implements CalculationStatus {

		public static final Running INSTANCE = new Running();

		@Override
		public String code() {
			return "RUNNING";
		}

	}

	/** Finished; results are final and reproducible from the recorded checksum. */
	record Completed() implements CalculationStatus {

		public static final Completed INSTANCE = new Completed();

		@Override
		public String code() {
			return "COMPLETED";
		}

	}

	/** Aborted. A run that failed produced no authoritative result. */
	record Failed() implements CalculationStatus {

		public static final Failed INSTANCE = new Failed();

		@Override
		public String code() {
			return "FAILED";
		}

	}

}
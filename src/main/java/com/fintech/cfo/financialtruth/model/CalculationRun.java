package com.fintech.cfo.financialtruth.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fintech.cfo.financialtruth.enums.CalculationStatus;
import com.fintech.cfo.financialtruth.enums.CalculationType;
import com.fintech.cfo.shared.domain.OrganizationId;
import com.fintech.cfo.shared.domain.UserId;
import com.fintech.cfo.shared.exception.ValidationException;
import com.fintech.cfo.shared.util.HashUtils;

/**
 * A calculation run: the record that makes a result defensible.
 *
 * <p>Maps onto {@code calculation_runs} in {@code V6__create_calculations.sql}. The
 * run pins the three things a replay needs and nothing else:
 *
 * <ul>
 * <li>{@code inputChecksum} - SHA-256 over the canonical form of every input read.</li>
 * <li>{@code ruleVersion} - the exact rule set that produced the figures.</li>
 * <li>{@code startedAt} / {@code completedAt} - from an injected clock, never the
 * system clock.</li>
 * </ul>
 *
 * <p>The run id is supplied by the caller rather than generated here. Generation
 * would inject entropy into the very record whose purpose is to be reproducible, and
 * a replay must be able to reconstruct the run it is replaying.
 */
public record CalculationRun(
		UUID runId,
		OrganizationId organizationId,
		CalculationType calculationType,
		CalculationStatus status,
		String ruleVersion,
		LocalDate periodStart,
		LocalDate periodEnd,
		String inputChecksum,
		UserId triggeredBy,
		Instant startedAt,
		Instant completedAt,
		String failureReason,
		List<CalculationResult> results,
		List<FinancialImpact> impacts) {

	/** {@code calculation_runs.input_checksum} is {@code CHAR(64)}: 64 lowercase hex digits. */
	private static final Pattern SHA_256_HEX = Pattern.compile("^[0-9a-f]{64}$");

	public CalculationRun {
		if (runId == null) {
			throw new ValidationException("runId must be supplied by the caller so a replay can reconstruct it");
		}
		if (organizationId == null) {
			throw new ValidationException("organizationId must not be null");
		}
		if (calculationType == null) {
			throw new ValidationException("calculationType must not be null");
		}
		if (status == null) {
			throw new ValidationException("status must not be null");
		}
		if (ruleVersion == null || ruleVersion.isBlank()) {
			throw new ValidationException("ruleVersion must not be blank");
		}
		if (inputChecksum == null || !SHA_256_HEX.matcher(inputChecksum).matches()) {
			throw new ValidationException("inputChecksum must be 64 lowercase hex characters (SHA-256)");
		}
		if (startedAt == null) {
			throw new ValidationException("startedAt must not be null");
		}
		results = results == null ? List.of() : List.copyOf(results);
		impacts = impacts == null ? List.of() : List.copyOf(impacts);
		// Status honesty. A run may not look COMPLETED without a completion instant, and
		// may not look FAILED without saying why - an unexplained failure is
		// indistinguishable from a run that was never attempted.
		if (status == CalculationStatus.Completed.INSTANCE && completedAt == null) {
			throw new ValidationException("a completed run must record completedAt");
		}
		if (status == CalculationStatus.Failed.INSTANCE && (failureReason == null || failureReason.isBlank())) {
			throw new ValidationException("a failed run must record why it failed");
		}
	}

	/**
	 * SHA-256 over the run's rule version, input checksum and every result in order.
	 *
	 * <p>This is the proof of reproducibility. Two runs over identical inputs under
	 * identical rules and clock produce the same fingerprint; anything that moved -
	 * an amount, a term version, the evaluated-at instant - changes it.
	 *
	 * <p>Includes the run id, so a fingerprint identifies one specific execution and two
	 * independent runs of the same invoice are correctly reported as different records
	 * even when their figures agree.
	 *
	 * @return 64 lowercase hex characters
	 */
	public String deterministicFingerprint() {
		StringBuilder text = new StringBuilder();
		text.append("calculationRun/v1|run=").append(this.runId)
				.append("|type=").append(this.calculationType.name())
				.append("|status=").append(this.status.code())
				.append("|rules=").append(this.ruleVersion)
				.append("|checksum=").append(this.inputChecksum)
				.append("|period=").append(RoundingPolicy.canonicalDate(this.periodStart)).append("..")
				.append(RoundingPolicy.canonicalDate(this.periodEnd))
				.append("|startedAt=").append(this.startedAt.toString())
				.append("|completedAt=").append(this.completedAt == null ? "-" : this.completedAt.toString())
				.append("|results=");
		for (CalculationResult result : this.results) {
			// Appended in list order, never re-sorted. The order is the engine's
			// evaluation order and is part of what is being fingerprinted; sorting here
			// would hide a reordering regression.
			text.append(result.canonicalForm());
		}
		text.append("|impacts=");
		for (FinancialImpact impact : this.impacts) {
			text.append(impact.canonicalForm()).append(';');
		}
		return HashUtils.sha256(text.toString());
	}

	/**
	 * Only the combined rows, in line order.
	 *
	 * <p>The single place component rows are excluded. Filtering by calculation type
	 * here - rather than leaving it to each caller - is what makes it impossible for a
	 * report to add a price deviation to a discount deviation.
	 */
	public List<CalculationResult> combinedResults() {
		return this.results.stream()
				.filter(result -> result.calculationType() == CalculationType.COMBINED_VARIANCE)
				.toList();
	}
}
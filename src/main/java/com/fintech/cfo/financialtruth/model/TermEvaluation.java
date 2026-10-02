package com.fintech.cfo.financialtruth.model;

import java.time.LocalDate;

import com.fintech.cfo.shared.exception.ValidationException;

/**
 * The exact contract terms one evaluation consulted.
 *
 * <p>This is the lineage of a number. It answers the question an auditor asks
 * months later: "which version of which term, in force on which dates, did you
 * use?" Without it a result can be recomputed but not defended.
 */
public record TermEvaluation(
		String termId,
		String termType,
		int termVersion,
		LocalDate effectiveFrom,
		LocalDate effectiveTo) {

	public TermEvaluation {
		if (termId == null || termId.isBlank()) {
			throw new ValidationException("termId must not be blank");
		}
		if (termType == null || termType.isBlank()) {
			throw new ValidationException("termType must not be blank");
		}
		if (termVersion <= 0) {
			throw new ValidationException("termVersion must be greater than 0 for term " + termId);
		}
		if (effectiveFrom == null) {
			throw new ValidationException("effectiveFrom must not be null for term " + termId);
		}
		if (effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) {
			throw new ValidationException("effectiveTo must not be before effectiveFrom for term " + termId);
		}
	}

	/**
	 * Fixed-order textual form of one consulted term.
	 *
	 * <p>{@code termId@v3[FIXED_UNIT_PRICE]2024-01-01..2024-12-31} - enough to
	 * identify the exact version and window a figure came from. Enteres the input
	 * checksum and the run fingerprint, so the format is part of the reproducibility
	 * contract and must not be reordered.
	 */
	public String canonicalForm() {
		return this.termId + "@v" + this.termVersion + "[" + this.termType + "]"
				+ RoundingPolicy.canonicalDate(this.effectiveFrom) + ".."
				+ RoundingPolicy.canonicalDate(this.effectiveTo);
	}

}
package com.fintech.cfo.opportunity.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.fintech.cfo.shared.exception.ValidationException;

/**
 * Pointer to the deterministic calculation that produced an opportunity's figure.
 *
 * <p>Mirrors the two V8 foreign keys on {@code opportunities}
 * ({@code calculation_run_id}, {@code primary_result_id}) and adds the three values
 * without which a stored amount cannot be re-derived: the rule that was applied, the
 * version of that rule, and the checksum of the inputs it was applied to.
 *
 * <p>The checksum is not decoration. A reported variance is only defensible if someone
 * can re-run the same rule over the same inputs and get the same number; carrying the
 * rule version and the input checksum on the opportunity is what lets that claim be
 * checked months later, when neither the rule nor the data may still be as they were.
 * That is why every one of them is required rather than optional: a reference with a
 * missing checksum would be indistinguishable from an unreproducible figure.
 *
 * @param calculationRunId    the run that produced the figure (V8 FK, required)
 * @param primaryResultId     the authoritative result row (V8 FK, optional until a
 *                            combined figure exists)
 * @param ruleCode            which rule decided this
 * @param ruleVersion         the version of that rule, which enters the checksum
 * @param inputChecksum       checksum of the exact inputs the rule saw
 * @param effectiveDate       the business date the terms were read as at, when one
 *                            applies
 * @param evaluatedAt         when the figure was produced, for freshness comparison
 */
public record CalculationReference(
		UUID calculationRunId,
		@Nullable UUID primaryResultId,
		String ruleCode,
		String ruleVersion,
		String inputChecksum,
		@Nullable LocalDate effectiveDate,
		Instant evaluatedAt) {

	public CalculationReference {
		// The run id is the one field V8 makes a foreign key, and it is required
		// here as well: a figure with no run behind it cannot be re-executed, only
		// believed.
		if (calculationRunId == null) {
			throw new ValidationException("calculationRunId must not be null");
		}
		// ruleCode, ruleVersion and inputChecksum are the reproducibility triple:
		// dropping any one of them makes a later re-run unable to prove it evaluated
		// the same inputs under the same rule.
		ruleCode = requireText(ruleCode, "ruleCode");
		ruleVersion = requireText(ruleVersion, "ruleVersion");
		inputChecksum = requireText(inputChecksum, "inputChecksum");
		if (evaluatedAt == null) {
			throw new ValidationException("evaluatedAt must not be null");
		}
	}

	/**
	 * The authoritative result is known, so this reference can be re-derived exactly.
	 *
	 * @return true when a primary result id is present, which is the difference
	 *         between re-derivable and merely recorded
	 */
	public boolean isReDerivable() {
		return this.primaryResultId != null;
	}

	private static String requireText(String value, String field) {
		Objects.requireNonNull(value, field + " must not be null");
		String trimmed = value.trim();
		if (trimmed.isEmpty()) {
			throw new ValidationException(field + " must not be blank");
		}
		return trimmed;
	}

}
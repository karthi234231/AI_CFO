package com.fintech.cfo.contract.dto;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

import com.fintech.cfo.contract.enums.CommercialRuleType;
import com.fintech.cfo.shared.util.HashUtils;

/**
 * The outcome of evaluating one commercial rule against one transaction.
 *
 * <p>Three states, not two. {@code applicable} false means the transaction did not
 * carry the inputs the rule needs, so the rule had no opinion; applicable and
 * {@code satisfied} false means the rule was breached. Both are recorded rather
 * than collapsed, because "we could not check" and "we checked and it failed" lead
 * to entirely different follow-up, and a system that reports the first as the
 * second is worse than one that reports neither.
 *
 * <p>{@code inputChecksum} is a SHA-256 over this result's own fields, computed by
 * the factory rather than supplied beside them, so it cannot drift from what it
 * attests to. A stored run can then prove which rule version, parameters, date and
 * outcome it was derived from.
 *
 * @param ruleCode rule code as stored
 * @param ruleType basis of the condition
 * @param ruleId originating {@code commercial_rules.id}
 * @param termVersion rule version applied
 * @param asOfDate date the rule was resolved for
 * @param applicable whether the transaction carried the inputs the rule needs
 * @param satisfied whether the condition held; always true when not applicable
 * @param explanation human-readable justification, always populated
 * @param parameters rule parameters in key-sorted canonical form
 * @param inputChecksum SHA-256 over the inputs that produced this result
 */
public record RuleEvaluationResult(
		String ruleCode,
		CommercialRuleType ruleType,
		UUID ruleId,
		int termVersion,
		LocalDate asOfDate,
		boolean applicable,
		boolean satisfied,
		String explanation,
		Map<String, String> parameters,
		String inputChecksum) implements Serializable {

	public RuleEvaluationResult {
		Objects.requireNonNull(ruleCode, "ruleCode must not be null");
		Objects.requireNonNull(ruleType, "ruleType must not be null");
		Objects.requireNonNull(ruleId, "ruleId must not be null");
		Objects.requireNonNull(asOfDate, "asOfDate must not be null");
		Objects.requireNonNull(explanation, "explanation must not be null");
		Objects.requireNonNull(parameters, "parameters must not be null");
		Objects.requireNonNull(inputChecksum, "inputChecksum must not be null");
		if (!applicable && !satisfied) {
			// The illegal combination, refused rather than stored: not-applicable
			// carries satisfied == true so a caller cannot read it as a failure.
			// Collapsing the two states is exactly what would turn a coverage gap
			// into a false attestation.
			throw new IllegalArgumentException("a rule that was not evaluated cannot be reported as unsatisfied");
		}
		// Copied into a sorted, unmodifiable map so the checksum, the wire form and
		// any later iteration all see the same key order.
		parameters = Collections.unmodifiableMap(new TreeMap<>(parameters));
	}

	/**
	 * Builds a result, deriving the checksum from the fields it is stored beside.
	 */
	public static RuleEvaluationResult of(String ruleCode, CommercialRuleType ruleType, UUID ruleId, int termVersion,
			LocalDate asOfDate, boolean applicable, boolean satisfied, String explanation,
			Map<String, String> parameters) {
		String checksum = checksum(ruleCode, ruleType, ruleId, termVersion, asOfDate, applicable, satisfied, parameters);
		return new RuleEvaluationResult(ruleCode, ruleType, ruleId, termVersion, asOfDate, applicable, satisfied,
				explanation, parameters, checksum);
	}

	/**
	 * A rule the transaction carried no input for. Never recorded as a check that
	 * passed.
	 */
	public static RuleEvaluationResult notApplicable(String ruleCode, CommercialRuleType ruleType, UUID ruleId,
			int termVersion, LocalDate asOfDate, String explanation, Map<String, String> parameters) {
		return of(ruleCode, ruleType, ruleId, termVersion, asOfDate, false, true, explanation, parameters);
	}

	public static RuleEvaluationResult satisfied(String ruleCode, CommercialRuleType ruleType, UUID ruleId,
			int termVersion, LocalDate asOfDate, String explanation, Map<String, String> parameters) {
		return of(ruleCode, ruleType, ruleId, termVersion, asOfDate, true, true, explanation, parameters);
	}

	public static RuleEvaluationResult violated(String ruleCode, CommercialRuleType ruleType, UUID ruleId,
			int termVersion, LocalDate asOfDate, String explanation, Map<String, String> parameters) {
		return of(ruleCode, ruleType, ruleId, termVersion, asOfDate, true, false, explanation, parameters);
	}

	/**
	 * Canonical, order-independent rendering of everything this result depends on.
	 * Parameters are sorted, so no hash iteration order can reach the checksum.
	 */
	public static String checksum(String ruleCode, CommercialRuleType ruleType, UUID ruleId, int termVersion,
			LocalDate asOfDate, boolean applicable, boolean satisfied, Map<String, String> parameters) {
		StringBuilder builder = new StringBuilder(160);
		// One field per line, so no two renderings can differ only by where a
		// boundary fell. The parameters block is last and is itself line-oriented,
		// which is what keeps a parameter value containing a separator from shifting
		// the fields after it.
		builder.append(ruleCode).append('\n')
				.append(ruleType.code()).append('\n')
				.append(ruleId).append('\n')
				.append(termVersion).append('\n')
				.append(asOfDate).append('\n')
				.append(applicable).append('\n')
				.append(satisfied).append('\n');
		new TreeMap<>(parameters).forEach((key, value) -> builder.append(key).append('=').append(value).append('\n'));
		// The explanation is deliberately absent from the digest. It is prose derived
		// from the fields above; hashing it would mean a reworded message changes the
		// checksum of a result that computed identically.
		return HashUtils.sha256(builder.toString().getBytes(StandardCharsets.UTF_8));
	}

}

package com.fintech.cfo.contract.dto;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

import com.fintech.cfo.contract.enums.CommercialRuleType;
import com.fintech.cfo.contract.model.CommercialRule;

/**
 * The whole rule set evaluated against one transaction.
 *
 * <p>Holds the per-rule results in a fixed order, so two runs over the same inputs
 * produce an identical list and a stored evaluation can be compared field by field
 * rather than as an unordered set.
 */
public record CommercialRuleEvaluation(List<RuleEvaluationResult> results) implements Serializable {

	public CommercialRuleEvaluation {
		results = results == null ? List.of() : List.copyOf(results);
	}

	/**
	 * Whether every rule that could be applied was satisfied.
	 *
	 * <p>Deliberately not "no rule was violated": a rule set with one applicable,
	 * satisfied rule and one not-applicable rule satisfies this, and the caller can
	 * still see from {@link #results()} which check never ran.
	 */
	public boolean isSatisfied() {
		return violations().isEmpty();
	}

	/**
	 * Codes of the rules that were applied and failed, in evaluation order.
	 */
	public List<String> violations() {
		return this.results.stream()
				.filter(result -> result.applicable() && !result.satisfied())
				.map(RuleEvaluationResult::ruleCode)
				.toList();
	}

	/**
	 * Rules that could not be applied at all, because the transaction carried none
	 * of their inputs. Recorded rather than dropped: a check that never ran is a gap
	 * in assurance.
	 */
	public List<String> notApplicable() {
		return this.results.stream()
				.filter(result -> !result.applicable())
				.map(RuleEvaluationResult::ruleCode)
				.toList();
	}

	/**
	 * Whether an {@link CommercialRuleType.ApprovalRequired} rule was applied and
	 * breached, meaning the transaction needs a human decision before release.
	 */
	public boolean requiresApproval() {
		return this.results.stream()
				.anyMatch(result -> result.applicable() && !result.satisfied()
						&& result.ruleType().equals(CommercialRuleType.ApprovalRequired.INSTANCE));
	}

	/**
	 * Whether a rule of the given type was applied and breached.
	 */
	public boolean isViolated(CommercialRule rule) {
		Objects.requireNonNull(rule, "rule must not be null");
		return this.results.stream()
				.anyMatch(result -> result.applicable() && !result.satisfied()
						&& result.ruleId().equals(rule.id()));
	}

}

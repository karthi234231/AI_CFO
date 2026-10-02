package com.fintech.cfo.financialtruth.model;

import java.time.Instant;
import java.util.List;

import com.fintech.cfo.financialtruth.enums.CalculationConfidence;
import com.fintech.cfo.financialtruth.enums.CalculationType;
import com.fintech.cfo.financialtruth.enums.RuleStatus;
import com.fintech.cfo.financialtruth.enums.VarianceType;
import com.fintech.cfo.financialtruth.rules.RuleEvaluationResult;
import com.fintech.cfo.shared.domain.Money;
import com.fintech.cfo.shared.domain.SourceReference;
import com.fintech.cfo.shared.exception.ValidationException;

/**
 * One persisted-shaped finding: a single rule's view of a single invoice line.
 *
 * <p>Maps column-for-column onto {@code calculation_results} in
 * {@code V6__create_calculations.sql}: the money columns and their currency columns
 * are nullable exactly where the schema allows them to be, and a variance is never
 * present without its currency.
 *
 * <p>A run emits one row per contributing rule per line, plus one combined row per
 * line. Only the combined rows carry an impact amount, because adding component
 * impacts together would count the same deviation twice.
 */
public record CalculationResult(
		CalculationType calculationType,
		RuleStatus status,
		String ruleCode,
		String ruleVersion,
		int lineNumber,
		Money expectedAmount,
		Money actualAmount,
		Variance variance,
		FinancialImpact impact,
		List<TermEvaluation> evaluatedTerms,
		SourceReference source,
		CalculationConfidence confidence,
		String explanation,
		Instant calculatedAt) {

	public CalculationResult {
		if (calculationType == null) {
			throw new ValidationException("calculationType must not be null");
		}
		if (status == null) {
			throw new ValidationException("status must not be null");
		}
		if (ruleCode == null || ruleCode.isBlank()) {
			throw new ValidationException("ruleCode must not be blank");
		}
		if (ruleVersion == null || ruleVersion.isBlank()) {
			throw new ValidationException("ruleVersion must not be blank");
		}
		if (calculatedAt == null) {
			throw new ValidationException("calculatedAt must not be null");
		}
		evaluatedTerms = evaluatedTerms == null ? List.of() : List.copyOf(evaluatedTerms);
		// The two branches below are the module's central guarantee, enforced at
		// construction so it cannot be bypassed by any factory or caller.
		if (status.carriesMonetaryClaim()) {
			if (variance == null) {
				throw new ValidationException("an " + status.code() + " result must carry a variance");
			}
			if (expectedAmount == null || actualAmount == null) {
				throw new ValidationException(
						"an " + status.code() + " result must carry both expected and actual amounts");
			}
		}
		else if (variance != null || expectedAmount != null || actualAmount != null) {
			// The single most damaging bug this module could have: a non-evaluated line
			// carrying a plausible-looking amount. Refuse to construct it at all.
			throw new ValidationException("an " + status.code()
					+ " result must not carry monetary figures; absence of evidence is not zero");
		}
	}

	/**
	 * Adapts a rule's finding into a stored-shaped result row.
	 *
	 * <p>The rule's code and version are supplied separately because they identify the
	 * <em>rule implementation</em>, which the finding itself does not carry; and the
	 * confidence is derived from the status by the engine rather than chosen here, so
	 * no rule can grade its own work.
	 *
	 * @param evaluation   what the rule concluded
	 * @param ruleCode     stable rule code, recorded for lineage
	 * @param ruleVersion  rule arithmetic version, recorded for lineage
	 * @param lineNumber   1-based invoice line this finding is about
	 * @param source       pointer back to the invoice row
	 * @param confidence   weight the finding deserves, derived from the status
	 * @param calculatedAt the single instant the run was evaluated at
	 */
	public static CalculationResult fromRule(RuleEvaluationResult evaluation, String ruleCode, String ruleVersion,
			int lineNumber, SourceReference source, CalculationConfidence confidence, Instant calculatedAt) {
		// Impact is null: only the combined row carries one, because adding component
		// impacts together would count the same deviation twice.
		return new CalculationResult(evaluation.calculationType(), evaluation.status(), ruleCode, ruleVersion, lineNumber,
				evaluation.expected() == null ? null : evaluation.expected().amount(),
				evaluation.actual() == null ? null : evaluation.actual().amount(), evaluation.variance(), null,
				evaluation.evaluatedTerms(), source, confidence, evaluation.explanation(), calculatedAt);
	}

	/**
	 * The authoritative per-line row: the net payable variance with its impact.
	 *
	 * <p>Confidence is read from the impact rather than passed separately, because the
	 * two are the same judgement: the engine already decided what weight the figure
	 * deserves when it built the impact, and letting a caller override it here would
	 * create two sources of truth.
	 */
	public static CalculationResult combined(String ruleCode, String ruleVersion, int lineNumber, Money expectedNet,
			Money actualNet, Variance variance, FinancialImpact impact, List<TermEvaluation> evaluatedTerms,
			SourceReference source, String explanation, Instant calculatedAt) {
		return new CalculationResult(CalculationType.COMBINED_VARIANCE, RuleStatus.Evaluated.INSTANCE, ruleCode,
				ruleVersion, lineNumber, expectedNet, actualNet, variance, impact, evaluatedTerms, source,
				impact == null ? CalculationConfidence.MEDIUM : impact.confidence(), explanation, calculatedAt);
	}

	public boolean hasVariance() {
		return this.variance != null;
	}

	/**
	 * The signed variance, or {@code null} when the row carries no monetary claim.
	 *
	 * <p>Null rather than zero: callers must branch, which is the point.
	 */
	public Money varianceAmount() {
		return this.variance == null ? null : this.variance.amount();
	}

	/**
	 * Stable textual form used to fingerprint a run. It includes the rule code and
	 * version, the money with its currency, the evaluated terms and the instant, so
	 * any change to any of them changes the fingerprint.
	 */
	public String canonicalForm() {
		StringBuilder text = new StringBuilder();
		text.append("result[line=").append(this.lineNumber)
				.append("|type=").append(this.calculationType.name())
				.append("|status=").append(this.status.code())
				.append("|rule=").append(this.ruleCode).append('@').append(this.ruleVersion)
				.append("|expected=").append(RoundingPolicy.canonicalMoney(this.expectedAmount))
				.append("|actual=").append(RoundingPolicy.canonicalMoney(this.actualAmount))
				.append('|');
		if (this.variance != null) {
			text.append(this.variance.canonicalForm());
		}
		else {
			// Explicit marker, so a row with no variance hashes differently from one
			// whose fields happened to be empty.
			text.append("variance=-");
		}
		text.append('|').append(this.impact == null ? "impact=-" : this.impact.canonicalForm())
				.append("|terms=");
		for (TermEvaluation term : this.evaluatedTerms) {
			text.append(term.canonicalForm()).append(';');
		}
		text.append("|confidence=").append(this.confidence.name())
				.append("|at=").append(this.calculatedAt.toString());
		return text.toString();
	}

}
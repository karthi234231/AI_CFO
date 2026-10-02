package com.fintech.cfo.financialtruth.rules;

import java.util.List;

import com.fintech.cfo.financialtruth.enums.CalculationType;
import com.fintech.cfo.financialtruth.enums.RuleStatus;
import com.fintech.cfo.financialtruth.model.ActualValue;
import com.fintech.cfo.financialtruth.model.ExpectedValue;
import com.fintech.cfo.financialtruth.model.TermEvaluation;
import com.fintech.cfo.financialtruth.model.Variance;
import com.fintech.cfo.shared.exception.ValidationException;

/**
 * What one rule concluded about one line.
 *
 * <p>The type exists to make one specific mistake impossible: reporting a monetary
 * figure for a rule that did not actually run, or could not run. The constructor
 * refuses any combination where a {@link RuleStatus} that
 * {@link RuleStatus#carriesMonetaryClaim() does not carry one} arrives with an
 * amount, a variance or evaluated terms.
 *
 * <p>Three findings are distinguishable, and they must stay distinguishable:
 *
 * <ul>
 * <li>{@link RuleStatus.Evaluated} - a number, with the terms that produced it.</li>
 * <li>{@link RuleStatus.NotApplicable} - the contract entitles nothing here. A true
 * statement, and deliberately not a zero variance.</li>
 * <li>{@link RuleStatus.IncompleteInputs} - the rule could not read the contract.
 * Also not a zero variance, and always carries a reason so the gap is chaseable.</li>
 * </ul>
 */
public final class RuleEvaluationResult {

	private final CalculationType calculationType;
	private final RuleStatus status;
	private final ExpectedValue expected;
	private final ActualValue actual;
	private final Variance variance;
	private final List<TermEvaluation> evaluatedTerms;
	private final String explanation;

	private RuleEvaluationResult(CalculationType calculationType, RuleStatus status, ExpectedValue expected,
			ActualValue actual, Variance variance, List<TermEvaluation> evaluatedTerms, String explanation) {
		if (calculationType == null) {
			throw new ValidationException("calculationType must not be null");
		}
		if (status == null) {
			throw new ValidationException("status must not be null");
		}
		if (explanation == null || explanation.isBlank()) {
			throw new ValidationException(
					"explanation is mandatory; a result nobody can explain is a result nobody can rely on");
		}
		if (status.carriesMonetaryClaim()) {
			if (expected == null || actual == null || variance == null) {
				throw new ValidationException("an " + status.code()
						+ " result must carry expected, actual and variance");
			}
		}
		else if (expected != null || actual != null || variance != null) {
			throw new ValidationException("an " + status.code()
					+ " result must not carry monetary figures; absence of evidence is not zero variance");
		}
		this.calculationType = calculationType;
		this.status = status;
		this.expected = expected;
		this.actual = actual;
		this.variance = variance;
		this.evaluatedTerms = evaluatedTerms == null ? List.of() : List.copyOf(evaluatedTerms);
		this.explanation = explanation;
	}

	/**
	 * @param calculationType  which calculation this finding contributes to
	 * @param expected         what the contract entitles, with its derivation
	 * @param actual           what was charged, with its source rows
	 * @param variance         the signed difference, type-tagged
	 * @param evaluatedTerms   the contract versions consulted, in evaluation order
	 * @param explanation      mandatory plain-language reason for the finding
	 * @return an EVALUATED finding, which must carry all three monetary figures
	 */
	public static RuleEvaluationResult evaluated(CalculationType calculationType, ExpectedValue expected, ActualValue actual,
			Variance variance, List<TermEvaluation> evaluatedTerms, String explanation) {
		return new RuleEvaluationResult(calculationType, RuleStatus.Evaluated.INSTANCE, expected, actual, variance,
				evaluatedTerms, explanation);
	}

	/** The rule is irrelevant here. Makes no monetary claim whatsoever. */
	public static RuleEvaluationResult notApplicable(CalculationType calculationType, String explanation) {
		return new RuleEvaluationResult(calculationType, RuleStatus.NotApplicable.INSTANCE, null, null, null, List.of(),
				explanation);
	}

	/** The rule could not be evaluated. Makes no monetary claim whatsoever. */
	public static RuleEvaluationResult incompleteInputs(CalculationType calculationType, String explanation) {
		return new RuleEvaluationResult(calculationType, RuleStatus.IncompleteInputs.INSTANCE, null, null, null,
				List.of(), explanation);
	}

	public CalculationType calculationType() {
		return this.calculationType;
	}

	/** The outcome itself; the engine maps it to a confidence level. */
	public RuleStatus status() {
		return this.status;
	}

	/**
	 * The expected side, or {@code null} for any status that carries no monetary claim.
	 *
	 * <p>Null rather than zero, so every caller must branch on {@link #isEvaluated()}
	 * before reading it.
	 */
	public ExpectedValue expected() {
		return this.expected;
	}

	/** The actual side, or {@code null} for any status that carries no monetary claim. */
	public ActualValue actual() {
		return this.actual;
	}

	/** The variance, or {@code null} for any status that carries no monetary claim. */
	public Variance variance() {
		return this.variance;
	}

	public List<TermEvaluation> evaluatedTerms() {
		return this.evaluatedTerms;
	}

	public String explanation() {
		return this.explanation;
	}

	/**
	 * Whether this outcome produced a monetary claim.
	 *
	 * <p>Delegates to the status rather than checking the fields, so the answer is a
	 * property of the variant and cannot disagree with what the constructor allowed.
	 */
	public boolean isEvaluated() {
		return this.status.carriesMonetaryClaim();
	}

}
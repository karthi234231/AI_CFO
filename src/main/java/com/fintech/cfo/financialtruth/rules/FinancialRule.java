package com.fintech.cfo.financialtruth.rules;

import com.fintech.cfo.financialtruth.enums.CalculationType;

/**
 * A single, versioned commercial check.
 *
 * <p>An interface with no framework annotations on purpose. Rules are plain objects
 * with constructor-injected collaborators, so they can be instantiated and exercised
 * in a unit test with no container, no database and no clock. Wiring them into a
 * Spring context belongs to whoever owns the composition root.
 *
 * <h2>Contract for implementors</h2>
 * <ul>
 * <li>Pure. No clock, no randomness, no I/O, no locale-sensitive formatting, and no
 * iteration over a {@code HashMap} whose order could reach a sum.</li>
 * <li>{@link #version()} changes whenever the arithmetic changes. It is recorded on
 * every result, so a historical figure can say which rule produced it.</li>
 * <li>Missing inputs are reported through
 * {@link RuleEvaluationResult#incompleteInputs}, never turned into a default.</li>
 * <li>Where a rule cannot proceed because the <em>contract</em> does not support the
 * conclusion, it raises {@code BusinessRuleException} rather than returning a
 * number nobody agreed to.</li>
 * </ul>
 */
public interface FinancialRule {

	/** Stable machine-readable rule code, e.g. {@code PRICING_VARIANCE}. */
	String code();

	/** Semantic version of this rule's arithmetic. Recorded on every result. */
	String version();

	/** Which calculation this rule contributes to. */
	CalculationType calculationType();

	/**
	 * Whether the rule could ever produce a finding for this line. Cheap predicate;
	 * evaluated before {@link #evaluate(RuleContext)}.
	 */
	boolean appliesTo(RuleContext context);

	/** Evaluates the rule. Must not mutate the context or read a clock. */
	RuleEvaluationResult evaluate(RuleContext context);

	/** {@code code@version}, the form stored in {@code rule_version}. */
	default String versionedCode() {
		return this.code() + "@" + this.version();
	}

}
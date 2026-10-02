/**
 * The pluggable variance rules and the context they evaluate against.
 *
 * <p>A {@link com.fintech.cfo.financialtruth.rules.FinancialRule} is pure: it
 * reads a {@link com.fintech.cfo.financialtruth.rules.RuleContext} and returns a
 * {@link com.fintech.cfo.financialtruth.rules.RuleEvaluationResult}, with no clock,
 * no I/O and no state. That is what lets the engine run the same rule set over the
 * same snapshot months later and get a byte-identical answer.
 *
 * <p>Two shipped rules: {@link com.fintech.cfo.financialtruth.rules.PricingVarianceRule}
 * for unit price, and {@link com.fintech.cfo.financialtruth.rules.DiscountVarianceRule}
 * for entitlement. Both refuse to produce a figure they cannot justify - they raise
 * {@code BusinessRuleException} when a contract term is missing outright, and report
 * {@code INCOMPLETE_INPUTS} when an input exists but is unusable. Neither ever
 * substitutes zero for an unknown.
 *
 * <p>{@link com.fintech.cfo.financialtruth.rules.RuleContext} and
 * {@link com.fintech.cfo.financialtruth.rules.RuleEvaluationResult} are final
 * classes rather than records: the first defensively re-validates and copies the
 * term lists handed to it so a rule can never mutate the caller's snapshot, and the
 * second enforces that a rule outcome that carries no monetary claim carries no
 * monetary figures.
 */
@NullMarked
package com.fintech.cfo.financialtruth.rules;

import org.jspecify.annotations.NullMarked;
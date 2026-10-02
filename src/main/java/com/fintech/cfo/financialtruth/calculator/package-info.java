/**
 * Arithmetic and composition for the Financial Truth Engine. No Spring, no I/O.
 *
 * <p>{@link com.fintech.cfo.financialtruth.calculator.ExpectedAmountCalculator} and
 * {@link com.fintech.cfo.financialtruth.calculator.ActualAmountCalculator} produce
 * the two sides of every comparison; together they own the rounding policy, so the
 * same scale and mode are applied on both sides of every variance by construction.
 *
 * <p>{@link com.fintech.cfo.financialtruth.calculator.VarianceCalculator} subtracts
 * and asserts that a reported net variance decomposes exactly into its components -
 * the check that would otherwise let a discount be counted twice.
 *
 * <p>{@link com.fintech.cfo.financialtruth.calculator.ImpactAggregator} sums per-line
 * findings into one figure per currency, refusing component rows and refusing to
 * present a total that silently omits unevaluable lines at high confidence.
 *
 * <p>{@link com.fintech.cfo.financialtruth.calculator.FinancialTruthEngine} composes
 * the rules into a run: it pins the rule-set version, evaluates every line, and
 * emits one combined row per line only when that line's decomposition is provable.
 */
@NullMarked
package com.fintech.cfo.financialtruth.calculator;

import org.jspecify.annotations.NullMarked;
/**
 * Records for handing figures in and out of the module.
 *
 * <p>These are the transport shape only. They never compute: every amount they
 * carry was produced by a calculator or a rule, and every optional field is
 * explicitly {@link org.jspecify.annotations.Nullable} because "no expected amount
 * was established" is a real answer this module gives, not a gap in the wire format.
 *
 * <p>{@link com.fintech.cfo.financialtruth.dto.AmountResponse},
 * {@link com.fintech.cfo.financialtruth.dto.VarianceResponse} and
 * {@link com.fintech.cfo.financialtruth.dto.FinancialImpactResponse} convert a
 * {@code Money} into an amount and a currency as separate fields, matching the V6
 * {@code NUMERIC}/{@code CHAR(3)} column pairs, and refuse to emit a non-null amount
 * with a null currency.
 */
@NullMarked
package com.fintech.cfo.financialtruth.dto;

import org.jspecify.annotations.NullMarked;
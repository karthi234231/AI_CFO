/**
 * Immutable carriers for the Financial Truth Engine: the input snapshot, the terms
 * read from it, and every result, run and impact produced from it.
 *
 * <p>Every type here is a record, except the two that own a derived invariant a
 * record cannot express:
 * {@link com.fintech.cfo.financialtruth.model.Variance} computes its amount once as
 * {@code actual - expected} in {@code Money}, so no caller can present an amount
 * that disagrees with its own figures; and
 * {@link com.fintech.cfo.financialtruth.model.CalculationResult} and
 * {@link com.fintech.cfo.financialtruth.model.CalculationRun} validate cross-field
 * invariants in a compact constructor.
 *
 * <p>{@link com.fintech.cfo.financialtruth.model.CalculationInput} owns the
 * canonical form that is hashed into the run's input checksum, and
 * {@link com.fintech.cfo.financialtruth.model.CalculationResult} plus
 * {@link com.fintech.cfo.financialtruth.model.CalculationRun} own the canonical
 * forms hashed into the deterministic fingerprint. Those forms sort and encode
 * terms in a fixed order, so reordering the caller's input lists cannot change a
 * checksum.
 *
 * <p>All money is {@code BigDecimal} via {@code com.fintech.cfo.shared.domain.Money}.
 * No {@code double} or {@code float} appears anywhere in this package.
 */
@NullMarked
package com.fintech.cfo.financialtruth.model;

import org.jspecify.annotations.NullMarked;
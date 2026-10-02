/**
 * Closed value sets for the Financial Truth Engine.
 *
 * <p>{@link com.fintech.cfo.financialtruth.enums.VarianceType},
 * {@link com.fintech.cfo.financialtruth.enums.RuleStatus} and
 * {@link com.fintech.cfo.financialtruth.enums.CalculationStatus} are sealed
 * interfaces of records, so every {@code switch} over them is exhaustiveness
 * checked and a new variant cannot be added without deciding how it behaves.
 *
 * <p>Each implements {@link com.fintech.cfo.financialtruth.enums.CodedEnum}: the
 * variant's {@code code()} is the stable value written to and read from the V6
 * {@code VARCHAR} columns and the value that enters a run's reproducibility
 * checksum, so the persisted string and the hashed string cannot drift apart.
 *
 * <p>{@code CalculationType}, {@code PricingType}, {@code DiscountType},
 * {@code ImpactDirection} and {@code CalculationConfidence} remain enums. They
 * name a stored value with no per-variant behaviour to model, and
 * {@code CalculationConfidence} in particular is ordered by an explicit
 * {@link com.fintech.cfo.financialtruth.enums.CalculationConfidence#isLessConfidentThan}
 * comparison rather than by ordinal.
 */
@NullMarked
package com.fintech.cfo.financialtruth.enums;

import org.jspecify.annotations.NullMarked;
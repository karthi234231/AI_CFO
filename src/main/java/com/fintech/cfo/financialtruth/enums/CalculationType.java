package com.fintech.cfo.financialtruth.enums;

/**
 * Which calculation a result row belongs to.
 *
 * <p>Maps to {@code calculation_results.calculation_type} / {@code
 * calculation_runs.calculation_type} in {@code V6__create_calculations.sql}, so the
 * stored value length matters (schema allows 48 characters).
 */
public enum CalculationType {

	/**
	 * Invoiced unit price versus contracted unit price.
	 *
	 * <p>A component, measured on the gross. Never summed with the others except through
	 * the combined row.
	 */
	PRICING_VARIANCE,

	/**
	 * Discount granted versus discount entitled by the contract.
	 *
	 * <p>A component, measured on the discount itself, so its sign reads opposite to the
	 * other two.
	 */
	DISCOUNT_VARIANCE,

	/**
	 * Net payable variance, composed from the pricing and discount components.
	 *
	 * <p>The authoritative figure. Only rows of this type are accepted by the impact
	 * aggregator, which is what prevents a deviation being counted twice.
	 */
	COMBINED_VARIANCE

}
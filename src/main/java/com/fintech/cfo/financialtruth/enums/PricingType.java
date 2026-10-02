package com.fintech.cfo.financialtruth.enums;

/**
 * How a pricing term expresses its unit price. Maps to
 * {@code pricing_terms.pricing_type}.
 *
 * <p>Only {@link #FIXED_UNIT_PRICE} yields a deterministic expected amount today.
 * The remaining types are modelled explicitly rather than omitted so that a term
 * of an unsupported kind is reported as "cannot be evaluated" instead of being
 * silently read as a fixed price.
 */
public enum PricingType {

	/**
	 * A single contracted unit price for the effective window.
	 *
	 * <p>The only type the engine can turn into a deterministic expected amount today.
	 */
	FIXED_UNIT_PRICE,

	/**
	 * Price derived from a price list; the list binding is not implemented yet.
	 *
	 * <p>A line priced this way is reported {@code INCOMPLETE_INPUTS} rather than
	 * evaluated against the wrong basis.
	 */
	LIST_PRICE,

	/**
	 * Volume-tiered pricing; requires a tier table this module does not have.
	 *
	 * <p>Reported {@code INCOMPLETE_INPUTS}: the contract is likely valid, the module is
	 * simply not the authority on which tier applies.
	 */
	TIERED

}
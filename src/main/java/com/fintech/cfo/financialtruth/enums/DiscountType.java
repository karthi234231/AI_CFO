package com.fintech.cfo.financialtruth.enums;

/**
 * How a discount term expresses its value. Maps to
 * {@code discount_terms.discount_type}.
 */
public enum DiscountType {

	/**
	 * A rate in percent of the contract gross, for example {@code 10} meaning ten percent.
	 *
	 * <p>Carries no currency of its own: the percentage inherits the currency of the gross
	 * it reduces. A value above 100 is treated as unusable rather than honoured.
	 */
	PERCENTAGE,

	/**
	 * A flat monetary amount for the line. Always carries a currency.
	 *
	 * <p>Taken as written and then clamped by the term's own cap and by the gross itself.
	 */
	FIXED_AMOUNT

}
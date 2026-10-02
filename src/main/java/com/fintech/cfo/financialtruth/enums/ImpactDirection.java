package com.fintech.cfo.financialtruth.enums;

import java.math.BigDecimal;

/**
 * Which way money moved, from the customer's point of view.
 *
 * <p>This is derived, never supplied. It exists so a report can say
 * "recoverable from the supplier" without every reader having to remember the sign
 * convention.
 */
public enum ImpactDirection {

	/**
	 * The customer was charged more than the contract entitles. Under the module
	 * sign convention (variance = actual - expected) this is a positive variance
	 * and the money is recoverable by the customer.
	 */
	CUSTOMER_OVERPAY,

	/** The customer was charged less than the contract entitles; the supplier under-billed. */
	CUSTOMER_UNDERPAY,

	/** Charges matched the contract exactly. */
	NEUTRAL;

	/**
	 * @param amount signed variance amount; {@code null} is treated as neutral
	 *            because no claim is being made
	 * @return the direction implied by the sign, or {@link #NEUTRAL} for zero and null
	 */
	public static ImpactDirection of(BigDecimal amount) {
		if (amount == null || amount.signum() == 0) {
			// signum, not equalsTo(ZERO): scale-insensitive, so 0.0000 and 0 are both
			// recognised as neutral.
			return NEUTRAL;
		}
		return amount.signum() > 0 ? CUSTOMER_OVERPAY : CUSTOMER_UNDERPAY;
	}

}
package com.fintech.cfo.shared.exception;

/**
 * A valid request cannot proceed because a business rule rejects it. FinancialTruthEngine must raise this rather than returning fabricated or default monetary values.
 */
public class BusinessRuleException extends DomainException {

	private static final long serialVersionUID = 1L;

	/** Stable code mapped to HTTP 422 by the exception handler. */
	public static final String CODE = "BUSINESS_RULE_VIOLATION";

	/**
	 * @param message explanation of which rule rejected the operation; written
	 *                for a client to read
	 */
	public BusinessRuleException(String message) {
		super(CODE, message);
	}

	/**
	 * @param message explanation of which rule rejected the operation
	 * @param cause   underlying failure, kept for logs only
	 */
	public BusinessRuleException(String message, Throwable cause) {
		super(CODE, message, cause);
	}

}
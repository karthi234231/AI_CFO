package com.fintech.cfo.shared.exception;

/**
 * Domain-level validation failure: invalid business input or a violated domain invariant. Not used for HTTP JSON parsing or framework bean-validation errors.
 */
public class ValidationException extends DomainException {

	private static final long serialVersionUID = 1L;

	/** Stable code mapped to HTTP 400 by the exception handler. */
	public static final String CODE = "VALIDATION_ERROR";

	/**
	 * @param message which field or invariant was violated, naming the field
	 *                where possible so a reviewer can act on the message alone
	 */
	public ValidationException(String message) {
		super(CODE, message);
	}

	/**
	 * @param message which field or invariant was violated
	 * @param cause   underlying failure, kept for logs only
	 */
	public ValidationException(String message, Throwable cause) {
		super(CODE, message, cause);
	}

}
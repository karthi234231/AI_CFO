package com.fintech.cfo.shared.exception;

/**
 * Requested operation conflicts with current state: optimistic locking, duplicate operation, idempotency conflict, or illegal state transition.
 */
public class ConflictException extends DomainException {

	private static final long serialVersionUID = 1L;

	/** Stable code mapped to HTTP 409 by the exception handler. */
	public static final String CODE = "RESOURCE_CONFLICT";

	/**
	 * @param message what currently conflicts, e.g. that a concurrent request
	 *                already holds the idempotency key
	 */
	public ConflictException(String message) {
		super(CODE, message);
	}

	/**
	 * @param message what currently conflicts
	 * @param cause   underlying failure, e.g. an optimistic-lock or constraint
	 *                violation
	 */
	public ConflictException(String message, Throwable cause) {
		super(CODE, message, cause);
	}

}
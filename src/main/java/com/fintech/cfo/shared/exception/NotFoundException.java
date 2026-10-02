package com.fintech.cfo.shared.exception;

/**
 * Requested resource cannot be resolved. When tenant isolation is active, the message must not reveal whether a record belongs to another tenant.
 */
public class NotFoundException extends DomainException {

	private static final long serialVersionUID = 1L;

	/** Stable code mapped to HTTP 404 by the exception handler. */
	public static final String CODE = "RESOURCE_NOT_FOUND";

	/**
	 * @param message deliberately non-specific under tenant isolation; must not
	 *                distinguish "absent" from "belongs to someone else"
	 */
	public NotFoundException(String message) {
		super(CODE, message);
	}

	/**
	 * @param message deliberately non-specific under tenant isolation
	 * @param cause   underlying failure, kept for logs only
	 */
	public NotFoundException(String message, Throwable cause) {
		super(CODE, message, cause);
	}

}
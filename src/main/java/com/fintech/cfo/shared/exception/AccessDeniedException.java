package com.fintech.cfo.shared.exception;

/**
 * Authenticated user is not authorized to perform the requested action. Spring Security still owns ordinary HTTP authorization failures; this is for explicit application-layer business/resource authorization decisions.
 */
public class AccessDeniedException extends DomainException {

	private static final long serialVersionUID = 1L;

	/**
	 * Exposed as a constant so the exception handler and any client-facing code
	 * reference the same literal rather than repeating the string.
	 */
	public static final String CODE = "ACCESS_DENIED";

	/**
	 * @param message explanation that is safe to return to the caller; must not
	 *                disclose the existence of a resource in another tenant
	 */
	public AccessDeniedException(String message) {
		super(CODE, message);
	}

	/**
	 * @param message explanation that is safe to return to the caller
	 * @param cause   underlying failure, kept for logs only
	 */
	public AccessDeniedException(String message, Throwable cause) {
		super(CODE, message, cause);
	}

}
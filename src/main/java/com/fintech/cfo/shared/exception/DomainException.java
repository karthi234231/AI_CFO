package com.fintech.cfo.shared.exception;

/**
 * Base application/domain exception carrying a stable, machine-readable error
 * code.
 *
 * <p>The code (for example {@code VALIDATION_ERROR}, {@code RESOURCE_NOT_FOUND})
 * is the contract that clients should depend on; the message is a
 * human-readable diagnostic only.
 */
public class DomainException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final String code;

	/**
	 * @param code    stable machine-readable code surfaced to clients
	 * @param message human-readable diagnostic
	 * @throws IllegalArgumentException if the code is blank, which would leave the
	 *                                  client with no stable contract to branch on
	 */
	public DomainException(String code, String message) {
		super(message);
		this.code = requireCode(code);
	}

	/**
	 * @param code    stable machine-readable code surfaced to clients
	 * @param message human-readable diagnostic
	 * @param cause   underlying failure, kept for logs only
	 */
	public DomainException(String code, String message, Throwable cause) {
		super(message, cause);
		this.code = requireCode(code);
	}

	/**
	 * @return the stable code, never the message, that clients should branch on
	 */
	public String getCode() {
		return this.code;
	}

	/**
	 * The invariant the whole hierarchy rests on: a domain exception without a
	 * code cannot be translated into a meaningful HTTP response, so it is a
	 * programming error rather than a runtime condition.
	 */
	private static String requireCode(String code) {
		if (code == null || code.isBlank()) {
			throw new IllegalArgumentException("code must not be blank");
		}
		return code;
	}

}

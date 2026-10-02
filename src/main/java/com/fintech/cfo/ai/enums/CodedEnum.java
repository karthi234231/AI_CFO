package com.fintech.cfo.ai.enums;

import com.fintech.cfo.shared.exception.ValidationException;

/**
 * The narrow contract every coded enum in this package obeys: a stable,
 * persisted string code, strict case/whitespace normalisation on read, and a
 * width guard so a stored value can never silently exceed its column.
 *
 * <p>This is deliberately a local copy of the identical contract in
 * {@code opportunity.enums} and {@code contract.enums}: the {@code ai} module
 * is not permitted to import those business packages, and the behaviour is
 * meant to be byte-for-byte identical, so a copy is safer than a shared
 * dependency would be.
 */
public interface CodedEnum {

	/**
	 * The persisted representation of a variant.
	 *
	 * @return a non-null, non-blank uppercase code
	 */
	String code();

	/**
	 * Width of the column that stores {@link #code()} in the ai output tables.
	 *
	 * <p>Each variant is responsible for asserting this against its own value on
	 * read, so a too-wide code fails fast instead of truncating silently in a
	 * later layer.
	 *
	 * @param maxCodeLength the column width, declared by the implementing enum
	 */
	default void requireColumnWidth(int maxCodeLength) {
		if (this.code().length() > maxCodeLength) {
			throw new ValidationException(
					this.code() + " exceeds the allowed width of " + maxCodeLength + " characters");
		}
	}

	/**
	 * Normalises a stored code before comparison: {@code null}/blank rejected
	 * up front, trimmed and upper-cased so a stray space or a lower-case write
	 * does not make a valid value look unknown.
	 *
	 * @param code        the raw stored value
	 * @param typeName    the enum name, used only to build a clear error message
	 * @return the normal form that {@link #code()} is compared against
	 * @throws ValidationException if {@code code} is null or blank
	 */
	static String normalise(String code, String typeName) {
		if (code == null || code.isBlank()) {
			throw new ValidationException(typeName + " code must not be blank");
		}
		return code.trim().toUpperCase();
	}

}

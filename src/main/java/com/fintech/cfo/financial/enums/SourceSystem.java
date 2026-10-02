package com.fintech.cfo.financial.enums;

import java.util.Locale;

import com.fintech.cfo.shared.exception.ValidationException;

/**
 * A supported upstream accounting system, persisted as
 * {@code source_system VARCHAR(64)} in V4 and forming part of every entity
 * resolution key.
 *
 * <p>Deliberately a closed set rather than free text. The value is not merely a
 * label: it selects the number-format tolerance the parsers apply, so an
 * unrecognised system must be rejected rather than guessed at with a default
 * profile that could misread a lakh/crore grouping as a decimal comma.
 */
public enum SourceSystem {

	/** Indian on-premise accounting suites; group digits in threes from the right. */
	TALLY("TALLY"),

	/** Global ERP; ISO dates and western grouping. */
	SAP("SAP"),

	/** Cloud accounting with ISO dates and western grouping. */
	ZOHO_BOOK("ZOHO_BOOK"),

	/** Cloud accounting with ISO dates and western grouping. */
	QUICKBOOKS("QUICKBOOKS"),

	/**
	 * Undeclared source. Accepted only with the western profile, and the choice
	 * is recorded in the {@code SourceReference} so a reviewer can see the
	 * profile was assumed rather than known.
	 */
	GENERIC("GENERIC");

	/** Widest {@code source_system} column in V4. */
	static final int MAX_CODE_LENGTH = 64;

	private final String code;

	SourceSystem(String code) {
		// The code is derived from the constant name everywhere else (code(), and
		// the exhaustive switch in fromCode), so a divergence here would make one
		// of those two paths lie. Caught at class-init, not at write time.
		if (!this.name().equals(code)) {
			throw new IllegalStateException("source code must equal the constant name: " + this.name() + " != " + code);
		}
		// Every canonical row stores this code in a VARCHAR(64) and it is part of
		// every EntityResolutionKey, so an over-long code would be truncated by the
		// database and would then key to the wrong row.
		if (code.length() > MAX_CODE_LENGTH) {
			throw new IllegalStateException("source code '" + code + "' exceeds V4 column width " + MAX_CODE_LENGTH);
		}
		this.code = code;
	}

	/** Value written to and read from the {@code source_system} column. */
	public String code() {
		return this.code;
	}

	/**
	 * Resolves a persisted or source-supplied code.
	 *
	 * <p>Never defaults, and never maps an unrecognised system onto
	 * {@link #GENERIC}: {@code GENERIC} is an explicit assertion that the source
	 * is unknown and the western profile is being assumed, which is recorded in
	 * the {@code SourceReference}. An unrecognised code is a data error, and
	 * silently substituting a profile could misread Indian digit grouping as a
	 * decimal separator.
	 *
	 * @param code persisted or source-supplied system code; trimmed and
	 *             upper-cased before matching
	 * @return the matching constant
	 * @throws ValidationException if {@code code} is null, blank, or not a known
	 *                              source system
	 */
	public static SourceSystem fromCode(String code) {
		// Blank is refused rather than mapped to GENERIC: GENERIC is an explicit
		// assertion that a profile was assumed, not a catch-all for missing data.
		if (code == null || code.isBlank()) {
			throw new ValidationException("source system code must not be blank");
		}
		// ROOT, not the default locale: a Turkish-locale host upper-cases "i" to
		// "İ" and would silently fail to match.
		String normalized = code.trim().toUpperCase(Locale.ROOT);
		// Linear scan over the closed set rather than a map lookup, so a new
		// constant becomes resolvable the moment it is declared. A miss throws
		// rather than falling back to GENERIC: an unknown source means the parsing
		// profile is unknown, and guessing one could read 12,34,567.89 as
		// 12.3456789.
		for (SourceSystem candidate : values()) {
			if (candidate.code.equals(normalized)) {
				return candidate;
			}
		}
		throw new ValidationException("unknown source system code: " + code);
	}

}

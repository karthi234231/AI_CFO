package com.fintech.cfo.ingestion.validator;

import java.util.Objects;

/**
 * Neutralises spreadsheet formula injection in uploaded text.
 *
 * <p>Any cell whose text begins with {@code =}, {@code +}, {@code -} or
 * {@code @} is interpreted as a formula by Excel and LibreOffice when the file is
 * later opened or re-exported. That is how a ledger upload becomes remote code
 * execution on a finance analyst's laptop. The defence used here is the one the
 * spreadsheet applications themselves understand: a leading apostrophe, which
 * makes the remainder literal text.
 *
 * <p>A signed decimal is <em>not</em> a payload. {@code -1000} and
 * {@code +4.5} are ordinary amounts and are left exactly as they were, because
 * mangling them would corrupt the financial record. Only a {@code +}/{@code -}
 * cell that is not a plain decimal is escaped.
 *
 * <p>Unicode bidirectional override and isolate characters are removed outright.
 * They are never meaningful in a ledger field and are used only to make one value
 * render as another.
 *
 * <p>Embedded newlines are <em>not</em> rewritten. A quoted CSV field may
 * legitimately contain one, and silently folding it would change the evidence.
 * Newlines cannot on their own start a formula; the explicit formula prefix check
 * already covers a value such as {@code "\r=cmd"}.
 */
public final class FormulaInjectionSanitiser {

	/** The escape understood by Excel and LibreOffice for literal cell text. */
	public static final char ESCAPE = '\'';

	private static final String BIDI_AND_FORMAT_CHARACTERS = "\u202A\u202B\u202C\u202D\u202E\u2066\u2067\u2068"
			+ "\u2069\u200E\u200F\u200B\u200C\u200D\uFEFF";

	private FormulaInjectionSanitiser() {
	}

	/** Allows the facade to hold one shared, stateless instance. */
	public static FormulaInjectionSanitiser instance() {
		return new FormulaInjectionSanitiser();
	}

	/**
	 * @param text the value after sanitisation
	 * @param neutralised true when a protective prefix was added
	 * @param removedControlCharacters true when bidi or format characters were
	 * stripped
	 */
	public record Result(String text, boolean neutralised, boolean removedControlCharacters) {

		public Result {
			Objects.requireNonNull(text, "text must not be null");
		}

	}

	/**
	 * @return the value with formula prefixes escaped and bidi characters removed;
	 * a {@code null} input is returned as-is because a missing value is the
	 * business of the required-field validator, not of this transformer
	 */
	public static Result sanitise(String raw) {
		if (raw == null) {
			return new Result("", false, false);
		}
		StringBuilder cleaned = new StringBuilder(raw.length());
		boolean removed = false;
		for (int i = 0; i < raw.length(); i++) {
			char character = raw.charAt(i);
			// NUL and the bidi/format family are dropped outright rather than escaped:
			// they are never meaningful in a ledger field and exist only to make one
			// value render as another.
			if (character == '\u0000' || BIDI_AND_FORMAT_CHARACTERS.indexOf(character) >= 0) {
				removed = true;
				continue;
			}
			cleaned.append(character);
		}
		String value = cleaned.toString();
		if (value.isEmpty()) {
			return new Result(value, false, removed);
		}
		// Escaping happens after stripping, so a value that only looked like a payload
		// because of a hidden character is judged on what will actually be stored.
		if (isInjectionPayload(value)) {
			return new Result(ESCAPE + value, true, removed);
		}
		return new Result(value, false, removed);
	}

	/**
	 * @return true when the value would be treated as a formula by a spreadsheet
	 */
	public static boolean isInjectionPayload(String value) {
		if (value == null || value.isEmpty()) {
			return false;
		}
		char first = value.charAt(0);
		// A leading tab, CR or LF is the classic bypass: spreadsheets skip it and then
		// read what follows as a formula, so it is a trigger in its own right.
		if (first == '\t' || first == '\r' || first == '\n') {
			return true;
		}
		if (first == '=' || first == '@') {
			return true;
		}
		// A signed decimal is ordinary data, not a payload. Only a +/- cell that is
		// not a plain number is escaped, so -1000 and +4.5 survive untouched.
		if (first == '+' || first == '-') {
			return !TypedValueParser.isDecimal(value);
		}
		return false;
	}

	/**
	 * Removes a single protective prefix, if present.
	 *
	 * <p>Exposed so a downstream consumer that is producing a non-spreadsheet
	 * artefact can recover the analyst-facing value without the escape marker.
	 */
	public static String unescape(String value) {
		if (value != null && value.length() > 1 && value.charAt(0) == ESCAPE
				&& isInjectionPayload(value.substring(1))) {
			return value.substring(1);
		}
		return value;
	}

}
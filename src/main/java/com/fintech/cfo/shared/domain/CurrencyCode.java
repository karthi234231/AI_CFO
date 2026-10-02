package com.fintech.cfo.shared.domain;

import java.io.Serializable;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Immutable ISO-4217 style three-letter currency identifier.
 *
 * <p>Carries no exchange-rate or conversion behaviour; FX concerns belong to a dedicated
 * financial module.
 *
 * <p>Interned deliberately. A {@code CurrencyCode} sits inside every {@link Money}, and
 * {@link #of(String)} sits on the read path of invoice and transaction resolution, so
 * allocating a fresh wrapper per call produced one short-lived object per amount examined.
 * The cache is naturally bounded: a valid code is exactly three upper-case ASCII letters, so
 * it cannot exceed 26^3 entries regardless of input, which is what makes an unbounded
 * {@link ConcurrentHashMap} safe here rather than a leak.
 *
 * <p>The shape check is a length test plus a range test rather than {@code ^[A-Z]{3}$}: a
 * three-character comparison does not need a regular-expression engine, and this sits in the
 * same hot path.
 */
public final class CurrencyCode implements Serializable {

	private static final long serialVersionUID = 1L;

	/** Length of an ISO-4217 alphabetic code. */
	private static final int CODE_LENGTH = 3;

	/** Bounded by 26^3 distinct codes; see the class comment. */
	private static final Map<String, CurrencyCode> INTERNED = new ConcurrentHashMap<>();

	private static final CurrencyCode INR = intern("INR");
	private static final CurrencyCode USD = intern("USD");
	private static final CurrencyCode EUR = intern("EUR");

	private final String value;

	private CurrencyCode(String value) {
		this.value = value;
	}

	/**
	 * Parses and interns a currency code.
	 *
	 * <p>Callers may supply the code in any case with surrounding whitespace; the
	 * normalised form is what gets validated and stored, so {@code "inr"} and
	 * {@code "INR"} yield the same instance and compare equal.
	 *
	 * @param value three-letter alphabetic code
	 * @return the interned code
	 * @throws IllegalArgumentException if the value is not three upper-case
	 *                                  ASCII letters after normalisation
	 */
	public static CurrencyCode of(String value) {
		Objects.requireNonNull(value, "currency must not be null");
		// Locale.ROOT, not the default locale: a Turkish default turns "i" into
		// a dotted capital and would corrupt the normalisation.
		String normalized = value.trim().toUpperCase(Locale.ROOT);
		if (!isAlphaUpper3(normalized)) {
			throw new IllegalArgumentException("invalid currency code: " + value);
		}
		// Read before compute: the hit path is a plain map read with no locking.
		CurrencyCode cached = INTERNED.get(normalized);
		if (cached != null) {
			return cached;
		}
		// computeIfAbsent is atomic per key, so a racing pair of callers cannot
		// intern two unequal instances for the same code.
		return INTERNED.computeIfAbsent(normalized, CurrencyCode::new);
	}

	/** @return the interned {@code INR} instance */
	public static CurrencyCode inr() {
		return INR;
	}

	/** @return the interned {@code USD} instance */
	public static CurrencyCode usd() {
		return USD;
	}

	/** @return the interned {@code EUR} instance */
	public static CurrencyCode eur() {
		return EUR;
	}

	/** @return the normalised three-letter code */
	public String value() {
		return this.value;
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof CurrencyCode that && this.value.equals(that.value);
	}

	@Override
	public int hashCode() {
		return this.value.hashCode();
	}

	@Override
	public String toString() {
		return this.value;
	}

	private static CurrencyCode intern(String code) {
		return INTERNED.computeIfAbsent(code, CurrencyCode::new);
	}

	/**
	 * Shape check for an already-normalised code.
	 *
	 * <p>The first and last characters are range-tested before the loop, which
	 * rejects the common cases (wrong length, punctuation at the edges) without
	 * entering it; only a code that starts and ends correctly needs the inner
	 * check.
	 */
	private static boolean isAlphaUpper3(String code) {
		if (code.length() != CODE_LENGTH) {
			return false;
		}
		char first = code.charAt(0);
		char last = code.charAt(CODE_LENGTH - 1);
		if (first < 'A' || last > 'Z') {
			return false;
		}
		for (int index = 1; index < CODE_LENGTH - 1; index++) {
			char character = code.charAt(index);
			if (character < 'A' || character > 'Z') {
				return false;
			}
		}
		return true;
	}

}

package com.fintech.cfo.shared.enums;

import java.util.Locale;

import com.fintech.cfo.shared.domain.CurrencyCode;

/**
 * Convenience enumeration of currencies supported in Phase 0.
 *
 * <p>Not an exhaustive ISO-4217 list: {@link CurrencyCode#of(String)} accepts
 * any valid three-letter code. This enum exists for configuration values and
 * enum-typed API parameters where a closed set is genuinely useful.
 */
/**
 * The closed set of currencies the business currently transacts in:
 * {@code INR} (reporting currency), {@code USD}, {@code EUR}, {@code GBP},
 * {@code AED}, {@code SGD}, {@code AUD} and {@code JPY}.
 *
 * <p>Values are the ISO-4217 alphabetic codes, so {@link #toCurrencyCode} and
 * {@link #from(CurrencyCode)} are lossless name lookups rather than a separate
 * mapping table that could drift from the constants.
 */
public enum Currency {

	INR,
	USD,
	EUR,
	GBP,
	AED,
	SGD,
	AUD,
	JPY;

	/**
	 * @return the shared-kernel value object for this currency; goes through
	 *         {@code CurrencyCode.of} so the interned instance is returned
	 */
	public CurrencyCode toCurrencyCode() {
		return CurrencyCode.of(this.name());
	}

	/**
	 * @param currencyCode code to map back to an enum constant
	 * @return the matching constant
	 * @throws IllegalArgumentException if the code is a valid ISO code but outside
	 *                                  this curated set
	 */
	public static Currency from(CurrencyCode currencyCode) {
		return Currency.valueOf(currencyCode.value().toUpperCase(Locale.ROOT));
	}

}
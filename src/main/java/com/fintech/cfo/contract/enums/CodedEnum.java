package com.fintech.cfo.contract.enums;

import java.util.Locale;
import java.util.Objects;

import com.fintech.cfo.shared.exception.ValidationException;

/**
 * Shared contract for the closed sets in this package whose variant name is also
 * the value persisted in the V5 {@code VARCHAR} columns.
 *
 * <p>V5 stores {@code contracts.status}, {@code contract_terms.term_type},
 * {@code pricing_terms.pricing_type}, {@code discount_terms.discount_type} and
 * {@code commercial_rules.rule_type} as bare strings with no lookup table. Each
 * set therefore publishes exactly one code per variant, derived from the variant
 * name, and resolves a stored value back to a variant without reflection.
 *
 * <p>Resolution fails loudly on an unrecognised code. A value this module does
 * not know means the schema and the code have diverged, and guessing would
 * price a transaction from the wrong commercial terms - the one failure mode
 * that cannot be detected downstream because the result still looks like money.
 */
public interface CodedEnum {

	/**
	 * The value written to and read from the database column. Always equal to the
	 * variant's simple name.
	 */
	String code();

	/**
	 * Normalises a stored column value for comparison.
	 *
	 * @throws ValidationException if the value is null or blank, which in a
	 * {@code NOT NULL} column means the row was written by something other than
	 * this module
	 */
	static String normalise(String code, String typeName) {
		Objects.requireNonNull(typeName, "typeName must not be null");
		if (code == null || code.isBlank()) {
			throw new ValidationException(typeName + " code must not be blank");
		}
		return code.trim().toUpperCase(Locale.ROOT);
	}

	/**
	 * Guards against a code that the V5 column cannot hold. Called from the
	 * {@code fromCode} methods so an over-long variant is refused at the boundary
	 * rather than failing as a database truncation at write time.
	 *
	 * @param maxColumnLength width of the V5 column this set is stored in
	 * @throws IllegalStateException if the variant could never be persisted
	 */
	default void requireColumnWidth(int maxColumnLength) {
		if (this.code().length() > maxColumnLength) {
			throw new IllegalStateException(
					code() + " exceeds the V5 column width of " + maxColumnLength);
		}
	}

}

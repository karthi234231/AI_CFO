package com.fintech.cfo.financialtruth.enums;

import java.util.Locale;
import java.util.Objects;

import com.fintech.cfo.shared.exception.ValidationException;

/**
 * Shared contract for the closed sets in this package whose variant is also the
 * value persisted in the V6 {@code VARCHAR} columns.
 *
 * <p>V6 stores {@code calculation_runs.status},
 * {@code calculation_results.result_type} and {@code calculation_results.status}
 * as bare strings with no lookup table, and the canonical form of a run is hashed
 * into its reproducibility checksum. Each set therefore publishes exactly one
 * stable code per variant and resolves a stored value back to a variant without
 * reflection.
 *
 * <p>Resolution fails loudly on an unrecognised code. A value this module does not
 * know means the schema and the code have diverged, and guessing would report a
 * variance whose provenance cannot be reconstructed - the one failure that cannot
 * be caught downstream, because the number still looks like money.
 *
 * <p>This is deliberately a local copy of the identical contract in
 * {@code contract.enums}: financialtruth must not depend on the contract module,
 * and the alternative of a shared constant is a dependency this pure engine has no
 * reason to carry.
 */
public interface CodedEnum {

	/**
	 * The value written to and read from the database column, and the value that
	 * enters the reproducibility checksum. Always equal to the variant's simple
	 * name, so a column written by hand and a column written by this module agree.
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
		// ROOT locale: a Turkish-locale JVM would lowercase "I" to a dotless i and make
		// every stored code unresolvable on that host alone.
		return code.trim().toUpperCase(Locale.ROOT);
	}

	/**
	 * Guards against a code the V6 column could never hold. Called from the
	 * {@code fromCode} methods so an over-long variant is refused at the boundary
	 * rather than failing later as a silent truncation at write time - a truncated
	 * status would be read back as an unknown status and fail the whole run.
	 *
	 * @param maxColumnLength width of the V6 column this set is stored in
	 * @throws IllegalStateException if the variant could never be persisted
	 */
	default void requireColumnWidth(int maxColumnLength) {
		if (this.code().length() > maxColumnLength) {
			throw new IllegalStateException(code() + " exceeds the V6 column width of " + maxColumnLength);
		}
	}

}
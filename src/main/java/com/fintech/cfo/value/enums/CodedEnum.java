package com.fintech.cfo.value.enums;

import java.util.Locale;
import java.util.Objects;

import com.fintech.cfo.shared.exception.ValidationException;

/**
 * Shared contract for the closed sets in this package whose variant is also the
 * value stored in a {@code V9__create_value_tracking.sql} column.
 *
 * <p>V9 stores {@code action_plans.status}, {@code action_executions.status},
 * {@code outcomes.status}, {@code outcomes.outcome_type},
 * {@code outcomes.measurement_method}, {@code realized_values.realization_status},
 * {@code value_attributions.attribution_method} and
 * {@code value_attributions.confidence} as bare strings with no lookup table. Each
 * set therefore publishes exactly one stable code per variant and resolves a stored
 * value back to a variant without reflection.
 *
 * <p>Resolution fails loudly on an unrecognised code. A status this module does not
 * recognise means the schema and the code have diverged, and guessing would report a
 * realized amount whose provenance cannot be reconstructed - the one failure that
 * cannot be caught downstream, because the number still looks like money.
 *
 * <p>This is deliberately a local copy of the identical contract in
 * {@code financialtruth.enums}: value must not depend on the financial-truth module,
 * and the alternative of a shared constant is a dependency this pure module has no
 * reason to carry.
 */
public interface CodedEnum {

	/**
	 * The value written to and read from the database column. Always equal to the
	 * variant's simple name, so a column written by hand and a column written by this
	 * module agree.
	 */
	String code();

	/**
	 * Normalises a stored column value for comparison.
	 *
	 * @throws ValidationException if the value is null or blank, which in a
	 * {@code NOT NULL} column means the row was written by something other than this
	 * module
	 */
	static String normalise(String code, String typeName) {
		Objects.requireNonNull(typeName, "typeName must not be null");
		if (code == null || code.isBlank()) {
			throw new ValidationException(typeName + " code must not be blank");
		}
		return code.trim().toUpperCase(Locale.ROOT);
	}

	/**
	 * Guards against a code the V9 column could never hold. Called from the
	 * {@code fromCode} methods so an over-long variant is refused at the boundary
	 * rather than failing later as a silent truncation at write time - a truncated
	 * status would be read back as an unknown status and fail the whole record.
	 *
	 * @param maxColumnLength width of the V9 column this set is stored in
	 * @throws IllegalStateException if the variant could never be persisted
	 */
	default void requireColumnWidth(int maxColumnLength) {
		if (this.code().length() > maxColumnLength) {
			throw new IllegalStateException(this.code() + " exceeds the V9 column width of " + maxColumnLength);
		}
	}

}

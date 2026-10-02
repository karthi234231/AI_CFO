package com.fintech.cfo.evidence.enums;

import java.util.Locale;
import java.util.Objects;

import com.fintech.cfo.shared.exception.ValidationException;

/**
 * Shared contract for the closed sets in this package whose variant name is also the
 * value persisted in the V7 {@code VARCHAR} columns.
 *
 * <p>V7 stores {@code evidences.evidence_type}, {@code lineage_edges.relation_type} and
 * {@code investigations.status} style columns as bare strings with no lookup table.
 * Each set therefore publishes exactly one stable code per variant and resolves a
 * stored value back to a variant without reflection.
 *
 * <p>Resolution fails loudly on an unrecognised code. A value this module does not
 * know means the schema and the code have diverged, and guessing would attach a
 * plausible-looking figure to the wrong row - the one failure that cannot be caught
 * downstream, because the number still looks like money.
 *
 * <p>This is a local copy of the identical contract in {@code contract.enums} and
 * {@code financialtruth.enums}: a business module must not depend on another business
 * module, and a shared constant package would be a dependency this pure module has no
 * reason to carry.
 */
public interface CodedEnum {

	/**
	 * The value written to and read from the database column. Always equal to the
	 * variant's simple name, so a row written by hand and a row written by this module
	 * agree.
	 */
	String code();

	/**
	 * Normalises a stored column value for comparison.
	 *
	 * <p>{@code Locale.ROOT} rather than a default-locale upper-case: a Turkish
	 * locale maps {@code 'i'} to {@code 'İ'}, so the same stored code would resolve
	 * to a different variant on a differently-configured server.
	 *
	 * @param code     value read from the {@code VARCHAR} column
	 * @param typeName set name, used to name the offending field in the message
	 * @return the trimmed, upper-cased code
	 * @throws ValidationException if the value is null or blank, which in a {@code NOT
	 *         NULL} column means the row was written by something other than this
	 *         module
	 */
	static String normalise(String code, String typeName) {
		Objects.requireNonNull(typeName, "typeName must not be null");
		// Null is rejected rather than defaulted to a variant. A code this module
		// does not recognise means the schema and the code have diverged, and
		// guessing would attach a plausible-looking figure to the wrong row.
		if (code == null || code.isBlank()) {
			throw new ValidationException(typeName + " code must not be blank");
		}
		return code.trim().toUpperCase(Locale.ROOT);
	}

	/**
	 * Guards against a code the V7 column could never hold. Called from the
	 * {@code fromCode} methods so an over-long variant is refused at the boundary
	 * rather than failing later as a silent truncation at write time - a truncated
	 * status would be read back as an unknown status and silently drop the record it
	 * described.
	 *
	 * @param maxColumnLength width of the V7 column this set is stored in
	 * @throws IllegalStateException if the variant could never be persisted
	 */
	default void requireColumnWidth(int maxColumnLength) {
		// Checked before the value ever reaches the database: a variant this module
		// declares at construction time must fit the column it is persisted into, and
		// the only alternative is a write that succeeds and a read that resolves to a
		// different variant.
		if (this.code().length() > maxColumnLength) {
			throw new IllegalStateException(this.code() + " exceeds the V7 column width of " + maxColumnLength);
		}
	}

}

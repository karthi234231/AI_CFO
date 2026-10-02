package com.fintech.cfo.opportunity.enums;

import java.util.Locale;
import java.util.Objects;

import com.fintech.cfo.shared.exception.ValidationException;

/**
 * Shared contract for the closed sets in this package whose variant is also the
 * value persisted in the V8 {@code VARCHAR} columns.
 *
 * <p>{@code V8__create_opportunities.sql} stores {@code opportunities.status},
 * {@code opportunities.validation_status}, {@code opportunities.opportunity_type}
 * and the several {@code opportunity_reviews.decision} /
 * {@code opportunity_findings.finding_type} columns as bare strings with no lookup
 * table. Each set therefore publishes exactly one stable code per variant and
 * resolves a stored value back to a variant without reflection, so the string in
 * the database and the variant in memory are the same fact expressed twice.
 *
 * <p>Resolution fails loudly on an unrecognised code. A status this module does not
 * know means the schema and the code have diverged, and guessing would report an
 * opportunity in a state nobody decided on - the one failure a downstream consumer
 * cannot catch, because the money still looks like money.
 *
 * <p>This is deliberately a local copy of the identical contract in
 * {@code contract.enums} and {@code financialtruth.enums}: module boundaries forbid
 * depending on either, and a shared constant in {@code shared} is a coupling the
 * lifecycle rules have no reason to carry.
 */
public interface CodedEnum {

	/**
	 * The value written to and read from the database column. Always equal to the
	 * variant's simple name, so a column written by hand and a column written by
	 * this module agree.
	 *
	 * @return the persisted code, stable across releases
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
		// Locale.ROOT, not the default locale: case mapping is locale-sensitive, and
		// a record read on a differently configured node must resolve to the same
		// variant it was written as everywhere else.
		return code.trim().toUpperCase(Locale.ROOT);
	}

	/**
	 * Guards against a code the V8 column could never hold. Called from the
	 * {@code fromCode} methods so an over-long variant is refused at the boundary
	 * rather than failing later as a silent truncation at write time - a truncated
	 * status would be read back as an unknown status and fail the whole record.
	 *
	 * @param maxColumnLength width of the V8 column this set is stored in
	 * @throws IllegalStateException if the variant could never be persisted
	 */
	default void requireColumnWidth(int maxColumnLength) {
		// Checked on resolution rather than on write, so an over-long code surfaces
		// at the boundary that owns the schema contract and cannot be persisted
		// silently.
		if (this.code().length() > maxColumnLength) {
			throw new IllegalStateException(code() + " exceeds the V8 column width of " + maxColumnLength);
		}
	}

}
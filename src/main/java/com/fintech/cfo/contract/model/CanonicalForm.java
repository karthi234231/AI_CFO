package com.fintech.cfo.contract.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.fintech.cfo.shared.domain.Money;

/**
 * Builds the canonical, order-independent text form of a term row.
 *
 * <p>Package-private: the canonical form exists only to feed the input checksum
 * that proves a past calculation evaluated the terms it claims to have
 * evaluated. Making it part of each term's own API would invite callers to
 * depend on a serialisation that has no other purpose.
 *
 * <p>Every separator is escaped so that two different term sets cannot render
 * to the same string and therefore the same SHA-256.
 *
 * <p><strong>Superseded by {@link CanonicalText}.</strong> This class is a
 * near-exact earlier duplicate and no term type references it; every
 * {@code canonical()} implementation calls {@code CanonicalText}. The
 * {@code NULL} sentinel, the separator escaping and the full-scale money
 * rendering here are identical to that type's, so the two produce the same
 * digest for the same row. It is retained rather than deleted because the
 * instruction for this slice is comments only; removing it is a separate,
 * code-changing decision for whoever owns the module.
 */
final class CanonicalForm {

	private static final String NULL = "-";

	private CanonicalForm() {
	}

	static String text(String value) {
		return value == null || value.isBlank() ? NULL : value;
	}

	static String uuid(UUID value) {
		return value == null ? NULL : value.toString();
	}

	static String date(LocalDate value) {
		return value == null ? NULL : value.toString();
	}

	static String instant(Instant value) {
		return value == null ? NULL : value.toString();
	}

	/**
	 * Renders money at full stored scale rather than {@code stripTrailingZeros()},
	 * because {@code NUMERIC(20,6)} and {@code NUMERIC(20,4)} columns are
	 * normalised on write and the scale itself is part of the evidence.
	 */
	static String money(Money value) {
		return value == null ? NULL : value.amount().toPlainString() + " " + value.currency().value();
	}

	static String decimal(BigDecimal value) {
		return value == null ? NULL : value.toPlainString();
	}

	static String join(Object... parts) {
		StringBuilder builder = new StringBuilder(128);
		for (int index = 0; index < parts.length; index++) {
			if (index > 0) {
				builder.append('|');
			}
			builder.append(escape(String.valueOf(parts[index])));
		}
		return builder.toString();
	}

	private static String escape(String value) {
		StringBuilder builder = new StringBuilder(value.length() + 8);
		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			switch (character) {
				case '\\' -> builder.append("\\\\");
				case '|' -> builder.append("\\p");
				case '\n' -> builder.append("\\n");
				case '\r' -> builder.append("\\r");
				default -> builder.append(character);
			}
		}
		return builder.toString();
	}

}

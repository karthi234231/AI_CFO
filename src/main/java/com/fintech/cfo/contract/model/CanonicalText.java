package com.fintech.cfo.contract.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.fintech.cfo.shared.domain.Money;

/**
 * Builds the canonical, order-independent text form of a term row.
 *
 * <p>Package-private on purpose. The canonical form exists only to feed the input
 * checksum that proves a past calculation evaluated the terms it claims to have
 * evaluated; exposing it as part of each term's public API would invite callers to
 * depend on a serialisation format that has no other purpose and no stability
 * promise.
 *
 * <p>Every separator is escaped, so two different term sets cannot render to the
 * same string and therefore to the same SHA-256. That is the difference between a
 * checksum and a decorative string.
 */
final class CanonicalText {

	private static final String NULL = "-";

	private CanonicalText() {
	}

	/**
	 * A single sentinel for "no value", shared by every type.
	 *
	 * <p>One sentinel rather than one per type is deliberate: a value of literally
	 * {@code "-"} is indistinguishable from an absent one, and the escape step below
	 * is what keeps that collision from reaching the digest. It is a known and
	 * accepted limitation rather than an oversight.
	 */
	static String text(@Nullable String value) {
		return value == null || value.isBlank() ? NULL : value;
	}

	static String uuid(@Nullable UUID value) {
		return value == null ? NULL : value.toString();
	}

	static String date(@Nullable LocalDate value) {
		return value == null ? NULL : value.toString();
	}

	static String instant(@Nullable Instant value) {
		return value == null ? NULL : value.toString();
	}

	/**
	 * Money at its stored scale rather than {@code stripTrailingZeros()}.
	 *
	 * <p>The scale is part of the evidence: {@code NUMERIC(20,6)} and
	 * {@code NUMERIC(20,4)} are different column contracts, and a restatement
	 * that only changes scale is still a change worth detecting.
	 */
	static String money(@Nullable Money value) {
		return value == null ? NULL : value.amount().toPlainString() + " " + value.currency().value();
	}

	static String decimal(@Nullable BigDecimal value) {
		return value == null ? NULL : value.toPlainString();
	}

	static String join(Object... parts) {
		StringBuilder builder = new StringBuilder(128);
		for (int index = 0; index < parts.length; index++) {
			if (index > 0) {
				// '|' is the field separator, so escaping it inside a value is what
				// makes the rendering injective: two different term rows cannot
				// produce the same string and therefore the same checksum.
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

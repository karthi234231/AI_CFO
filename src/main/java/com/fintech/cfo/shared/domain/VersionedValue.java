package com.fintech.cfo.shared.domain;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * A value together with the exact version and effective instant at which it applied.
 *
 * <p>Core requirement: a historical financial calculation must be able to identify the
 * precise rule/value version used at the time it ran, so results stay reproducible.
 *
 * <p>Record rather than a hand-written carrier because the type is pure data. The three
 * components are validated once in the compact constructor, and the compiler-generated
 * {@code equals}/{@code hashCode} compare the version numerically, which is what makes two
 * runs provably evaluated the same input.
 *
 * @param <T> underlying value type
 */
/**
 * @param value       the underlying rule or term value
 * @param version     monotonically increasing version number, 1-based
 * @param effectiveAt instant from which this version is in force
 * @throws NullPointerException     if {@code value} or {@code effectiveAt} is null
 * @throws IllegalArgumentException if {@code version} is not positive
 */
public record VersionedValue<T>(T value, long version, Instant effectiveAt) implements Serializable {

	private static final long serialVersionUID = 1L;

	// Compact constructor validates once on every construction path, so an
	// unusable version cannot be persisted by a mapper or a deserializer.
	public VersionedValue {
		Objects.requireNonNull(value, "value must not be null");
		Objects.requireNonNull(effectiveAt, "effectiveAt must not be null");
		if (version <= 0) {
			throw new IllegalArgumentException("version must be greater than 0");
		}
	}

	/**
	 * Whether {@code instant} falls in this version's window, treating the effective instant
	 * itself as in force.
	 *
	 * <p>Half-open would exclude the exact instant a version took effect, which is the one
	 * moment where a naive {@code isBefore} comparison silently falls back to the previous
	 * version and reports a plausible wrong number.
	 */
	/**
	 * @param instant the moment being tested
	 * @return true when this version was already in force at {@code instant}
	 */
	public boolean isInForceAt(Instant instant) {
		Objects.requireNonNull(instant, "instant must not be null");
		return !instant.isBefore(this.effectiveAt);
	}

	/** This value advanced to {@code nextVersion}, effective from {@code effectiveAt}. */
	/**
	 * @param nextVersion version number to move to; must exceed the current one
	 * @param effectiveAt instant from which the new version applies
	 * @return a new instance carrying the same value at the later version; this
	 *         instance is unchanged, which is what makes a superseded version
	 *         still usable for reproducing an older calculation
	 */
	public VersionedValue<T> advancedTo(long nextVersion, Instant effectiveAt) {
		return new VersionedValue<>(this.value, nextVersion, effectiveAt);
	}

	/**
	 * @return a {@code value @ version @ effectiveAt} form, so a stored result can
	 *         state on its face which rule version produced it
	 */
	@Override
	public String toString() {
		return "VersionedValue[v" + this.version + " @ " + this.effectiveAt + " = " + this.value + "]";
	}

}

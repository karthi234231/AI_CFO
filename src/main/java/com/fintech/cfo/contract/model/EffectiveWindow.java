package com.fintech.cfo.contract.model;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import com.fintech.cfo.shared.exception.ValidationException;

/**
 * The {@code effective_from} / {@code effective_to} pair shared by every V5 table
 * that takes part in as-of resolution.
 *
 * <p>{@code shared.domain.DateRange} cannot be used for this. It rejects a null
 * end date, and an open-ended term is both legal and common in V5: a price agreed
 * "from 1 April 2024, no end date" is stored as {@code effective_to IS NULL}. A
 * sentinel date would have to be invented, and any invented sentinel eventually
 * gets compared against a real date in a report.
 *
 * <p><strong>Both ends are inclusive.</strong> A term with
 * {@code effective_to = 2026-03-31} still applies to an invoice dated 2026-03-31.
 * The half-open convention common in accounting systems drops the last day of
 * every price, and a silently dropped day is far more damaging than the
 * duplicated day the inclusive convention can produce: a duplicate is visible in
 * the candidate set and is decided by the resolver's documented tie-break, while
 * a dropped day is invisible and unfixable after the fact.
 */
public record EffectiveWindow(LocalDate effectiveFrom, @Nullable LocalDate effectiveTo) implements Serializable {

	public EffectiveWindow {
		Objects.requireNonNull(effectiveFrom, "effectiveFrom must not be null");
		if (effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) {
			throw new ValidationException("effectiveTo must not be before effectiveFrom");
		}
	}

	/**
	 * A window that runs from {@code effectiveFrom} and is never closed.
	 */
	public static EffectiveWindow openFrom(LocalDate effectiveFrom) {
		return new EffectiveWindow(effectiveFrom, null);
	}

	/**
	 * The window for a nullable {@code effective_from} / {@code effective_to} pair.
	 * A null end is the open-ended case, not a sentinel.
	 */
	public static EffectiveWindow of(LocalDate effectiveFrom, @Nullable LocalDate effectiveTo) {
		return new EffectiveWindow(effectiveFrom, effectiveTo);
	}

	/**
	 * The window between two known dates, inclusive. Convenient in tests and
	 * controlled data feeds, where inventing an open end is never the intent.
	 */
	public static EffectiveWindow between(LocalDate effectiveFrom, LocalDate effectiveTo) {
		return new EffectiveWindow(effectiveFrom, effectiveTo);
	}

	/**
	 * The last date this window covers, or empty when it is open-ended.
	 */
	public Optional<LocalDate> closedOn() {
		return Optional.ofNullable(this.effectiveTo);
	}

	public boolean isOpenEnded() {
		return this.effectiveTo == null;
	}

	/**
	 * Inclusive containment on both ends. See the class javadoc for why.
	 */
	public boolean contains(LocalDate date) {
		Objects.requireNonNull(date, "date must not be null");
		// Before the start is a miss. There is no tolerance band here: a term that
		// starts tomorrow does not apply today, and a grace period would be a
		// business decision this type has no column for.
		if (date.isBefore(this.effectiveFrom)) {
			return false;
		}
		// After the end is a miss. A null end means open-ended and covers every date
		// from the start forward - the common case for a price agreed with no expiry.
		return this.effectiveTo == null || !date.isAfter(this.effectiveTo);
	}

	/**
	 * Whether this window and {@code other} share at least one business date.
	 * Used to explain an overlap rather than to resolve one.
	 */
	public boolean intersects(EffectiveWindow other) {
		Objects.requireNonNull(other, "other window must not be null");
		// Inclusive on both sides, consistent with contains(). A window that ends on
		// the day another begins does intersect - on that one day.
		if (this.effectiveTo != null && this.effectiveTo.isBefore(other.effectiveFrom)) {
			return false;
		}
		return other.effectiveTo == null || !other.effectiveTo.isBefore(this.effectiveFrom);
	}

	/**
	 * Stable text form for the input checksum.
	 *
	 * <p>Rendered here rather than delegated to {@code toString()} so that
	 * improving a debug message cannot change the checksum of a stored
	 * calculation.
	 */
	public String canonical() {
		return this.effectiveFrom + ".." + (this.effectiveTo == null ? "open" : this.effectiveTo.toString());
	}

}

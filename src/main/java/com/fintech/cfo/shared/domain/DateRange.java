package com.fintech.cfo.shared.domain;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Immutable, inclusive business-date range.
 *
 * <p>Represents business dates only (no time zone or timestamp semantics). The range is
 * inclusive at both ends, matching how accounting periods are stated, so {@code contains}
 * and {@link #days()} both count the first and last day.
 */
/**
 * @param startDate first business date in the range, inclusive
 * @param endDate   last business date in the range, inclusive
 * @throws NullPointerException     if either bound is null
 * @throws IllegalArgumentException if {@code endDate} precedes {@code startDate}
 */
public record DateRange(LocalDate startDate, LocalDate endDate) implements Serializable {

	private static final long serialVersionUID = 1L;

	// Compact constructor: this runs on every construction path including
	// record canonicalisation, so an inverted range cannot exist as an instance.
	public DateRange {
		Objects.requireNonNull(startDate, "startDate must not be null");
		Objects.requireNonNull(endDate, "endDate must not be null");
		if (endDate.isBefore(startDate)) {
			throw new IllegalArgumentException("endDate must not be before startDate");
		}
	}

	/**
	 * @param startDate first business date, inclusive
	 * @param endDate   last business date, inclusive
	 * @return a validated range
	 */
	public static DateRange of(LocalDate startDate, LocalDate endDate) {
		return new DateRange(startDate, endDate);
	}

	/**
	 * @param date the business date the range covers
	 * @return a one-day range, which {@link #days()} counts as 1 rather than 0
	 */
	public static DateRange singleDay(LocalDate date) {
		return new DateRange(date, date);
	}

	/** Inclusive count of days, so a single-day range is 1 and never 0. */
	public long days() {
		// +1 because ChronoUnit.between is exclusive of the end date while this
		// type is inclusive; without it a single-day range would measure zero days.
		return ChronoUnit.DAYS.between(this.startDate, this.endDate) + 1;
	}

	/**
	 * @param date business date to test
	 * @return true when the date lies within this range, bounds included
	 */
	public boolean contains(LocalDate date) {
		Objects.requireNonNull(date, "date must not be null");
		// Negated isBefore/isAfter rather than isAfter/isBefore, so both bounds
		// are inclusive without a separate equality special case.
		return !date.isBefore(this.startDate) && !date.isAfter(this.endDate);
	}

	/**
	 * Full containment. Testing only the endpoints is correct for a range, since
	 * ranges are contiguous.
	 *
	 * @param other candidate covering range
	 * @return true when {@code other} lies entirely within this range
	 */
	public boolean contains(DateRange other) {
		Objects.requireNonNull(other, "other range must not be null");
		return contains(other.startDate) && contains(other.endDate);
	}

	/**
	 * Touching ranges overlap: an accounting period ending the 31st and one
	 * starting the 1st of the next month share no day, but two periods that meet
	 * on the same day do.
	 *
	 * @param other candidate range
	 * @return true when the ranges share at least one day
	 */
	public boolean overlaps(DateRange other) {
		Objects.requireNonNull(other, "other range must not be null");
		return !this.endDate.isBefore(other.startDate) && !other.endDate.isBefore(this.startDate);
	}

	/**
	 * @return a compact {@code start..end} form for logs and error messages
	 */
	@Override
	public String toString() {
		return this.startDate + ".." + this.endDate;
	}

}

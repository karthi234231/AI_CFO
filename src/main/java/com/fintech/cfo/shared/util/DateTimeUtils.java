package com.fintech.cfo.shared.util;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;

import org.springframework.stereotype.Component;

/**
 * Time helpers.
 *
 * <p>Business dates are always interpreted in UTC. Tests inject a fixed
 * {@link Clock} so calculations stay reproducible.
 *
 * <p><b>Why this class exists at all.</b> Calling {@code Instant.now()} or
 * {@code LocalDate.now()} directly would tie every caller to the system clock and
 * the default zone, and both are non-deterministic. In a system that computes
 * financial variances and period comparisons, "today" is an input to the
 * calculation, not an ambient fact: the same inputs run on two machines at
 * different times must produce the same rupee amount, and a test that depends on
 * the wall clock fails intermittently and is impossible to reproduce. Routing
 * every "now" through one injectable component makes the clock a parameter, so
 * time becomes controllable from the outside.
 *
 * <p><b>Why UTC specifically.</b> Business dates are period boundaries — month
 * ends, quarter ends, financial-year ends — and those must not shift because a
 * server runs in a different zone or because a region observes daylight saving.
 * An accounting period that changed length twice a year would silently alter
 * every variance computed inside it. Fixing the interpretation to UTC removes
 * that class of bug entirely; if a business later needs a local-time fiscal
 * calendar, that belongs in a dedicated policy object rather than being
 * reintroduced implicitly through the default zone.
 *
 * <p>Registered as a {@code @Component} so the no-argument constructor is used in
 * production, while tests construct it directly with a fixed {@link Clock}. The
 * period methods themselves are static maths on a supplied {@link LocalDate} and
 * hold no hidden state, so they are reproducible regardless of the clock.
 */
@Component
public class DateTimeUtils {

	/** The single source of "now"; never read the system clock directly. */
	private final Clock clock;

	/** Production path: real time, UTC. */
	public DateTimeUtils() {
		this(Clock.systemUTC());
	}

	/** Test path: a fixed or offset clock supplied by the caller. */
	public DateTimeUtils(Clock clock) {
		this.clock = clock;
	}

	/**
	 * Exposes the underlying clock for the few components that need to derive a
	 * zone or a duration themselves, rather than taking a second constructor
	 * argument at every call site.
	 */
	public Clock clock() {
		return this.clock;
	}

	/**
	 * The current instant. Used for event timestamps — when a record was created,
	 * when an action occurred — where an exact point in time matters.
	 */
	public Instant now() {
		return this.clock.instant();
	}

	/**
	 * The current calendar date in UTC.
	 *
	 * <p>Derived from the instant and then projected into UTC, rather than calling
	 * {@code LocalDate.now()} which would use the machine's default zone. The
	 * distinction is real: at 02:00 UTC on the 1st, a host in
	 * {@code Asia/Kolkata} would report the 1st, while one in
	 * {@code America/Los_Angeles} would still report the previous day. For period
	 * selection that difference decides which quarter a record lands in.
	 */
	public LocalDate today() {
		return LocalDate.ofInstant(this.clock.instant(), ZoneOffset.UTC);
	}

	/**
	 * First day of the month containing {@code date}.
	 *
	 * <p>Uses {@code withDayOfMonth(1)}, which preserves month, year and — notably
	 * — the chronology. Cheaper and safer than reconstructing via
	 * {@code LocalDate.of}, which would reset to the ISO chronology and can throw
	 * on a non-ISO date such as a Buddhist or Japanese imperial year.
	 */
	public LocalDate startOfMonth(LocalDate date) {
		return date.withDayOfMonth(1);
	}

	/**
	 * Last day of the month containing {@code date}.
	 *
	 * <p>{@link TemporalAdjusters#lastDayOfMonth()} resolves the actual month
	 * length, so 31 January and 28/29 February are all handled by one expression.
	 * Deriving it this way rather than hard-coding "day 30" is what keeps
	 * February correct without a special case.
	 */
	public LocalDate endOfMonth(LocalDate date) {
		return date.with(TemporalAdjusters.lastDayOfMonth());
	}

	/**
	 * First day of the calendar quarter containing {@code date}.
	 *
	 * <p>The arithmetic maps a 1-based month number onto its quarter and back to
	 * the first month of that quarter: {@code (month - 1) / 3} yields 0, 1 or 2
	 * for the three months of each quarter, and multiplying by 3 and adding 1
	 * converts that back to 1, 4, 7 or 10. Subtracting one before the division is
	 * what makes the result zero-based and therefore correct for January, which
	 * would otherwise divide to a negative-and-floored value.
	 *
	 * <p>Reconstructs via {@code LocalDate.of} because the answer is a different
	 * month, not just a different day.
	 */
	public LocalDate startOfQuarter(LocalDate date) {
		int firstMonth = ((date.getMonthValue() - 1) / 3) * 3 + 1;
		return LocalDate.of(date.getYear(), firstMonth, 1);
	}

	/**
	 * Last day of the calendar quarter containing {@code date}.
	 *
	 * <p>Expressed as "the day before the same quarter one quarter later" rather
	 * than by counting 90 days: stepping forward three months lands exactly on
	 * the first day of the next quarter, so subtracting a single day gives the
	 * last day of the current one. Deriving it from
	 * {@link #startOfQuarter} means the two can never disagree, and it stays
	 * correct for quarters containing 90, 91 or 92 days.
	 */
	public LocalDate endOfQuarter(LocalDate date) {
		return startOfQuarter(date).plusMonths(3).minusDays(1);
	}

	/**
	 * First day of the financial year containing {@code date}.
	 *
	 * <p>The fiscal year runs April to March, the Indian convention, so it is not
	 * the same as the calendar year and cannot be derived from
	 * {@link #startOfQuarter}. The branch is the whole rule: from April onwards the
	 * year starts in the current calendar year; before April the fiscal year began
	 * in the previous one, hence the {@code - 1}.
	 */
	public LocalDate startOfFinancialYear(LocalDate date) {
		return date.getMonthValue() >= 4 ? LocalDate.of(date.getYear(), 4, 1) : LocalDate.of(date.getYear() - 1, 4, 1);
	}

	/**
	 * Last day of the financial year containing {@code date}.
	 *
	 * <p>31 March, derived as the day before 1 April of the following fiscal year.
	 * Expressed relative to {@link #startOfFinancialYear} so the two boundaries
	 * are guaranteed to be one day apart and cannot drift when the fiscal
	 * convention is ever changed.
	 */
	public LocalDate endOfFinancialYear(LocalDate date) {
		return startOfFinancialYear(date).plusYears(1).minusDays(1);
	}

}
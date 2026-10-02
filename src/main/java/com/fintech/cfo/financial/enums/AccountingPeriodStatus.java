package com.fintech.cfo.financial.enums;

import java.time.LocalDate;
import java.util.Locale;

import com.fintech.cfo.shared.exception.ValidationException;

/**
 * Lifecycle of an accounting period, persisted as
 * {@code accounting_periods.status VARCHAR(32) NOT NULL DEFAULT 'OPEN'} in V4.
 *
 * <p>Sealed because whether a period still accepts postings is a business gate,
 * not a display string: a locked period must reject a late transaction rather
 * than accept it and restate a signed report.
 */
public sealed interface AccountingPeriodStatus {

	/**
	 * Period is open for business: postings are accepted on any date inside the
	 * window. The V4 column default, so a period created without an explicit
	 * status starts here.
	 */
	AccountingPeriodStatus OPEN = new Open();

	/**
	 * Period has been closed for routine posting but is not yet final. New
	 * postings are refused, which is the distinction from {@link #LOCKED}: a
	 * closed period can still be reopened by an authorised correction, while a
	 * locked one cannot.
	 */
	AccountingPeriodStatus CLOSED = new Closed();

	/**
	 * Period is final. No posting is accepted on any date, inside the window or
	 * not, because the report that contains it has been signed.
	 */
	AccountingPeriodStatus LOCKED = new Locked();

	/** Value written to and read from {@code accounting_periods.status}. */
	String code();

	/** Whether new transactions may still be booked into the period. */
	boolean acceptsNewPostings();

	/**
	 * Whether a posting date falling on {@code date} may still be recorded.
	 *
	 * <p>Kept separate from {@link #acceptsNewPostings()} so that a caller
	 * asking about a specific date does not have to rebuild the period window.
	 */
	default boolean acceptsPostingOn(LocalDate date) {
		// Default is deliberately identical to acceptsNewPostings(): only a locked
		// period narrows it, and only because its report is already signed.
		return acceptsNewPostings();
	}

	/**
	 * Resolves a persisted or source-supplied status code.
	 *
	 * <p>Never defaults: an unrecognised code means the schema and this module
	 * disagree about whether a period may still accept postings, and guessing
	 * {@code OPEN} would admit a posting into a locked period.
	 *
	 * @param code persisted or source-supplied status code; trimmed and
	 *             upper-cased before matching
	 * @return the matching member
	 * @throws ValidationException if {@code code} is null, blank, or not a known
	 *                              status
	 */
	static AccountingPeriodStatus fromCode(String code) {
		// A blank code cannot be classified, and the period window cannot be
		// rebuilt without knowing whether the period is still open.
		if (code == null || code.isBlank()) {
			throw new ValidationException("accounting period status code must not be blank");
		}
		// ROOT, not the default locale: a Turkish-locale host upper-cases "i" to
		// "İ" and would silently fail to match.
		String normalized = code.trim().toUpperCase(Locale.ROOT);
		// Exhaustive switch, not a map lookup: adding a constant without a case is
		// then a compile error rather than a period quietly defaulting to OPEN.
		return switch (normalized) {
			case "OPEN" -> OPEN;
			case "CLOSED" -> CLOSED;
			case "LOCKED" -> LOCKED;
			default -> throw new ValidationException("unknown accounting period status code: " + code);
		};
	}

	record Open() implements AccountingPeriodStatus {

		@Override
		public String code() {
			return "OPEN";
		}

		@Override
		public boolean acceptsNewPostings() {
			return true;
		}
	}

	record Closed() implements AccountingPeriodStatus {

		@Override
		public String code() {
			return "CLOSED";
		}

		@Override
		public boolean acceptsNewPostings() {
			return false;
		}
	}

	record Locked() implements AccountingPeriodStatus {

		@Override
		public String code() {
			return "LOCKED";
		}

		@Override
		public boolean acceptsNewPostings() {
			return false;
		}

		/**
		 * A locked period is final: even a posting dated inside the window is
		 * refused, because the closed report that contains it is already signed.
		 */
		@Override
		public boolean acceptsPostingOn(LocalDate date) {
			return false;
		}
	}

}

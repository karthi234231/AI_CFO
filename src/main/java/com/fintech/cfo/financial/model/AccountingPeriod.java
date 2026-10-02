package com.fintech.cfo.financial.model;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.UUID;

import com.fintech.cfo.financial.enums.AccountingPeriodStatus;
import com.fintech.cfo.shared.domain.DateRange;
import com.fintech.cfo.shared.domain.OrganizationId;
import com.fintech.cfo.shared.exception.ValidationException;

/**
 * Immutable mirror of the V4 {@code accounting_periods} row.
 *
 * <p>Range validity is checked in the compact constructor rather than left to
 * {@code ck_accounting_periods_range}, so a period with {@code end_date <
 * start_date} cannot be built and then reported on. The period is the tenant's
 * reporting calendar, so it is keyed by {@code ux_accounting_periods_org_code}
 * and carries no lineage: a period is a decision made in this system, not a row
 * imported from one.
 */
public record AccountingPeriod(
		UUID id,
		OrganizationId organizationId,
		String code,
		LocalDate startDate,
		LocalDate endDate,
		AccountingPeriodStatus status,
		long version) implements Serializable {

	/** {@code accounting_periods.code VARCHAR(32)}. */
	public static final int MAX_CODE_LENGTH = 32;

	public AccountingPeriod {
		// Identity: the surrogate key the rest of the system refers to a period by.
		if (id == null) {
			throw new ValidationException("id must not be null");
		}
		// Tenancy is a constructor invariant, not a query filter: every downstream
		// repository method can therefore scope by it without re-deriving it.
		if (organizationId == null) {
			throw new ValidationException("organizationId must not be null");
		}
		// The code is the human label ("FY26-Q1") and half of the natural key
		// ux_accounting_periods_org_code, so it must be present and trimmed here or
		// the same period could be created twice under two paddings of one string.
		if (code == null || code.isBlank()) {
			throw new ValidationException("code must not be blank");
		}
		String trimmedCode = code.trim();
		// Column-width check before storage, matching accounting_periods.code
		// VARCHAR(32); a longer code would be truncated by the database and would
		// then no longer match its own resolutionKey.
		if (trimmedCode.length() > MAX_CODE_LENGTH) {
			throw new ValidationException("code exceeds V4 column width " + MAX_CODE_LENGTH);
		}
		code = trimmedCode;
		// Both bounds are required: an open-ended period cannot be closed or
		// reported on, so an unbounded period is not a state this module models.
		if (startDate == null) {
			throw new ValidationException("startDate must not be null");
		}
		if (endDate == null) {
			throw new ValidationException("endDate must not be null");
		}
		// Mirrors ck_accounting_periods_range (end_date >= start_date). Enforced
		// here so an inverted window cannot exist in memory, where contains()
		// would silently answer false for every date.
		if (endDate.isBefore(startDate)) {
			throw new ValidationException("endDate must not be before startDate");
		}
		// The status decides whether postings are accepted at all; defaulting it
		// would decide that for the caller.
		if (status == null) {
			throw new ValidationException("status must not be null");
		}
		// Optimistic-locking column, managed by the persistence pass.
		if (version < 0) {
			throw new ValidationException("version must not be negative");
		}
	}

	/** The inclusive window this period covers, as the shared value type. */
	public DateRange range() {
		// Inclusive on both ends: the end date is the last day of the period, not
		// the first day of the next one, and an exclusive reading here would
		// silently drop every transaction dated on the final day.
		return DateRange.of(this.startDate, this.endDate);
	}

	/**
	 * Whether {@code date} falls inside the period.
	 *
	 * <p>Answered purely from the window. Whether a posting on that date is
	 * still permitted is a separate question answered by
	 * {@link #acceptsPostingOn(LocalDate)}; a date can be inside a locked period
	 * and still be refused.
	 *
	 * @param date the posting date to test
	 * @return {@code true} when the date is within the inclusive window
	 */
	public boolean contains(LocalDate date) {
		return this.range().contains(date);
	}

	/**
	 * Whether a transaction dated {@code date} may still be booked.
	 *
	 * <p>The conjunction of two independent questions is the whole point: the date
	 * must be in the window, and the period must still accept postings. A locked
	 * period fails the second half even for a date inside the window, because the
	 * report that contains it has been signed.
	 *
	 * @param date the posting date to test
	 * @return {@code true} only when the date is in the window and the status
	 *         permits a posting on it
	 */
	public boolean acceptsPostingOn(LocalDate date) {
		return this.contains(date) && this.status.acceptsPostingOn(date);
	}

	/**
	 * Unique key of the period within its tenant.
	 *
	 * <p>Mirrors {@code ux_accounting_periods_org_code (organization_id, code)}.
	 * Unlike the other canonical records this is a {@code String} rather than an
	 * {@code EntityResolutionKey}: a period is a decision made in this system, so
	 * it is not deduplicated against an upstream system and has no lineage.
	 *
	 * @return the tenant-qualified period code, pipe-separated
	 */
	public String resolutionKey() {
		return this.organizationId.value() + "|" + this.code;
	}

}

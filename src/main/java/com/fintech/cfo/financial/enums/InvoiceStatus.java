package com.fintech.cfo.financial.enums;

import java.util.Locale;

import com.fintech.cfo.shared.exception.ValidationException;

/**
 * Lifecycle of a canonical invoice, persisted as {@code invoices.status
 * VARCHAR(32)} in V4.
 *
 * <p>A sealed hierarchy rather than an enum because the status set is used in
 * arithmetic decisions, not just persisted: {@link #requiresSettlement()} and
 * {@link #isTerminal()} are the predicates the service layer asks, and adding a
 * status must break those decisions loudly instead of defaulting to
 * "not settled, not terminal".
 */
public sealed interface InvoiceStatus {

	/** Drafted; not issued to the customer and not yet a receivable. */
	InvoiceStatus DRAFT = new Draft();

	/** Issued; the full amount is a receivable. */
	InvoiceStatus ISSUED = new Issued();

	/** Part paid; the unpaid remainder is still a receivable. */
	InvoiceStatus PARTIALLY_PAID = new PartiallyPaid();

	/** Settled in full. */
	InvoiceStatus PAID = new Paid();

	/** Issued and past its due date; still a receivable. */
	InvoiceStatus OVERDUE = new Overdue();

	/** Negated; the invoice was issued in error. */
	InvoiceStatus VOID = new Voided();

	/** Withdrawn before settlement; never becomes a receivable. */
	InvoiceStatus CANCELLED = new Cancelled();

	/**
	 * Value written to and read from {@code invoices.status}. Derived from the
	 * constant name so the column can never drift from the Java meaning.
	 */
	String code();

	/** Whether an amount is still expected from the customer. */
	boolean requiresSettlement();

	/** Whether the lifecycle has ended, whatever the amount. */
	boolean isTerminal();

	/**
	 * Resolves a persisted or source-supplied code.
	 *
	 * <p>Never defaults: an unrecognised code means the source and this module
	 * disagree about the lifecycle, and guessing would decide whether an
	 * invoice counts as a receivable.
	 *
	 * @param code persisted or source-supplied status code; trimmed and
	 *             upper-cased before matching
	 * @return the matching member
	 * @throws ValidationException if {@code code} is null, blank, or not a known
	 *                              status
	 */
	static InvoiceStatus fromCode(String code) {
		// A blank status cannot decide whether the invoice is a receivable, so it is
		// refused before any normalisation is attempted.
		if (code == null || code.isBlank()) {
			throw new ValidationException("invoice status code must not be blank");
		}
		// ROOT, not the default locale: a Turkish-locale host upper-cases "i" to
		// "İ" and would silently fail to match.
		String normalized = code.trim().toUpperCase(Locale.ROOT);
		// Exhaustive switch over every constant: a new status without a case is a
		// compile error, so it cannot escape the settlement and terminal decisions.
		return switch (normalized) {
			case "DRAFT" -> DRAFT;
			case "ISSUED" -> ISSUED;
			case "PARTIALLY_PAID" -> PARTIALLY_PAID;
			case "PAID" -> PAID;
			case "OVERDUE" -> OVERDUE;
			case "VOID" -> VOID;
			case "CANCELLED" -> CANCELLED;
			default -> throw new ValidationException("unknown invoice status code: " + code);
		};
	}

	record Draft() implements InvoiceStatus {

		@Override
		public String code() {
			return "DRAFT";
		}

		@Override
		public boolean requiresSettlement() {
			return false;
		}

		@Override
		public boolean isTerminal() {
			return false;
		}
	}

	record Issued() implements InvoiceStatus {

		@Override
		public String code() {
			return "ISSUED";
		}

		@Override
		public boolean requiresSettlement() {
			return true;
		}

		@Override
		public boolean isTerminal() {
			return false;
		}
	}

	record PartiallyPaid() implements InvoiceStatus {

		@Override
		public String code() {
			return "PARTIALLY_PAID";
		}

		@Override
		public boolean requiresSettlement() {
			return true;
		}

		@Override
		public boolean isTerminal() {
			return false;
		}
	}

	record Paid() implements InvoiceStatus {

		@Override
		public String code() {
			return "PAID";
		}

		@Override
		public boolean requiresSettlement() {
			return false;
		}

		@Override
		public boolean isTerminal() {
			return true;
		}
	}

	record Overdue() implements InvoiceStatus {

		@Override
		public String code() {
			return "OVERDUE";
		}

		@Override
		public boolean requiresSettlement() {
			return true;
		}

		@Override
		public boolean isTerminal() {
			return false;
		}
	}

	record Voided() implements InvoiceStatus {

		@Override
		public String code() {
			return "VOID";
		}

		@Override
		public boolean requiresSettlement() {
			return false;
		}

		@Override
		public boolean isTerminal() {
			return true;
		}
	}

	record Cancelled() implements InvoiceStatus {

		@Override
		public String code() {
			return "CANCELLED";
		}

		@Override
		public boolean requiresSettlement() {
			return false;
		}

		@Override
		public boolean isTerminal() {
			return true;
		}
	}

}

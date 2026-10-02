package com.fintech.cfo.financial.enums;

import java.util.Locale;

import com.fintech.cfo.shared.exception.ValidationException;

/**
 * Nature of a canonical financial transaction, persisted as
 * {@code financial_transactions.transaction_type VARCHAR(32)} in V4.
 *
 * <p>Sealed because cash-flow classification drives reporting and must not be
 * inferred from the sign of the amount: a refund and an adjustment can both be
 * negative, and only the type says whether cash actually moved.
 */
public sealed interface TransactionType {

	// Each constant is a singleton instance of its own record. Declared as
	// interface fields so every caller uses the same instance and the type can be
	// compared by identity. Records are used for the implementations because they
	// are implicitly final, need no constructor arguments, and generate equals,
	// hashCode and toString — so these behave as true enum constants.
	TransactionType REVENUE = new Revenue();

	TransactionType EXPENSE = new Expense();

	TransactionType RECEIPT = new Receipt();

	TransactionType PAYMENT = new Payment();

	TransactionType CREDIT_NOTE = new CreditNote();

	TransactionType DEBIT_NOTE = new DebitNote();

	TransactionType REFUND = new Refund();

	TransactionType ADJUSTMENT = new Adjustment();

	// Balances are snapshots of a position, not movements. Kept as distinct types
	// rather than inferred, so a report can separate "what changed this period"
	// from "what the position was".
	TransactionType OPENING_BALANCE = new OpeningBalance();

	TransactionType CLOSING_BALANCE = new ClosingBalance();

	/** Value written to and read from {@code transaction_type}. */
	String code();

	/**
	 * Whether this type moves cash in or out of the organisation.
	 *
	 * <p>{@code OPENING_BALANCE} and {@code CLOSING_BALANCE} are snapshots, not
	 * movements: counting them as cash flow would double-count the period they
	 * open or close.
	 */
	boolean affectsCashFlow();

	/**
	 * Whether the amount is expected to be positive as stored.
	 *
	 * <p>Only a statement of expectation for reconciliation and reporting; the
	 * stored sign is never rewritten to match it, because a source that inverted
	 * a sign is an audit finding, not a formatting detail.
	 */
	boolean expectsPositiveAmount();

	/**
	 * Resolves a stored code back to its type.
	 *
	 * <p>Reads and writes must be symmetric or persisted rows become unreadable, so
	 * the accepted forms are exactly the codes above. The {@code switch} on the
	 * normalised string — rather than a lookup in a map — means adding a constant
	 * without adding a case is a compile error, which is the safer failure here.
	 *
	 * <p>Two normalisations are applied before matching. Trimming tolerates padding
	 * from a {@code VARCHAR} that was written by a fixed-width import, and
	 * upper-casing tolerates a source system that emitted lowercase. Both are
	 * presentation differences that should not fail a load of otherwise valid data.
	 *
	 * <p>{@link Locale#ROOT} rather than the default locale is a correctness
	 * requirement, not a style choice: under a Turkish default locale
	 * {@code toUpperCase()} maps {@code "i"} to {@code "İ"} rather than {@code "I"},
	 * so a lowercase code from a Turkish-locale host would silently fail to match.
	 *
	 * <p>Both failure paths throw {@link ValidationException} rather than returning
	 * null. A blank code means the row cannot be classified at all, and an unknown
	 * code means the data is from a newer or unexpected source; in both cases the
	 * caller's correct response is to refuse the row with a reason, which requires
	 * an exception, and returning null would push that decision onto every caller.
	 *
	 * <p>An unrecognised code is never treated as an adjustment: the type decides
	 * whether a row belongs in a cash-flow total at all, so falling back to the
	 * neutral member would quietly drop it from the period.
	 *
	 * @param code persisted or source-supplied type code; trimmed and upper-cased
	 *             before matching
	 * @return the matching member
	 * @throws ValidationException if {@code code} is null, blank, or not a known
	 *                              type
	 */
	static TransactionType fromCode(String code) {
		// Guard before trim/isBlank: a null code is a programming error upstream.
		if (code == null || code.isBlank()) {
			throw new ValidationException("transaction type code must not be blank");
		}
		// Normalise presentation differences so an import still loads.
		String normalized = code.trim().toUpperCase(Locale.ROOT);
		return switch (normalized) {
			case "REVENUE" -> REVENUE;
			case "EXPENSE" -> EXPENSE;
			case "RECEIPT" -> RECEIPT;
			case "PAYMENT" -> PAYMENT;
			case "CREDIT_NOTE" -> CREDIT_NOTE;
			case "DEBIT_NOTE" -> DEBIT_NOTE;
			case "REFUND" -> REFUND;
			case "ADJUSTMENT" -> ADJUSTMENT;
			case "OPENING_BALANCE" -> OPENING_BALANCE;
			case "CLOSING_BALANCE" -> CLOSING_BALANCE;
			// Unknown code: refuse rather than default, so unclassifiable data is visible.
			default -> throw new ValidationException("unknown transaction type code: " + code);
		};
	}

	// The ten records below are intentionally repetitive. Each carries its own
	// three answers, and listing them per type is the point: a reader asking
	// "is a refund cash flow?" gets the answer by looking at the refund
	// declaration, not by evaluating a conditional elsewhere. Collapsing them
	// into one record with a table of flags would move the same data further
	// from the code that uses it, and adding a type with a different flag
	// combination would then require editing the table too.
	//
	// Each returns a literal rather than reading a field, so the flag combination
	// is fixed at compile time and the whole set is a compile-time constant.

	/** Sales revenue: a cash inflow from trading activity. */
	record Revenue() implements TransactionType {

		@Override
		public String code() {
			return "REVENUE";
		}

		@Override
		public boolean affectsCashFlow() {
			return true;
		}

		@Override
		public boolean expectsPositiveAmount() {
			return true;
		}
	}

	/** Costs of running the business: a cash outflow. */
	record Expense() implements TransactionType {

		@Override
		public String code() {
			return "EXPENSE";
		}

		@Override
		public boolean affectsCashFlow() {
			return true;
		}

		@Override
		public boolean expectsPositiveAmount() {
			return true;
		}
	}

	/** Cash actually received against an invoice. */
	record Receipt() implements TransactionType {

		@Override
		public String code() {
			return "RECEIPT";
		}

		@Override
		public boolean affectsCashFlow() {
			return true;
		}

		@Override
		public boolean expectsPositiveAmount() {
			return true;
		}
	}

	/** Cash actually paid out. */
	record Payment() implements TransactionType {

		@Override
		public String code() {
			return "PAYMENT";
		}

		@Override
		public boolean affectsCashFlow() {
			return true;
		}

		@Override
		public boolean expectsPositiveAmount() {
			return true;
		}
	}

	/**
	 * A credit note: reduces what the customer owes.
	 *
	 * <p>Cash does move, so {@code affectsCashFlow} is true. The amount is expected
	 * to be negative, i.e. not positive, because a credit note reduces revenue
	 * rather than adding to it.
	 */
	record CreditNote() implements TransactionType {

		@Override
		public String code() {
			return "CREDIT_NOTE";
		}

		@Override
		public boolean affectsCashFlow() {
			return true;
		}

		@Override
		public boolean expectsPositiveAmount() {
			return false;
		}
	}

	/**
	 * A debit note: increases what the customer owes.
	 *
	 * <p>Cash does move, so {@code affectsCashFlow} is true, but the amount is
	 * still expected to be negative: from the organisation's own point of view a
	 * debit note is a return, recorded with the opposite sign to the credit note it
	 * accompanies.
	 */
	record DebitNote() implements TransactionType {

		@Override
		public String code() {
			return "DEBIT_NOTE";
		}

		@Override
		public boolean affectsCashFlow() {
			return true;
		}

		@Override
		public boolean expectsPositiveAmount() {
			return false;
		}
	}

	/**
	 * Money returned to a customer for goods already paid for.
	 *
	 * <p>A real cash outflow, so {@code affectsCashFlow} is true, with a negative
	 * amount.
	 */
	record Refund() implements TransactionType {

		@Override
		public String code() {
			return "REFUND";
		}

		@Override
		public boolean affectsCashFlow() {
			return true;
		}

		@Override
		public boolean expectsPositiveAmount() {
			return false;
		}
	}

	/**
	 * A manual correction.
	 *
	 * <p>Not cash flow: an adjustment restates a figure that is already accounted
	 * for, and treating it as a movement would double-count it. This is the case
	 * the class Javadoc describes — an adjustment and a refund can both be
	 * negative, and only the type says whether cash actually moved.
	 */
	record Adjustment() implements TransactionType {

		@Override
		public String code() {
			return "ADJUSTMENT";
		}

		@Override
		public boolean affectsCashFlow() {
			return false;
		}

		@Override
		public boolean expectsPositiveAmount() {
			return false;
		}
	}

	/**
	 * The position at the start of a period. A snapshot, so explicitly not cash
	 * flow, and its sign is not constrained because a position may be positive,
	 * negative or zero.
	 */
	record OpeningBalance() implements TransactionType {

		@Override
		public String code() {
			return "OPENING_BALANCE";
		}

		@Override
		public boolean affectsCashFlow() {
			return false;
		}

		@Override
		public boolean expectsPositiveAmount() {
			return false;
		}
	}

	/**
	 * The position at the end of a period. A snapshot for the same reasons as
	 * {@link OpeningBalance}, and the pair together allow a report to show
	 * movement without double-counting the balances that bound it.
	 */
	record ClosingBalance() implements TransactionType {

		@Override
		public String code() {
			return "CLOSING_BALANCE";
		}

		@Override
		public boolean affectsCashFlow() {
			return false;
		}

		@Override
		public boolean expectsPositiveAmount() {
			return false;
		}
	}

}

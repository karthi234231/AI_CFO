package com.fintech.cfo.financial.enums;

import java.util.Locale;

import com.fintech.cfo.shared.exception.ValidationException;

/**
 * Outcome of comparing the totals a source system reported against the totals
 * recomputed from the invoice lines.
 *
 * <p>Sealed and exhaustive because the reconciliation result decides whether a
 * canonical total may be trusted. A new outcome must not be able to fall through
 * as "matches": every mismatch kind below carries a monetary variance, so a
 * caller can never act on an unexplained figure.
 */
public sealed interface ReconciliationStatus {

	/** Recomputed totals equal the reported totals to the cent. */
	ReconciliationStatus MATCHED = new Matched();

	/** Reported subtotal differs from the sum of the lines. */
	ReconciliationStatus SUBTOTAL_MISMATCH = new SubtotalMismatch();

	/** Reported tax differs from the sum of the line taxes. */
	ReconciliationStatus TAX_MISMATCH = new TaxMismatch();

	/** Reported total differs from the recomputed total. */
	ReconciliationStatus TOTAL_MISMATCH = new TotalMismatch();

	/**
	 * Reported amounts are not all in one currency, so no comparison is
	 * possible. Raised rather than converted.
	 */
	ReconciliationStatus CURRENCY_MISMATCH = new CurrencyMismatch();

	/** Whether the reported totals may be treated as verified. */
	boolean isBalanced();

	/**
	 * Stable wire/persistence code.
	 *
	 * <p>Derived from the constant name so the reported reconciliation outcome
	 * can never drift from the Java meaning of the set.
	 */
	String code();

	/**
	 * Resolves a code back to its member. Never defaults: an unrecognised code
	 * means a caller is about to trust a total nobody has verified.
	 *
	 * @param code persisted or source-supplied reconciliation code; trimmed and
	 *             upper-cased before matching
	 * @return the matching member
	 * @throws ValidationException if {@code code} is null, blank, or not a known
	 *                              outcome
	 */
	static ReconciliationStatus fromCode(String code) {
		// Blank means the reconciliation was never recorded; treating it as MATCHED
		// would mark an unverified total as verified.
		if (code == null || code.isBlank()) {
			throw new ValidationException("reconciliation status code must not be blank");
		}
		// ROOT, not the default locale: a Turkish-locale host upper-cases "i" to
		// "İ" and would silently fail to match.
		String normalized = code.trim().toUpperCase(Locale.ROOT);
		// Each mismatch is its own outcome rather than one generic MISMATCH, so a
		// reviewer can see which figure disagreed without recomputing it.
		return switch (normalized) {
			case "MATCHED" -> MATCHED;
			case "SUBTOTAL_MISMATCH" -> SUBTOTAL_MISMATCH;
			case "TAX_MISMATCH" -> TAX_MISMATCH;
			case "TOTAL_MISMATCH" -> TOTAL_MISMATCH;
			case "CURRENCY_MISMATCH" -> CURRENCY_MISMATCH;
			default -> throw new ValidationException("unknown reconciliation status code: " + code);
		};
	}

	record Matched() implements ReconciliationStatus {

		@Override
		public boolean isBalanced() {
			return true;
		}

		@Override
		public String code() {
			return "MATCHED";
		}
	}

	record SubtotalMismatch() implements ReconciliationStatus {

		@Override
		public boolean isBalanced() {
			return false;
		}

		@Override
		public String code() {
			return "SUBTOTAL_MISMATCH";
		}
	}

	record TaxMismatch() implements ReconciliationStatus {

		@Override
		public boolean isBalanced() {
			return false;
		}

		@Override
		public String code() {
			return "TAX_MISMATCH";
		}
	}

	record TotalMismatch() implements ReconciliationStatus {

		@Override
		public boolean isBalanced() {
			return false;
		}

		@Override
		public String code() {
			return "TOTAL_MISMATCH";
		}
	}

	record CurrencyMismatch() implements ReconciliationStatus {

		@Override
		public boolean isBalanced() {
			return false;
		}

		@Override
		public String code() {
			return "CURRENCY_MISMATCH";
		}
	}

}

package com.fintech.cfo.financial.enums;

/**
 * Number-formatting family of an upstream system.
 *
 * <p>Exists so that amount parsing is a property of the source rather than a
 * guess: only a system in the Indian family may legitimately group digits in
 * pairs after the last three (lakh/crore). The projection from
 * {@link SourceSystem} to a family is declared once in
 * {@code SourceSystemMapper} with {@code @ValueMapping}, so the mapping is
 * exhaustive and a newly added source system cannot silently inherit a
 * profile.
 */
public enum SourceSystemFamily {

	/** Tally-style lakh/crore grouping, dot decimal separator. */
	INDIAN_ERP,

	/** ISO dates, western grouping, dot decimal separator. */
	GLOBAL_ERP,

	/** Cloud accounting: ISO dates, western grouping, dot decimal separator. */
	CLOUD_ACCOUNTING,

	/**
	 * Source not declared. Western grouping is assumed, and any deviation is
	 * reported rather than silently reinterpreted.
	 */
	UNKNOWN;

	/** Digit grouping the family is expected to emit. */
	public DecimalGrouping expectedGrouping() {
		// Exhaustive switch over the closed set. Note that UNKNOWN shares the
		// western arm with the declared cloud and global families: assuming
		// western grouping is a recorded assumption, and it is what makes a
		// deviation reportable rather than invisible.
		return switch (this) {
			case INDIAN_ERP -> DecimalGrouping.INDIAN;
			case GLOBAL_ERP, CLOUD_ACCOUNTING, UNKNOWN -> DecimalGrouping.WESTERN;
		};
	}

	/**
	 * Digit grouping styles this module can validate against.
	 *
	 * <p>Both styles are always *accepted* by the amount parser; the family's
	 * expected style only decides which one is treated as expected, so that a
	 * row using the other style is reported instead of being quietly read as
	 * though the source were what it claims to be.
	 */
	public enum DecimalGrouping {

		/** Groups of three counted from the right: {@code 1,234,567.89}. */
		WESTERN,

		/** Last group of three, then pairs: {@code 12,34,567.89}. */
		INDIAN
	}

}

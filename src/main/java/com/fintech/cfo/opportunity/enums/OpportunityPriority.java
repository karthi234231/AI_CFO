package com.fintech.cfo.opportunity.enums;

/**
 * How urgently an opportunity should be worked
 * ({@code opportunities.priority VARCHAR(16)}, default {@code MEDIUM}).
 *
 * <p>Priority is a <em>triage</em> decision, not a property of the money. It is set
 * by a human or by an explicit policy rule, and it is deliberately independent of
 * {@link OpportunityConfidence}: a large well-evidenced opportunity and a large
 * poorly-evidenced one can carry the same priority, because what priority answers is
 * "what do we look at next", not "can we trust this".
 *
 * <p>Ordering is expressed by {@link #isAtLeast(OpportunityPriority)} rather than by
 * {@link Enum#ordinal()} for the same reason the financial modules avoid ordinals: a
 * declaration reorder would silently change which of two priorities wins.
 */
public enum OpportunityPriority {

	/** Escalate ahead of the ordinary review queue. */
	CRITICAL,

	/** Above the ordinary review queue. */
	HIGH,

	/** The default: normal review cadence. */
	MEDIUM,

	/** Only when nothing higher is outstanding. */
	LOW;

	/**
	 * Whether this priority is at least as urgent as {@code other}.
	 *
	 * @param other the priority being compared against
	 * @return true if this priority is at the same level or higher
	 */
	public boolean isAtLeast(OpportunityPriority other) {
		return switch (this) {
			case CRITICAL -> true;
			case HIGH -> other == CRITICAL || other == HIGH;
			case MEDIUM -> other == CRITICAL || other == HIGH || other == MEDIUM;
			case LOW -> false;
		};
	}

	/**
	 * The more urgent of two priorities, resolving {@code null} to the other side so a
	 * caller that has no view does not have to invent one.
	 *
	 * @param left  first priority, may be null
	 * @param right second priority, may be null
	 * @return the more urgent of the two, or {@code MEDIUM} when neither side has
	 *         an opinion, matching the V8 column default
	 */
	public static OpportunityPriority moreUrgent(OpportunityPriority left, OpportunityPriority right) {
		if (left == null) {
			return right == null ? MEDIUM : right;
		}
		if (right == null) {
			return left;
		}
		return left.isAtLeast(right) ? left : right;
	}

}
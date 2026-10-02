package com.fintech.cfo.opportunity.enums;

/**
 * How much attention a finding deserves
 * ({@code opportunity_findings.severity VARCHAR(16)}).
 *
 * <p>Severity is about the finding's effect on how much the record can be trusted,
 * not about the size of the money. A {@code CRITICAL} finding on a small variance can
 * matter more than a {@code LOW} finding on a large one, because it undermines the
 * basis of the number rather than merely qualifying it.
 *
 * <p>Ordering is expressed by {@link #isAtLeast(FindingSeverity)} rather than by
 * {@link Enum#ordinal()}, for the same reason the rest of this codebase avoids
 * ordinals: a declaration reorder must not be able to change which severity wins.
 */
public enum FindingSeverity {

	/** Context only; the figure is unaffected. */
	INFO,

	/** Worth noting in passing. */
	LOW,

	/** Worth reading before acting. */
	MEDIUM,

	/** The figure should be treated as provisional until the finding is cleared. */
	HIGH,

	/** The figure cannot be relied on at all until the finding is cleared. */
	CRITICAL;

	/**
	 * Whether this severity is at least as severe as {@code other}.
	 *
	 * @param other the severity being compared against
	 * @return true if this severity is at the same level or higher
	 */
	public boolean isAtLeast(FindingSeverity other) {
		return switch (this) {
			case CRITICAL -> true;
			case HIGH -> other == CRITICAL || other == HIGH;
			case MEDIUM -> other == CRITICAL || other == HIGH || other == MEDIUM;
			case LOW -> other == CRITICAL || other == HIGH || other == LOW;
			case INFO -> false;
		};
	}

	/**
	 * Whether a finding of this severity should prevent a record from being presented
	 * as settled.
	 *
	 * <p>{@code HIGH} and above. Used to gate confirmation, so a record with an
	 * unresolved {@code HIGH} finding cannot be marked validated.
	 *
	 * @return true for {@code HIGH} and {@code CRITICAL}
	 */
	public boolean blocksValidation() {
		// Stated in terms of the ordering rather than by listing the two constants,
		// so raising the bar later is a single edit here.
		return this.isAtLeast(HIGH);
	}

	/**
	 * The most severe of the supplied levels.
	 *
	 * @param severities non-empty severities to combine
	 * @return the most severe level supplied
	 * @throws IllegalArgumentException if no level is supplied, because "no findings"
	 * and "no severity" must not collapse into an implicit {@code INFO}
	 */
	public static FindingSeverity mostSevere(Iterable<FindingSeverity> severities) {
		// Seeded with the lowest severity so any real finding replaces it.
		// INFO never displaces the seed, which is correct: it is the floor, not an
		// improvement on anything.
		FindingSeverity worst = INFO;
		boolean any = false;
		for (FindingSeverity severity : severities) {
			if (severity == null) {
				continue;
			}
			any = true;
			if (severity.isAtLeast(worst)) {
				worst = severity;
			}
		}
		if (!any) {
			throw new IllegalArgumentException("at least one severity is required");
		}
		return worst;
	}

}
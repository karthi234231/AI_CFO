package com.fintech.cfo.opportunity.enums;

/**
 * How much weight a reported figure deserves
 * ({@code opportunities.confidence VARCHAR(16)}, default {@code HIGH}).
 *
 * <p>Confidence is a statement about <em>evidence completeness</em>, never about
 * whether the number is large or attractive. A perfectly reproducible figure derived
 * from an incomplete input set is still low confidence, because it does not answer
 * the question that was asked.
 *
 * <p>Ordering is expressed by {@link #isLessConfidentThan(OpportunityConfidence)}
 * rather than by {@link Enum#ordinal()}. The ordinal comparison happens to be
 * correct today only because {@code LOW} is declared last, and that is exactly the
 * kind of coincidence that survives a refactor and silently reports an optimistic
 * total.
 *
 * <p>This is a local copy of the calculation engine's confidence scale rather than a
 * shared type: the two are set independently (an opportunity can inherit the worst
 * confidence of the results it was built from, or be demoted by a reviewer), and
 * module boundaries forbid depending on the engine.
 */
public enum OpportunityConfidence {

	/** Every contributing input was present, effective and mutually consistent. */
	HIGH,

	/** Figures are sound, but part of the input set could not be evaluated. */
	MEDIUM,

	/** Material inputs were missing or contradictory. */
	LOW;

	/**
	 * Whether this level carries less evidence than {@code other}, and so must win
	 * when a total inherits the worst confidence of its rows.
	 *
	 * @param other the level being compared against
	 * @return true if this level carries less evidence, which is the direction a
	 *         combined confidence always moves in
	 */
	public boolean isLessConfidentThan(OpportunityConfidence other) {
		return switch (this) {
			case HIGH -> false;
			case MEDIUM -> other == HIGH;
			case LOW -> true;
		};
	}

	/**
	 * The least confident of the supplied levels.
	 *
	 * @param levels non-empty levels to combine
	 * @return the lowest confidence supplied, never null
	 * @throws IllegalArgumentException if no level is supplied, because an aggregate
	 * with no stated confidence would be an implicit {@code HIGH}
	 */
	public static OpportunityConfidence leastOf(Iterable<OpportunityConfidence> levels) {
		// Start from the optimistic end and move down: confidence only ever degrades
		// in an aggregate, so a single LOW among HIGH rows dominates the result.
		OpportunityConfidence worst = HIGH;
		boolean any = false;
		for (OpportunityConfidence level : levels) {
			if (level == null) {
				// A null level is a caller with no view, not a vote. Skipping keeps
				// one missing input from silently becoming a HIGH.
				continue;
			}
			any = true;
			if (level.isLessConfidentThan(worst)) {
				worst = level;
			}
		}
		if (!any) {
			throw new IllegalArgumentException("at least one confidence level is required");
		}
		return worst;
	}

}
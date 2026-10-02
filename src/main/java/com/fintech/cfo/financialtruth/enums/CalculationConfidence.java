package com.fintech.cfo.financialtruth.enums;

/**
 * How much weight a reported figure deserves
 * ({@code calculation_results.confidence VARCHAR(16)}).
 *
 * <p>Confidence is a statement about <em>evidence completeness</em>, never about
 * whether a number is positive. A perfectly reproducible figure derived from an
 * incomplete input set is still low confidence, because it does not answer the
 * question that was asked.
 *
 * <p>Ordering is expressed by {@link #isLessConfidentThan(CalculationConfidence)}
 * rather than by {@link Enum#ordinal()}. The ordinal comparison happens to be
 * correct today only because {@code LOW} is declared last, and that is exactly the
 * kind of coincidence that survives a refactor and silently reports an optimistic
 * total.
 */
public enum CalculationConfidence {

	/** All contract terms were present, effective and mutually consistent. */
	HIGH,

	/** Figures are sound, but part of the input set could not be evaluated. */
	MEDIUM,

	/** Material inputs were missing or contradictory. */
	LOW;

	/**
	 * Whether this level carries less evidence than {@code other}, and so must win
	 * when a total inherits the worst confidence of its rows.
	 *
	 * <p>An explicit relation rather than a comparison: {@link #LOW} is the minimum and
	 * is not less confident than itself, {@link #MEDIUM} loses only to {@link #HIGH}, and
	 * {@link #HIGH} never loses.
	 *
	 * @param other the level currently held as the worst seen
	 * @return true when this level should replace {@code other}
	 */
	public boolean isLessConfidentThan(CalculationConfidence other) {
		return switch (this) {
			case HIGH -> false;
			case MEDIUM -> other == HIGH;
			case LOW -> true;
		};
	}

}
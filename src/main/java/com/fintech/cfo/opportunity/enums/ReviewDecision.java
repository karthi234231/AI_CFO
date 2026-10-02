package com.fintech.cfo.opportunity.enums;

import java.util.List;

import com.fintech.cfo.shared.exception.ValidationException;

/**
 * What a reviewer concluded when they looked at the record
 * ({@code opportunity_reviews.decision VARCHAR(24)}).
 *
 * <p>The decision column is nullable but {@code rationale} is {@code NOT NULL}, so a
 * bare verdict was never intended: every decision on this record must be able to
 * explain itself, and that obligation is enforced in {@link #requiresWrittenRationale}
 * and by the review service rather than by the schema alone.
 *
 * <p>A sealed interface of records, so the review service's effect switch is a
 * compile-time exhaustiveness check. Adding a decision without deciding what it does
 * to the record stops the build.
 */
public sealed interface ReviewDecision extends CodedEnum permits ReviewDecision.Approve, ReviewDecision.Reject,
		ReviewDecision.Challenge, ReviewDecision.Defer {

	/** Width of {@code opportunity_reviews.decision} in V8. */
	int MAX_CODE_LENGTH = 24;

	/**
	 * Every variant, in a fixed order that never depends on declaration order.
	 *
	 * <p>A method rather than a constant: a static field holding nested
	 * {@code INSTANCE} references cannot initialise, because the nested classes are
	 * themselves subclasses of the interface being initialised.
	 */
	static List<ReviewDecision> all() {
		return List.of(Approve.INSTANCE, Reject.INSTANCE, Challenge.INSTANCE, Defer.INSTANCE);
	}

	/**
	 * Resolves a stored {@code decision} value.
	 *
	 * @throws ValidationException if the value is blank or unknown
	 */
	static ReviewDecision fromCode(String code) {
		String normalized = CodedEnum.normalise(code, "ReviewDecision");
		for (ReviewDecision candidate : all()) {
			if (candidate.code().equals(normalized)) {
				candidate.requireColumnWidth(MAX_CODE_LENGTH);
				return candidate;
			}
		}
		throw new ValidationException("unknown review decision code: " + code);
	}

	/**
	 * Whether the reviewer must supply prose, not just a verdict.
	 *
	 * <p>True for all four variants. It is a method rather than a constant because the
	 * schema already decided it, and the alternative - assuming it in the service -
	 * would let a future variant slip through without anyone re-examining that.
	 *
	 * @return true for every variant today, because V8 makes {@code rationale}
	 *         {@code NOT NULL} on the review row
	 */
	default boolean requiresWrittenRationale() {
		return switch (this) {
			case Approve approve -> true;
			case Reject reject -> true;
			case Challenge challenge -> true;
			case Defer defer -> true;
		};
	}

	/**
	 * Whether the decision works against the record.
	 *
	 * <p>Used to require a stronger justification for adverse outcomes: a challenge
	 * has to be answerable, which means it must say what would change the conclusion.
	 *
	 * @return true for {@link Reject} and {@link Challenge}; a defer is a request
	 *         for more evidence rather than a verdict against the record
	 */
	default boolean isAdverse() {
		// Exhaustive and typed, so a decision added later has to declare its own
		// direction rather than inheriting a default.
		return switch (this) {
			case Approve approve -> false;
			case Reject reject -> true;
			case Challenge challenge -> true;
			case Defer defer -> false;
		};
	}

	/**
	 * Whether the decision ends the record's life.
	 *
	 * <p>Only {@link Reject}. A challenge re-opens quantification, a defer changes
	 * nothing, and an approval advances it.
	 *
	 * @return true only for {@link Reject}
	 */
	default boolean closesRecord() {
		return switch (this) {
			case Approve approve -> false;
			case Reject reject -> true;
			case Challenge challenge -> false;
			case Defer defer -> false;
		};
	}

	/** The record is accepted and may move forward to a recommendation. */
	record Approve() implements ReviewDecision {

		public static final Approve INSTANCE = new Approve();

		@Override
		public String code() {
			return "APPROVE";
		}

	}

	/** The record is not a real opportunity and is closed. */
	record Reject() implements ReviewDecision {

		public static final Reject INSTANCE = new Reject();

		@Override
		public String code() {
			return "REJECT";
		}

	}

	/**
	 * The record's basis is contested and must be re-quantified.
	 *
	 * <p>Preferred over rejecting outright whenever the disagreement is about evidence
	 * or method rather than about whether the deviation exists at all, because it keeps
	 * the work product attached to the record.
	 */
	record Challenge() implements ReviewDecision {

		public static final Challenge INSTANCE = new Challenge();

		@Override
		public String code() {
			return "CHALLENGE";
		}

	}

	/** More work is needed before a decision can be made; the record does not move. */
	record Defer() implements ReviewDecision {

		public static final Defer INSTANCE = new Defer();

		@Override
		public String code() {
			return "DEFER";
		}

	}

}
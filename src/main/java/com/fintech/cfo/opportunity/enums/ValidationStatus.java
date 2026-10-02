package com.fintech.cfo.opportunity.enums;

import java.util.List;

import com.fintech.cfo.shared.exception.ValidationException;

/**
 * Whether a finance reviewer has stood behind the figure
 * ({@code opportunities.validation_status VARCHAR(32)}, default {@code PENDING}).
 *
 * <p>Deliberately separate from {@link OpportunityStatus}. Validation answers "does
 * this money exist and have we proved it?" and status answers "where is this record
 * in its life?". The two come apart in the direction that matters: a record can be
 * {@link OpportunityStatus.Rejected} with a validation status of
 * {@link Disputed}, meaning a challenge is outstanding, and can be
 * {@link OpportunityStatus.Quantified} with a {@link Confirmed} validation, meaning
 * the figure survived review and is waiting to be quantified. Collapsing the two
 * would lose the ability to ask "how much of what we detected has actually been
 * verified", which is the only honest measure of whether this product works.
 *
 * <p>A sealed interface of records, so every {@code switch} over it is a
 * compile-time exhaustiveness check.
 */
public sealed interface ValidationStatus extends CodedEnum permits ValidationStatus.Pending,
		ValidationStatus.InReview, ValidationStatus.Confirmed, ValidationStatus.Disputed,
		ValidationStatus.Rejected {

	/** Width of {@code opportunities.validation_status} in V8. */
	int MAX_CODE_LENGTH = 32;

	/**
	 * Every variant, in a fixed order that never depends on declaration order.
	 *
	 * <p>A method rather than a constant: a static field holding nested
	 * {@code INSTANCE} references cannot initialise, because the nested classes are
	 * themselves subclasses of the interface being initialised.
	 */
	static List<ValidationStatus> all() {
		return List.of(Pending.INSTANCE, InReview.INSTANCE, Confirmed.INSTANCE, Disputed.INSTANCE,
				Rejected.INSTANCE);
	}

	/**
	 * Resolves a stored {@code validation_status} value.
	 *
	 * @throws ValidationException if the value is blank or unknown
	 */
	static ValidationStatus fromCode(String code) {
		String normalized = CodedEnum.normalise(code, "ValidationStatus");
		for (ValidationStatus candidate : all()) {
			if (candidate.code().equals(normalized)) {
				candidate.requireColumnWidth(MAX_CODE_LENGTH);
				return candidate;
			}
		}
		throw new ValidationException("unknown validation status code: " + code);
	}

	/**
	 * Whether no further validation outcome is expected.
	 *
	 * <p>Terminal here is not the same as a terminal lifecycle: a record whose
	 * validation was rejected is finished being validated while its lifecycle may still
	 * move on, which is how a quantified-and-refused finding ends up
	 * {@code REJECTED}.
	 *
	 * @return true once no further reviewer outcome is expected
	 */
	default boolean isTerminal() {
		return switch (this) {
			case Pending pending -> false;
			case InReview inReview -> false;
			// A confirmation ends the validation loop; it does not end the record.
			case Confirmed confirmed -> true;
			// Deliberately not terminal: a challenge is work in progress, so the
			// record stays eligible for a later confirmation.
			case Disputed disputed -> false;
			case Rejected rejected -> true;
		};
	}

	/**
	 * Whether a human has accepted the figure.
	 *
	 * <p>Only {@link Confirmed}. This gates the moves to
	 * {@link OpportunityStatus.Validated} and {@link OpportunityStatus.Approved}: an
	 * amount nobody has verified must not become something an organisation acts on.
	 *
	 * @return true only for {@link Confirmed}
	 */
	default boolean isConfirmed() {
		// Identity against the shared singleton rather than equals(): these are
		// stateless records, so two instances of Confirmed could only ever be a
		// bug, and identity makes that bug visible instead of masking it.
		return this == Confirmed.INSTANCE;
	}

	/**
	 * Whether a challenge is outstanding and the record's standing is in question.
	 *
	 * @return true only for {@link Disputed}
	 */
	default boolean isOpenlyDisputed() {
		return this == Disputed.INSTANCE;
	}

	/** No reviewer has looked at it. The V8 default. */
	record Pending() implements ValidationStatus {

		public static final Pending INSTANCE = new Pending();

		@Override
		public String code() {
			return "PENDING";
		}

	}

	/** A reviewer has the record open. */
	record InReview() implements ValidationStatus {

		public static final InReview INSTANCE = new InReview();

		@Override
		public String code() {
			return "IN_REVIEW";
		}

	}

	/** A reviewer has independently verified the figure and its basis. */
	record Confirmed() implements ValidationStatus {

		public static final Confirmed INSTANCE = new Confirmed();

		@Override
		public String code() {
			return "CONFIRMED";
		}

	}

	/**
	 * A reviewer has challenged the record and it is back in quantification.
	 *
	 * <p>Not terminal and not negative: a disputed record is one where somebody has
	 * done the work to disagree, which is the state most worth surfacing in a review
	 * queue.
	 */
	record Disputed() implements ValidationStatus {

		public static final Disputed INSTANCE = new Disputed();

		@Override
		public String code() {
			return "DISPUTED";
		}

	}

	/** A reviewer has looked and refused the record. */
	record Rejected() implements ValidationStatus {

		public static final Rejected INSTANCE = new Rejected();

		@Override
		public String code() {
			return "REJECTED";
		}

	}

}
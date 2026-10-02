package com.fintech.cfo.ai.enums;

import java.util.List;

import com.fintech.cfo.shared.exception.ValidationException;

/**
 * The status of an AI-produced artefact after it has passed the module's
 * guardrails and structured-extraction validation.
 *
 * <p>AI output is never trusted as authoritative: it begins {@link #IN_REVIEW}
 * and only becomes {@link #CONFIRMED} when a guardrail check has passed it.
 * A result that the model refused to produce is {@link #REJECTED}; one that
 * the model produced but the guardrail fenced out of (for example an
 * explanation that invent a monetary figure) is {@link #DISPUTED}. The
 * distinction matters because {@link #DISPUTED} still carries a value an
 * auditor must review, whereas {@link #REJECTED} does not.
 *
 * <p>A sealed interface of records rather than an enum, so every site that
 * acts on a status is a {@code switch} the compiler keeps exhaustive.
 */
public sealed interface AiValidationStatus extends CodedEnum
		permits AiValidationStatus.Pending, AiValidationStatus.InReview, AiValidationStatus.Confirmed,
		AiValidationStatus.Disputed, AiValidationStatus.Rejected {

	/** Width of {@code ai_artefacts.validation_status} in V8. */
	int MAX_CODE_LENGTH = 16;

	/**
	 * Every variant, in a fixed order that never depends on declaration order.
	 *
	 * <p>A method rather than a constant: a static field holding the nested
	 * {@code INSTANCE} references cannot initialise, because the nested
	 * subclasses are themselves subclasses of the interface being initialised.
	 */
	static List<AiValidationStatus> all() {
		return List.of(Pending.INSTANCE, InReview.INSTANCE, Confirmed.INSTANCE, Disputed.INSTANCE, Rejected.INSTANCE);
	}

	/**
	 * Resolves a stored status code.
	 *
	 * @param code the stored code
	 * @return the matching variant
	 * @throws ValidationException if the code is blank or unknown
	 */
	static AiValidationStatus fromCode(String code) {
		String normalized = CodedEnum.normalise(code, "AiValidationStatus");
		for (AiValidationStatus candidate : all()) {
			if (candidate.code().equals(normalized)) {
				candidate.requireColumnWidth(MAX_CODE_LENGTH);
				return candidate;
			}
		}
		throw new ValidationException("unknown AI validation status code: " + code);
	}

	/**
	 * @return true when a human must still review the artefact before it is fit
	 *         to support a report
	 */
	default boolean isOpen() {
		return switch (this) {
			case Pending pending -> true;
			case InReview inReview -> true;
			case Confirmed confirmed -> false;
			case Disputed disputed -> false;
			case Rejected rejected -> false;
		};
	}

	/**
	 * @return true when the artefact passed every guardrail and may be shown to
	 *         a report without a human-in-the-loop
	 */
	default boolean isConfirmed() {
		return this == Confirmed.INSTANCE;
	}

	/**
	 * @return true when the model produced an artefact that the guardrails
	 *         fenced out of the report; it carries content needing a review
	 */
	default boolean isOpenlyDisputed() {
		return this == Disputed.INSTANCE;
	}

	/** Not yet seen by any guardrail. */
	record Pending() implements AiValidationStatus {

		public static final Pending INSTANCE = new Pending();

		@Override
		public String code() {
			return "PENDING";
		}

	}

	/** Produced by the model; not yet passed the guardrails. The default on output. */
	record InReview() implements AiValidationStatus {

		public static final InReview INSTANCE = new InReview();

		@Override
		public String code() {
			return "IN_REVIEW";
		}

	}

	/** Passed the guardrails and safe to surface in a report. */
	record Confirmed() implements AiValidationStatus {

		public static final Confirmed INSTANCE = new Confirmed();

		@Override
		public String code() {
			return "CONFIRMED";
		}

	}

	/**
	 * Model output was produced, but the guardrail rejected it (for example it
	 * contained a number not present in the deterministic context).
	 */
	record Disputed() implements AiValidationStatus {

		public static final Disputed INSTANCE = new Disputed();

		@Override
		public String code() {
			return "DISPUTED";
		}

	}

	/** The model refused to produce the artefact; no value is available. */
	record Rejected() implements AiValidationStatus {

		public static final Rejected INSTANCE = new Rejected();

		@Override
		public String code() {
			return "REJECTED";
		}

	}

}

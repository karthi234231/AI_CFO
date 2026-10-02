package com.fintech.cfo.ai.model;

import org.jspecify.annotations.Nullable;

import com.fintech.cfo.ai.enums.AiValidationStatus;

/**
 * A prose explanation produced by the LLM for an already-computed result.
 *
 * <p>The fence lives in the status, not the text: the {@code text} is the
 * model's words and is untrusted; {@link #validationStatus()} is set by the
 * guardrail after checking that every number in the text was also present in
 * the deterministic context it was given. A {@link AiValidationStatus#Confirmed}
 * explanation is therefore one that introduced no figures, a
 * {@link AiValidationStatus#Disputed} one introduced a figure, and a
 * {@link AiValidationStatus#Rejected} one came from a refused call.
 *
 * @param text            the explanation prose as the model produced it
 * @param validationStatus the guardrail verdict on this explanation
 * @param refusalReason   why the explanation was disputed or rejected, if any
 * @param outputTokens    tokens the model consumed producing this text
 */
public record AiExplanation(
		String text,
		AiValidationStatus validationStatus,
		@Nullable String refusalReason,
		int outputTokens) {

	public AiExplanation {
		if (text == null || text.isEmpty()) {
			throw new IllegalArgumentException("AiExplanation text must not be empty");
		}
		if (validationStatus == null) {
			throw new IllegalArgumentException("validationStatus must not be null");
		}
		if (outputTokens < 0) {
			throw new IllegalArgumentException("outputTokens must not be negative");
		}
	}

}

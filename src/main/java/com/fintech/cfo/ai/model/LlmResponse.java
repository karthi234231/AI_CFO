package com.fintech.cfo.ai.model;

import org.jspecify.annotations.Nullable;

/**
 * The raw result of a single LLM call.
 *
 * <p>A port DTO rather than a domain type: the model text is untrusted and is
 * validated by {@link com.fintech.cfo.ai.service.AiGuardrailService} and
 * {@link com.fintech.cfo.ai.extraction.StructuredExtractionValidator} before
 * anything treats it as a term or an explanation. Token counts are returned by
 * the adapter so the orchestrators can account for cost (the project bills per
 * million output tokens) and enforce output limits.
 *
 * @param text            the model's reply text
 * @param outputTokens    tokens the model consumed producing this reply
 * @param refused         whether the model refused the request
 * @param refusalReason   why the model refused, when {@code refused} is true
 */
public record LlmResponse(String text, int outputTokens, boolean refused, @Nullable String refusalReason) {

	public LlmResponse {
		if (text == null || text.isEmpty()) {
			throw new IllegalArgumentException("LlmResponse text must not be empty");
		}
		if (outputTokens < 0) {
			throw new IllegalArgumentException("outputTokens must not be negative");
		}
		// A refusal without a reason is a protocol violation, not just missing data:
		// the reason is what lets the caller distinguish "I can't see that" from
		// "I won't explain finances", and both must be auditable.
		if (refused && (refusalReason == null || refusalReason.isBlank())) {
			throw new IllegalArgumentException("refused responses must carry a refusalReason");
		}
	}

}

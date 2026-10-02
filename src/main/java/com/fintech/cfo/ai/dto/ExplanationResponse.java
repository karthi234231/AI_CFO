package com.fintech.cfo.ai.dto;

import java.util.UUID;

import com.fintech.cfo.ai.enums.AiValidationStatus;
import com.fintech.cfo.ai.model.AiExplanation;
import com.fintech.cfo.shared.validation.Preconditions;

/**
 * HTTP response body for the opportunity-explanation endpoint.
 *
 * <p>A thin envelope over the domain {@link AiExplanation}: the model carries
 * the prose and the guardrail verdict, and this DTO adds the correlation id and
 * the input checksum so a returned explanation can be traced back to the exact
 * deterministic inputs that produced it.
 *
 * @param opportunityId   the deterministic result that was explained
 * @param explanation     the model's prose and the guardrail's verdict
 * @param validationStatus whether the explanation passed the number fence
 * @param inputChecksum   SHA-256 of the deterministic context shown to the model
 */
public record ExplanationResponse(
		UUID opportunityId,
		AiExplanation explanation,
		AiValidationStatus validationStatus,
		String inputChecksum) {

	public ExplanationResponse {
		Preconditions.requireNonNull(opportunityId, "opportunityId");
		Preconditions.requireNonNull(explanation, "explanation");
		Preconditions.requireNonNull(validationStatus, "validationStatus");
		Preconditions.requireText(inputChecksum, "inputChecksum");
	}

}

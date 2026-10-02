package com.fintech.cfo.ai.dto;

import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.fintech.cfo.ai.enums.AiValidationStatus;
import com.fintech.cfo.ai.model.AiExplanation;
import com.fintech.cfo.ai.model.ExtractedCommercialTerm;
import com.fintech.cfo.shared.validation.Preconditions;

/**
 * HTTP response body for the contract-interpretation endpoint.
 *
 * <p>Exposes the terms the model extracted plus an optional prose summary, with
 * the guardrail verdict and the input checksum that make the result reproducible.
 * The summary, when present, is an {@link AiExplanation} subject to the same
 * number fence as an opportunity explanation: it may restate values from the
 * contract, but it may not invent others.
 *
 * @param contractId        the interpreted contract
 * @param terms             commercial terms extracted from the document
 * @param summary           prose summary, or null when the request did not ask for one
 * @param validationStatus  the guardrail verdict on the artefact
 * @param inputChecksum     SHA-256 of the deterministic inputs shown to the model
 * @param runId             id of the extraction run that produced these terms
 */
public record ContractInterpretationResponse(
		UUID contractId,
		List<ExtractedCommercialTerm> terms,
		@Nullable AiExplanation summary,
		AiValidationStatus validationStatus,
		String inputChecksum,
		UUID runId) {

	public ContractInterpretationResponse {
		Preconditions.requireNonNull(contractId, "contractId");
		Preconditions.requireNonNull(terms, "terms");
		Preconditions.requireNonNull(validationStatus, "validationStatus");
		Preconditions.requireNonNull(runId, "runId");
		Preconditions.requireText(inputChecksum, "inputChecksum");
		terms = List.copyOf(terms);
	}

}

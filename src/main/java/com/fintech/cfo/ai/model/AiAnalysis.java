package com.fintech.cfo.ai.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.fintech.cfo.ai.enums.AiProcessingStatus;
import com.fintech.cfo.ai.enums.AiValidationStatus;
import com.fintech.cfo.shared.domain.SourceReference;

/**
 * The aggregate artefact for one AI invocation: the terms it extracted, the
 * explanation it produced, the verdict the guardrails reached, and the
 * provenance that lets a reported finding be reproduced.
 *
 * <p>This is the type the AI layer hands to the rest of the system. It carries
 * no money: {@link #inputChecksum()} is the SHA-256 of the deterministic inputs
 * the model was shown, and {@link #source()} points at the original document, so
 * a consumer can re-run the exact same prompt and expect the same terms — but it
 * can never mistake a model-computed figure for a financial truth, because no
 * figure lives here.
 *
 * @param analysisId       unique id of this invocation
 * @param terms            commercial terms extracted from the document
	 * @param explanation      prose the model produced for this artefact, or null
	 *                         when the invocation did not ask for one (for example a
	 *                         contract-term extraction run)
 * @param processingStatus lifecycle state of the run
 * @param validationStatus guardrail verdict on the whole artefact
 * @param inputChecksum    SHA-256 of the deterministic inputs shown to the model
 * @param source           provenance of the source document
 * @param analyzedAt       when the invocation completed
 */
public record AiAnalysis(
		UUID analysisId,
		List<ExtractedCommercialTerm> terms,
		@Nullable AiExplanation explanation,
		AiProcessingStatus processingStatus,
		AiValidationStatus validationStatus,
		String inputChecksum,
		SourceReference source,
		Instant analyzedAt) {

	public AiAnalysis {
		Objects.requireNonNull(analysisId, "analysisId must not be null");
		Objects.requireNonNull(terms, "terms must not be null");
		Objects.requireNonNull(processingStatus, "processingStatus must not be null");
		Objects.requireNonNull(validationStatus, "validationStatus must not be null");
		Objects.requireNonNull(source, "source must not be null");
		Objects.requireNonNull(analyzedAt, "analyzedAt must not be null");
		if (inputChecksum == null || inputChecksum.isEmpty()) {
			throw new IllegalArgumentException("inputChecksum must not be empty");
		}
		// Defensive copy: this list is the module's output and must not alias a
		// mutable caller list.
		terms = List.copyOf(terms);
	}

}

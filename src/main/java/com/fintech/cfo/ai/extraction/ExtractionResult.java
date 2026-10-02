package com.fintech.cfo.ai.extraction;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.fintech.cfo.ai.enums.AiProcessingStatus;
import com.fintech.cfo.ai.enums.AiValidationStatus;
import com.fintech.cfo.ai.model.ExtractedCommercialTerm;
import com.fintech.cfo.shared.domain.SourceReference;
import com.fintech.cfo.shared.validation.Preconditions;

/**
 * The output of a single contract-term extraction run.
 *
 * <p>Carries the model's terms plus the metadata that makes the run auditable:
 * the run id, the lifecycle and guardrail verdicts, the output token count
 * (cost accounting) and the SHA-256 of the deterministic inputs (reproducibility
 * per §4). The original document is never stored here, only its
 * {@link SourceReference}; the model text lives transiently in the call and is
 * discarded.
 *
 * @param runId             id of this extraction run
 * @param terms             terms extracted from the document
 * @param processingStatus  lifecycle state of the run
 * @param validationStatus  guardrail verdict on the artefact
 * @param outputTokens      tokens the model consumed (cost signal)
 * @param inputChecksum     SHA-256 of the deterministic inputs shown to the model
 * @param source            provenance of the source document
 * @param refusalReason     why the run failed, if it did
 */
public record ExtractionResult(
		UUID runId,
		List<ExtractedCommercialTerm> terms,
		AiProcessingStatus processingStatus,
		AiValidationStatus validationStatus,
		int outputTokens,
		String inputChecksum,
		SourceReference source,
		@Nullable String refusalReason) {

	public ExtractionResult {
		Preconditions.requireNonNull(runId, "runId");
		Preconditions.requireNonNull(terms, "terms");
		Preconditions.requireNonNull(processingStatus, "processingStatus");
		Preconditions.requireNonNull(validationStatus, "validationStatus");
		Preconditions.requireText(inputChecksum, "inputChecksum");
		Preconditions.requireNonNull(source, "source");
		Preconditions.requireAtLeast(outputTokens, 0, "outputTokens");
		terms = List.copyOf(terms);
	}

}

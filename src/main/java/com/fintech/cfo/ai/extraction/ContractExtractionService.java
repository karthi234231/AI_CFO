package com.fintech.cfo.ai.extraction;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fintech.cfo.ai.client.LlmPort;
import com.fintech.cfo.ai.enums.AiProcessingStatus;
import com.fintech.cfo.ai.enums.AiTaskType;
import com.fintech.cfo.ai.enums.AiValidationStatus;
import com.fintech.cfo.ai.model.ExtractedCommercialTerm;
import com.fintech.cfo.ai.model.LlmMessage;
import com.fintech.cfo.ai.model.LlmResponse;
import com.fintech.cfo.shared.domain.OrganizationId;
import com.fintech.cfo.shared.domain.SourceReference;
import com.fintech.cfo.shared.exception.ValidationException;
import com.fintech.cfo.shared.util.HashUtils;
import com.fintech.cfo.shared.validation.Preconditions;

/**
 * Extracts commercial terms from contract text via an LLM, behind the
 * {@link LlmPort}.
 *
 * <p>The engine is deliberately narrow: it token-budgets the input, asks the
 * model for structured clauses at temperature {@code 0.0}, and feeds the reply
 * through {@link StructuredExtractionValidator} before anything is trusted. It
 * does not compute, summarise or reason about money — it only reads clauses —
 * which is what keeps the financial-truth engine's numbers the single source of
 * monetary truth.
 */
public final class ContractExtractionService {

	/** Characters of document text the engine will forward to a model. Acts as the input-side cost/latency guardrail. */
	private static final int MAX_INPUT_CHARS = 12_000;

	/** Cap on generated tokens. Bounds cost and, transitively, the surface a model has to hallucinate a figure into. */
	private static final int MAX_OUTPUT_TOKENS = 2048;

	private final LlmPort llmPort;
	private final StructuredExtractionValidator validator;

	public ContractExtractionService(LlmPort llmPort, StructuredExtractionValidator validator) {
		this.llmPort = llmPort;
		this.validator = validator;
	}

	/**
	 * Extracts the commercial terms from a document's already-parsed text.
	 *
	 * @param organizationId tenant scoping the artefact (also binds the checksum)
	 * @param source         provenance of the document, for traceability
	 * @param documentText   the contract text to extract clauses from
	 * @return the extraction run result, with the guardrail verdict already applied
	 */
	public ExtractionResult extract(OrganizationId organizationId, SourceReference source, String documentText) {
		Preconditions.requireNonNull(organizationId, "organizationId");
		Preconditions.requireNonNull(source, "source");
		Preconditions.requireText(documentText, "documentText");
		String trimmed = documentText.trim();

		// The checksum binds the artefact to tenant + source + text, so the same
		// inputs reproduce the same result and the artefact cannot be replayed
		// into another tenant. Computed up front so both the success and failure
		// paths carry it.
		String inputChecksum = HashUtils.sha256(
				"ai/extraction:" + organizationId.value() + ":" + source + ":" + trimmed);

		// Input token budget: a contract larger than this is not refused outright,
		// but it is not streamed model-side either; the caller should split it.
		if (trimmed.length() > MAX_INPUT_CHARS) {
			return failed(inputChecksum, source, AiProcessingStatus.SKIPPED,
					"document text (" + trimmed.length() + " chars) exceeds the extraction token budget");
		}

		// The document text is the USER message, never substituted into the system
		// template. Only the safe contract reference is rendered into the template,
		// so a contract that contains prompt-injection text (e.g. "{{system := ...}}")
		// cannot rewrite the model's instructions — that is the injection fence.
		String system = render(loadTemplate(AiTaskType.EXTRACTION.promptTemplate()),
				Map.of("CONTRACT_REFERENCE", source.sourceRecordId()));
		List<LlmMessage> messages = List.of(
				new LlmMessage(LlmMessage.Role.SYSTEM, system),
				new LlmMessage(LlmMessage.Role.USER, trimmed));

		// temperature 0.0: extraction must be reproducible, not creative (§4).
		LlmResponse response = this.llmPort.complete(messages, MAX_OUTPUT_TOKENS, 0.0);

		if (response.refused()) {
			return failed(inputChecksum, source, AiProcessingStatus.FAILED,
					response.refusalReason() == null ? "model refused" : response.refusalReason());
		}

		List<ExtractedCommercialTerm> terms = this.validator.validate(response.text());
		// Extracted terms are untrusted until a human confirms them, so the artefact
		// leaves review IN_REVIEW rather than CONFIRMED.
		return new ExtractionResult(UUID.randomUUID(), terms, AiProcessingStatus.SUCCEEDED,
				AiValidationStatus.InReview.INSTANCE, response.outputTokens(), inputChecksum, source, null);
	}

	private ExtractionResult failed(String inputChecksum, SourceReference source,
			AiProcessingStatus status, String reason) {
		return new ExtractionResult(UUID.randomUUID(), List.of(), status,
				AiValidationStatus.Rejected.INSTANCE, 0, inputChecksum, source, reason);
	}

	private String loadTemplate(String resource) {
		try (InputStream in = ContractExtractionService.class.getResourceAsStream(resource)) {
			if (in == null) {
				throw new ValidationException("prompt template not found on classpath: " + resource);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			// A missing template is a startup-time defect, not a runtime data error:
			// wrapping keeps the method signature clean for callers.
			throw new UncheckedIOException("could not read prompt template " + resource, ex);
		}
	}

	private static String render(String template, Map<String, String> params) {
		String out = template;
		for (Map.Entry<String, String> entry : params.entrySet()) {
			// Naive placeholder substitution by design: only trusted, fixed ids are
			// rendered here, so there is nothing to escape. User data is passed as a
			// separate message instead of being templated in.
			out = out.replace("{{" + entry.getKey() + "}}", entry.getValue());
		}
		return out;
	}

}

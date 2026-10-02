package com.fintech.cfo.ai.service;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fintech.cfo.ai.client.LlmPort;
import com.fintech.cfo.ai.dto.ExplainOpportunityRequest;
import com.fintech.cfo.ai.enums.AiValidationStatus;
import com.fintech.cfo.ai.model.AiExplanation;
import com.fintech.cfo.ai.model.LlmMessage;
import com.fintech.cfo.ai.model.LlmResponse;
import com.fintech.cfo.shared.domain.OrganizationId;
import com.fintech.cfo.shared.util.HashUtils;
import com.fintech.cfo.shared.validation.Preconditions;

/**
 * Produces an {@link AiExplanation}: prose that describes a deterministic
 * financial result, passed back through the guardrails so it cannot carry a
 * number it was not given.
 *
 * <p>This is where the financial-truth-over-AI rule is enforced in code, not
 * only in the prompt: the explanation is allowed to restate only the numbers
 * that appeared in its {@link ExplainOpportunityRequest#context() deterministic
 * context}, and {@link AiGuardrailService#findForeignNumbers} rejects anything
 * else. A clean explanation earns {@link AiValidationStatus#Confirmed}; one
 * that cites an unauthorised figure is {@link AiValidationStatus#Disputed} and
 * is never surfaced as financial truth.
 */
public final class AiExplanationService {

	/** Cap on generated tokens: bounds cost and the surface the model has to invent a figure into. */
	private static final int MAX_OUTPUT_TOKENS = 2048;

	private final LlmPort llmPort;
	private final AiContextService contextService;
	private final AiGuardrailService guardrails;

	public AiExplanationService(LlmPort llmPort, AiContextService contextService, AiGuardrailService guardrails) {
		this.llmPort = llmPort;
		this.contextService = contextService;
		this.guardrails = guardrails;
	}

	/**
	 * Explains the deterministic opportunity result carried by {@code request}.
	 *
	 * @param organizationId tenant scoping the artefact (binds the checksum)
	 * @param request        the opportunity id, its deterministic context, and optional focus
	 * @return the explanation together with the guardrail verdict
	 */
	public AiExplanation explain(OrganizationId organizationId, ExplainOpportunityRequest request) {
		Preconditions.requireNonNull(organizationId, "organizationId");
		Preconditions.requireNonNull(request, "request");

		List<LlmMessage> messages = this.contextService.explanationMessages(request);
		// The user message is the only place numbers live, so it is the only place
		// the explanation is permitted to read them from. Deriving the allowed set
		// from it — and only from it — is what makes the number fence sound.
		String userContent = messages.stream()
				.filter(message -> message.role() == LlmMessage.Role.USER)
				.findFirst()
				.map(LlmMessage::content)
				.orElseThrow(() -> new IllegalStateException("explanation prompt had no user message"));
		Set<String> allowedNumbers = this.guardrails.extractNumbers(userContent);
		String inputChecksum = HashUtils
				.sha256("ai/explanation:" + organizationId.value() + ":" + request.opportunityId() + ":" + userContent);

		// temperature 0.0: an explanation must be reproducible, not creative (§4).
		LlmResponse response = this.llmPort.complete(messages, MAX_OUTPUT_TOKENS, 0.0);

		if (response.refused()) {
			return new AiExplanation(response.text(), AiValidationStatus.Rejected.INSTANCE,
					response.refusalReason() == null ? "model refused" : response.refusalReason(),
					response.outputTokens());
		}

		// A refusal can hide in a normal-looking reply ("I'm not able to ..."), so
		// a refusal keyword is treated as a refusal even without the port's flag.
		if (this.guardrails.looksLikeRefusal(response.text())) {
			return new AiExplanation(response.text(), AiValidationStatus.Rejected.INSTANCE,
					"model declined to explain", response.outputTokens());
		}

		// THE FENCE: any number in the reply that was not in the deterministic
		// context is the model producing a figure, not describing one.
		Set<String> foreign = this.guardrails.findForeignNumbers(response.text(), allowedNumbers);
		if (!foreign.isEmpty()) {
			return new AiExplanation(response.text(), AiValidationStatus.Disputed.INSTANCE,
					"explanation cites numbers absent from the deterministic context: " + foreign,
					response.outputTokens());
		}

		return new AiExplanation(response.text(), AiValidationStatus.Confirmed.INSTANCE, null,
				response.outputTokens());
	}

}

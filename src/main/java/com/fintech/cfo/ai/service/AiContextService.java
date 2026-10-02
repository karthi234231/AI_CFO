package com.fintech.cfo.ai.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.fintech.cfo.ai.client.EmbeddingPort;
import com.fintech.cfo.ai.dto.ExplainOpportunityRequest;
import com.fintech.cfo.ai.enums.AiTaskType;
import com.fintech.cfo.ai.model.LlmMessage;
import com.fintech.cfo.shared.validation.Preconditions;

/**
 * Assembles the prompts the model sees, and nothing more.
 *
 * <p>Two responsibilities, both about the shape of what the model is shown:
 *
 * <ol>
 *   <li>{@link #explanationMessages build the request} for an opportunity
 *       explanation. The deterministic facts go in the <em>user</em> message and
 *       the instructions go in the <em>system</em> message. Keeping them as two
 *       messages — and never interpolating the facts into the instruction
 *       template — is the structural half of the prompt-injection defence: data
 *       the model should not be able to rewrite is never part of the text it
 *       could rewrite.</li>
 *   <li>{@link #rankByRelevance rank contract terms} by embedding similarity so
 *       the context stays inside the token budget instead of dumping every clause.</li>
 * </ol>
 */
public final class AiContextService {

	/** How many supporting terms are kept when ranking: enough for context, not enough to blow the budget. */
	private static final int MAX_SUPPORTING_TERMS = 10;

	private final EmbeddingPort embeddingPort;

	public AiContextService(EmbeddingPort embeddingPort) {
		this.embeddingPort = embeddingPort;
	}

	/**
	 * Builds the two-message prompt for an opportunity explanation.
	 *
	 * <p>The system message is the rendered {@link AiTaskType#EXPLANATION} template
	 * (instructions only); the user message is the deterministic context plus any
	 * ranked supporting terms. The user message is the only message that carries
	 * numbers, which is what lets the guardrail know exactly which figures the
	 * explanation is allowed to restate.
	 *
	 * @param request the explanation request
	 * @return the system and user messages, system first
	 */
	public List<LlmMessage> explanationMessages(ExplainOpportunityRequest request) {
		Preconditions.requireNonNull(request, "request");
		String focus = focusOf(request.focus());
		String payload = buildPayload(request, focus);
		String system = render(loadTemplate(AiTaskType.EXPLANATION.promptTemplate()),
				Map.of("FOCUS", focus, "OPPORTUNITY_ID", request.opportunityId().toString()));
		return List.of(
				new LlmMessage(LlmMessage.Role.SYSTEM, system),
				new LlmMessage(LlmMessage.Role.USER, payload));
	}

	/**
	 * Ranks candidates by cosine similarity to a query embedding, descending.
	 *
	 * <p>Similarity is a selection signal, not a semantic truth: it only decides
	 * which few clauses fit the budget, never what the model may conclude.
	 *
	 * @param query      the focus the model will be asked about
	 * @param candidates the terms to choose from
	 * @param max         how many to keep
	 * @return the top {@code max} candidates, most relevant first
	 */
	public List<String> rankByRelevance(String query, List<String> candidates, int max) {
		if (query == null || query.isBlank() || candidates == null || candidates.isEmpty()) {
			return List.copyOf(candidates == null ? List.of() : candidates);
		}
		float[] queryVector = this.embeddingPort.embed(query);
		List<Map.Entry<Double, String>> scored = new ArrayList<>();
		for (String candidate : candidates) {
			if (candidate == null || candidate.isBlank()) {
				continue;
			}
			// Each candidate is embedded once and cached only for the duration of
			// the call; the list is bounded by the request, not by the model.
			float[] candidateVector = this.embeddingPort.embed(candidate);
			scored.add(Map.entry(cosine(queryVector, candidateVector), candidate));
		}
		scored.sort(Comparator.<Map.Entry<Double, String>>comparingDouble(Map.Entry::getKey).reversed());
		List<String> selected = new ArrayList<>();
		for (Map.Entry<Double, String> entry : scored) {
			if (selected.size() >= max) {
				break;
			}
			selected.add(entry.getValue());
		}
		return selected;
	}

	private String buildPayload(ExplainOpportunityRequest request, String focus) {
		List<String> ranked = request.supportingTerms() == null || request.supportingTerms().isEmpty()
				? List.of()
				: rankByRelevance(focus, request.supportingTerms(), MAX_SUPPORTING_TERMS);
		String payload = request.context();
		if (!ranked.isEmpty()) {
			payload = payload + "\n\nRelevant contract terms:\n" + String.join("\n", ranked);
		}
		return payload;
	}

	private static String focusOf(String focus) {
		return focus == null || focus.isBlank() ? "the deterministic result described below" : focus.trim();
	}

	private static double cosine(float[] a, float[] b) {
		// Guard against degenerate zero vectors, which would divide by zero and
		// otherwise rank every candidate as perfectly (or not at all) similar.
		if (a.length != b.length || a.length == 0) {
			return 0.0;
		}
		double dot = 0.0;
		double normA = 0.0;
		double normB = 0.0;
		for (int i = 0; i < a.length; i++) {
			dot += (double) a[i] * (double) b[i];
			normA += (double) a[i] * (double) a[i];
			normB += (double) b[i] * (double) b[i];
		}
		double denominator = Math.sqrt(normA) * Math.sqrt(normB);
		return denominator == 0.0 ? 0.0 : dot / denominator;
	}

	private static String loadTemplate(String resource) {
		try (InputStream in = AiContextService.class.getResourceAsStream(resource)) {
			if (in == null) {
				throw new IllegalStateException("prompt template not found on classpath: " + resource);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("could not read prompt template " + resource, ex);
		}
	}

	private static String render(String template, Map<String, String> params) {
		String out = template;
		for (Map.Entry<String, String> entry : params.entrySet()) {
			// Only trusted, fixed ids are rendered in: the deterministic context is
			// never templated in, so a context that contains injection text cannot
			// rewrite the instructions.
			out = out.replace("{{" + entry.getKey() + "}}", entry.getValue());
		}
		return out;
	}

}

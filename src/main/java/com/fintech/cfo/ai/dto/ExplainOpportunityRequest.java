package com.fintech.cfo.ai.dto;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.fintech.cfo.shared.validation.Preconditions;

/**
 * Request body for "explain a deterministic opportunity result in prose".
 *
 * <p>The AI never sees the money engine's {@code Money} objects; it sees
 * {@link #context()}, a text the caller (the deterministic side) assembled from
 * the already-computed result. That is the fence: the model can only restate or
 * rephrase what it was handed, and the guardrail verifies that.
 *
 * @param opportunityId     the deterministic result this explains
 * @param context           the deterministic facts, as text, including any
 *                          numbers the explanation is allowed to cite
 * @param focus             optional aspect to concentrate on, or null to explain the whole context
 * @param supportingTerms   optional candidate contract-term phrases to rank by
 *                          relevance against the context, or null
 */
public record ExplainOpportunityRequest(
		UUID opportunityId,
		String context,
		@Nullable String focus,
		@Nullable List<String> supportingTerms) {

	public ExplainOpportunityRequest {
		Preconditions.requireNonNull(opportunityId, "opportunityId");
		// context is everything the model is allowed to know; it must exist and be
		// non-blank, otherwise the model has nothing deterministic to explain.
		Preconditions.requireText(context, "context");
		if (supportingTerms != null) {
			// Defensive copy: a mutable list handed in by the caller would otherwise
			// change what the model saw after the request was accepted.
			supportingTerms = List.copyOf(supportingTerms);
		}
	}

}

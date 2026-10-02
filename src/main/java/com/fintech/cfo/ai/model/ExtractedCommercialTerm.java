package com.fintech.cfo.ai.model;

import com.fintech.cfo.ai.enums.AiValidationStatus;

/**
 * A single commercial term surfaced from a contract document by the extraction
 * flow.
 *
 * <p>This is the model's word, not a financial engine's: the term carries the
 * clause type and its text, but never a money amount. The financial engine owns
 * money; this module only says "the contract said net 30 / 5% / cap at 1000" in
 * the text it was trusted to read.
 *
 * @param termType        the term category, as a stable code (never an amount)
 * @param description     the clause text exactly as the model reported it
 * @param pageNumber       1-based page the text appeared on, for traceability
 * @param validationStatus always {@link AiValidationStatus#InReview} on first
 *                         production, because an LLM-extracted term is
 *                         unconfirmed until a human or downstream check passes it
 */
public record ExtractedCommercialTerm(
		String termType,
		String description,
		int pageNumber,
		AiValidationStatus validationStatus) {

	public ExtractedCommercialTerm {
		if (termType == null || termType.isBlank()) {
			throw new IllegalArgumentException("termType must not be blank");
		}
		if (description == null || description.isEmpty()) {
			throw new IllegalArgumentException("description must not be empty");
		}
		if (pageNumber < 1) {
			throw new IllegalArgumentException("pageNumber must be at least 1");
		}
		if (validationStatus == null) {
			throw new IllegalArgumentException("validationStatus must not be null");
		}
		// The default on output is IN_REVIEW: the model is trusted to read, not to
		// decide, so an extracted term only leaves review once validated.
	}

}

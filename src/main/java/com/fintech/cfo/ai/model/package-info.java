/**
 * Immutable carries that cross the AI module's boundaries: the port DTOs that
 * the LLM and document-extraction ports exchange ({@link LlmMessage},
 * {@link LlmResponse}, {@link ExtractedDocument}), and the domain records that
 * describe what the AI extracted or explained ({@link ExtractedCommercialTerm},
 * {@link AiExplanation}, {@link AiAnalysis}).
 *
 * <p>None of these carry money. An AI artefact may reference a financial result
 * by its {@link com.fintech.cfo.shared.domain.SourceReference} and its input
 * checksum, and it may quote the numbers it was given as context, but it never
 * holds a {@link com.fintech.cfo.shared.domain.Money} value — that is the
 * type-level fence behind which AI output can never be mistaken for financial
 * truth.
 */
@NullMarked
package com.fintech.cfo.ai.model;

import org.jspecify.annotations.NullMarked;

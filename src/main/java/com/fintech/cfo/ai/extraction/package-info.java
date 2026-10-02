/**
 * LLM extraction of commercial terms from contract documents.
 *
 * <p>This package turns document text into structured {@link
 * com.fintech.cfo.ai.model.ExtractedCommercialTerm}s. It is deliberate that the
 * extraction engine lives here and the HTTP transport of the model does not:
 * every guardrail, schema check and token accounting decision is unit-testable
 * against the {@link com.fintech.cfo.ai.client.LlmPort} interface without a
 * network.
 */
@NullMarked
package com.fintech.cfo.ai.extraction;

import org.jspecify.annotations.NullMarked;

/**
 * Outbound ports of the AI module.
 *
 * <p>These are the narrow interfaces the orchestrators depend on, not the HTTP
 * clients themselves. The concrete {@link com.fintech.cfo.ai.client.LlmClient},
 * {@link com.fintech.cfo.ai.client.EmbeddingClient} and
 * {@link com.fintech.cfo.ai.client.DocumentExtractionClient} are HTTP adapters
 * and are intentionally left as placeholders: per the module rules an HTTP/AI
 * client is written only in the transport pass, behind these ports. Depending
 * on an interface — never on an HTTP client — is what lets every guardrail,
 * validation and cost-accounting rule be unit-tested with no network.
 */
@NullMarked
package com.fintech.cfo.ai.client;

import org.jspecify.annotations.NullMarked;

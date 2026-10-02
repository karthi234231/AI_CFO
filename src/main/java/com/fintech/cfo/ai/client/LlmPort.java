package com.fintech.cfo.ai.client;

import java.util.List;

import com.fintech.cfo.ai.model.LlmMessage;
import com.fintech.cfo.ai.model.LlmResponse;

/**
 * Port behind which the LLM HTTP client sits.
 *
 * <p>The orchestrators call this, never the concrete {@link LlmClient}, so a
 * request for "summarise this" or "compute this" can never reach a transport
 * adapter by accident: the only verbs exposed here are the two this module is
 * licensed to make of a model — {@link #complete complete a prompt} and nothing
 * else.
 *
 * <p>The adapter that implements this is responsible for the transport-level
 * policy this port does not express: a bounded retry with exponential backoff
 * for transient failures, a per-call timeout, and the streaming/chunking of a
 * long reply. What the port DOES own that matters to correctness is
 * {@code maxOutputTokens}, which caps how much the model can write back and so
 * caps both cost and the surface area for a hallucination; and the temperature
 * the caller passes, which is {@code 0.0} for extraction/explanation because
 * those tasks must be reproducible, not creative.
 *
 * @param messages          the conversation, system message first
 * @param maxOutputTokens   hard cap on generated tokens
 * @param temperature       sampling temperature; the orchestrators pass 0.0
 * @return the model's raw, unvalidated reply
 */
public interface LlmPort {

	LlmResponse complete(List<LlmMessage> messages, int maxOutputTokens, double temperature);

}

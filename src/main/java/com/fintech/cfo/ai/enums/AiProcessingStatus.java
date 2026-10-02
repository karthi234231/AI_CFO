package com.fintech.cfo.ai.enums;

/**
 * Lifecycle of an AI invocation against a single document
 * ({@code ai_runs.processing_status}).
 *
 * <p>A plain enum rather than a sealed interface: the set never carries
 * per-variant behaviour, so the exhaustiveness guarantee of a sealed interface
 * would buy nothing here. It mirrors
 * {@code com.fintech.cfo.shared.enums.ProcessingStatus} by intent, so a run is
 * described by the same four stable states everywhere in the system.
 */
public enum AiProcessingStatus {

	/** Accepted but not started. */
	PENDING,

	/** An LLM request is in flight. */
	RUNNING,

	/** Finished; the AI output was produced and validated. */
	SUCCEEDED,

	/**
	 * Aborted: the run did not produce a usable result, either because the
	 * guardrails rejected it or the LLM refused. The reason is recorded on the
	 * owning {@code ExtractionResult}/{@code AiAnalysis}, never on this enum,
	 * because a reason is free text and does not belong on a closed code set.
	 */
	FAILED,

	/** Cancelled before it could run. */
	CANCELLED,

	/** Not executed: a guardrail or budget short-circuited the run. */
	SKIPPED

}

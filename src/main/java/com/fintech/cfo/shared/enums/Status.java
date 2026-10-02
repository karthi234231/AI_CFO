package com.fintech.cfo.shared.enums;

/**
 * Lifecycle status shared across long-running business records.
 *
 * <p>The Phase 0 opportunity/value lifecycle is:
 * {@code DETECTED -> EVIDENCED -> QUANTIFIED -> VALIDATED -> ACTED ->
 * MEASURED -> REALIZED}. Modules that need finer-grained states define their
 * own enums and map from this one.
 */
public enum Status {

	/** Being assembled and not yet submitted; still freely editable. */
	DRAFT,

	/** Submitted and awaiting review or execution. */
	PENDING,

	/** Being worked on right now. */
	IN_PROGRESS,

	/** Finished successfully with a durable result. */
	COMPLETED,

	/** Stopped on an error rather than a decision. */
	FAILED,

	/** Abandoned before completion, by request. */
	CANCELLED

}
package com.fintech.cfo.shared.enums;

/**
 * Status of an asynchronous processing unit (ingestion, calculation, report).
 *
 * <p>Distinguishes {@code SKIPPED} from {@code FAILED} because a batch that was
 * never eligible to run is not an error and must not be alerted on as one.
 */
public enum ProcessingStatus {

	/** Accepted and queued, not yet started. */
	PENDING,

	/** Claimed by a worker and executing. */
	RUNNING,

	/** Completed with its full result persisted. */
	SUCCEEDED,

	/** Attempted and stopped on an error; the reason is recorded separately. */
	FAILED,

	/** Stopped by an explicit cancellation before completing. */
	CANCELLED,

	/** Deliberately not run: the input was already processed or was ineligible. */
	SKIPPED

}
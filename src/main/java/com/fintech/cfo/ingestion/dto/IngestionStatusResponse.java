package com.fintech.cfo.ingestion.dto;

import java.util.Objects;
import java.util.UUID;

import com.fintech.cfo.ingestion.enums.IngestionStage;
import com.fintech.cfo.ingestion.enums.IngestionStatus;
import com.fintech.cfo.ingestion.model.IngestionRun;

/**
 * A snapshot of one ingestion run for a status polling client.
 *
 * <p>Carries {@code version} so a client polling a long run can tell a newer state
 * from a stale one; it is the same counter V3 declares for optimistic locking.
 *
 * <p>No row values. A status endpoint that returned accepted rows would re-serve
 * financial data on a path that is polled, cached and logged.
 */
public record IngestionStatusResponse(UUID ingestionRunId, UUID sourceFileId, IngestionStatus status,
		IngestionStage stage, long totalRows, long acceptedRows, long rejectedRows, String failureReason, long version) {

	public IngestionStatusResponse {
		Objects.requireNonNull(ingestionRunId, "ingestionRunId must not be null");
		Objects.requireNonNull(sourceFileId, "sourceFileId must not be null");
		status = status == null ? IngestionStatus.PENDING : status;
		stage = stage == null ? IngestionStage.UPLOAD : stage;
		failureReason = failureReason == null ? "" : failureReason;
		if (totalRows < 0 || acceptedRows < 0 || rejectedRows < 0) {
			throw new IllegalArgumentException("row counts must not be negative");
		}
	}

	public static IngestionStatusResponse from(IngestionRun run) {
		Objects.requireNonNull(run, "run must not be null");
		return new IngestionStatusResponse(run.id(), run.sourceFileId(), run.status(), run.stage(), run.totalRows(),
				run.acceptedRows(), run.rejectedRows(), run.failureReason(), run.version());
	}

	/** @return true when the run has reached a state it will not leave */
	public boolean isTerminal() {
		return this.status.isTerminal();
	}

	/** @return the proportion refused, 0 when there were no rows at all */
	public double rejectionRate() {
		if (this.totalRows == 0) {
			return 0.0;
		}
		return (double) this.rejectedRows / this.totalRows;
	}

}
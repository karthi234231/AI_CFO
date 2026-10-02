package com.fintech.cfo.ingestion.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.fintech.cfo.ingestion.enums.IngestionStage;
import com.fintech.cfo.ingestion.enums.IngestionStatus;
import com.fintech.cfo.shared.domain.OrganizationId;

/**
 * Immutable value mirroring one {@code ingestion_runs} row of
 * {@code V3__create_ingestion.sql}.
 *
 * <p>Not a JPA entity; see {@link SourceFile}. The {@code version} field is
 * carried because V3 declares it {@code NOT NULL DEFAULT 0} for optimistic
 * locking — translating a lock failure into {@code ConflictException} is the
 * repository milestone's job, but the counter belongs to the value so it cannot
 * be quietly left out of the mapping.
 */
public record IngestionRun(UUID id, OrganizationId organizationId, UUID sourceFileId, IngestionStatus status,
		IngestionStage stage, String sourceSystem, long totalRows, long acceptedRows, long rejectedRows,
		Long jobInstanceId, Instant startedAt, Instant completedAt, String failureReason, Instant createdAt,
		Instant updatedAt, long version) {

	public static final int MAX_FAILURE_REASON_LENGTH = 2000;

	public IngestionRun {
		Objects.requireNonNull(id, "id must not be null");
		Objects.requireNonNull(organizationId, "organizationId must not be null (organization_id is the tenant boundary)");
		Objects.requireNonNull(sourceFileId, "sourceFileId must not be null");
		Objects.requireNonNull(status, "status must not be null");
		Objects.requireNonNull(stage, "stage must not be null");
		if (totalRows < 0 || acceptedRows < 0 || rejectedRows < 0) {
			throw new IllegalArgumentException("row counts must not be negative");
		}
		if (acceptedRows + rejectedRows > totalRows) {
			throw new IllegalArgumentException(
					"accepted (" + acceptedRows + ") + rejected (" + rejectedRows + ") exceeds total (" + totalRows + ")");
		}
		sourceSystem = sourceSystem == null ? "" : sourceSystem;
		failureReason = failureReason == null ? "" : failureReason;
		if (failureReason.length() > MAX_FAILURE_REASON_LENGTH) {
			failureReason = failureReason.substring(0, MAX_FAILURE_REASON_LENGTH);
		}
		createdAt = createdAt == null ? Instant.EPOCH : createdAt;
		updatedAt = updatedAt == null ? createdAt : updatedAt;
	}

	public static IngestionRun pending(UUID id, OrganizationId organizationId, UUID sourceFileId, Instant createdAt) {
		return new IngestionRun(id, organizationId, sourceFileId, IngestionStatus.PENDING, IngestionStage.UPLOAD, "",
				0L, 0L, 0L, null, null, null, "", createdAt, createdAt, 0L);
	}

	/**
	 * Advances to a running stage. Timestamps are passed in rather than read from
	 * the clock so a re-run months later reproduces the same run record (rule 3).
	 */
	public IngestionRun started(IngestionStage newStage, Instant at) {
		Objects.requireNonNull(at, "at must not be null (inject the clock)");
		ensureNotTerminal("started");
		return new IngestionRun(this.id, this.organizationId, this.sourceFileId, IngestionStatus.RUNNING, newStage,
				this.sourceSystem, this.totalRows, this.acceptedRows, this.rejectedRows, this.jobInstanceId, at, null, "",
				this.createdAt, at, this.version + 1);
	}

	/**
	 * Closes the run. A run that refused at least one row becomes
	 * {@link IngestionStatus#COMPLETED_WITH_REJECTIONS} rather than
	 * {@link IngestionStatus#COMPLETED}: a completion status that hides rejections
	 * is the exact thing an auditor would catch.
	 */
	public IngestionRun completed(long newTotalRows, long newAcceptedRows, long newRejectedRows, Instant at) {
		Objects.requireNonNull(at, "at must not be null (inject the clock)");
		ensureNotTerminal("completed");
		IngestionStatus finalStatus = newRejectedRows > 0 ? IngestionStatus.COMPLETED_WITH_REJECTIONS
				: IngestionStatus.COMPLETED;
		return new IngestionRun(this.id, this.organizationId, this.sourceFileId, finalStatus, IngestionStage.COMPLETED,
				this.sourceSystem, newTotalRows, newAcceptedRows, newRejectedRows, this.jobInstanceId, this.startedAt, at,
				"", this.createdAt, at, this.version + 1);
	}

	public IngestionRun failed(IngestionStage failedAt, String reason, Instant at) {
		Objects.requireNonNull(at, "at must not be null (inject the clock)");
		ensureNotTerminal("failed");
		return new IngestionRun(this.id, this.organizationId, this.sourceFileId, IngestionStatus.FAILED,
				IngestionStage.FAILED, this.sourceSystem, this.totalRows, this.acceptedRows, this.rejectedRows,
				this.jobInstanceId, this.startedAt == null ? at : this.startedAt, at, reason, this.createdAt, at,
				this.version + 1);
	}

	private void ensureNotTerminal(String operation) {
		if (this.status.isTerminal()) {
			throw new IllegalStateException("cannot " + operation + " a run that is already " + this.status);
		}
	}

}
package com.fintech.cfo.ingestion.service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.fintech.cfo.ingestion.enums.IngestionStage;
import com.fintech.cfo.ingestion.enums.IngestionStatus;
import com.fintech.cfo.ingestion.model.IngestionError;
import com.fintech.cfo.ingestion.model.IngestionProcessingResult;
import com.fintech.cfo.ingestion.model.IngestionRequest;
import com.fintech.cfo.ingestion.model.IngestionRun;
import com.fintech.cfo.ingestion.model.ValidationFinding;
import com.fintech.cfo.shared.domain.OrganizationId;

/**
 * Projects a finished in-memory run onto the records V3 will eventually store.
 *
 * <p>Separated from {@link IngestionService} so the run and error rows are derived
 * from the processing result by one piece of code rather than by each caller. Two
 * callers deriving a run row slightly differently is how a persisted run stops
 * matching the run that was actually executed.
 *
 * <p>Nothing here writes. It returns the values an {@code IngestionRepository}
 * will later insert; the milestone that owns persistence owns the insert, the
 * version check and the transaction.
 *
 * <p>Timestamps come from the request, not the clock, so the projection of the same
 * run twice produces the same row (rule 3). Error identifiers are derived from the
 * run id and the finding's position rather than randomly generated: a replay must
 * not invent new primary keys for the same findings (rule 3 again, and it is what
 * makes an idempotent re-ingestion possible later).
 */
public final class IngestionStatusService {

	/**
	 * @return the {@code ingestion_runs} row for a completed run, or {@code null}
	 * when the request never produced a usable identity
	 */
	public IngestionRun toRun(IngestionRequest request, IngestionProcessingResult result) {
		Objects.requireNonNull(request, "request must not be null");
		Objects.requireNonNull(result, "result must not be null");

		UUID runId = result.ingestionRunId().orElse(request.ingestionRunId());
		OrganizationId organizationId = result.organizationId().orElse(request.organizationId());
		UUID sourceFileId = result.sourceFileId().orElse(null);
		if (sourceFileId == null) {
			return null;
		}

		Instant at = request.uploadedAt();
		String sourceSystem = request.sourceSystem();
		boolean refused = !result.isSuccess();

		return new IngestionRun(runId, organizationId, sourceFileId, result.status(), stageFor(result),
				sourceSystem, result.totalRowCount(), result.acceptedRowCount(), result.rejectedRowCount(), null, at, at,
				result.failureReason(), at, at, 0L);
	}

	/**
	 * @return the {@code ingestion_errors} rows for every finding, in the order the
	 * findings were produced
	 */
	public List<IngestionError> toErrors(IngestionRequest request, IngestionProcessingResult result) {
		Objects.requireNonNull(request, "request must not be null");
		Objects.requireNonNull(result, "result must not be null");

		UUID runId = result.ingestionRunId().orElse(request.ingestionRunId());
		OrganizationId organizationId = result.organizationId().orElse(request.organizationId());
		Instant at = request.uploadedAt();

		return result.findings()
			.stream()
			.map(finding -> toError(organizationId, runId, finding, at, positionOf(finding, result)))
			.toList();
	}

	/**
	 * The stage recorded on the run.
	 *
	 * <p>A successful or partly successful run reports the last real stage it
	 * reached; a refused one reports {@link IngestionStage#FAILED}, which is the
	 * only terminal value V3 offers for a run that did not complete.
	 */
	private static IngestionStage stageFor(IngestionProcessingResult result) {
		if (result.isSuccess()) {
			return result.finalStage().isTerminal() ? result.finalStage() : IngestionStage.COMPLETED;
		}
		return IngestionStage.FAILED;
	}

	private static IngestionError toError(OrganizationId organizationId, UUID runId, ValidationFinding finding,
			Instant at, int position) {
		return IngestionError.from(errorIdFor(runId, position), organizationId, runId, finding, at);
	}

	/**
	 * Deterministic, position-derived identifier. Not a random UUID: re-projecting the
	 * same run must yield the same {@code ingestion_errors.id} values, otherwise the
	 * replay is not the replay (rule 3).
	 */
	private static UUID errorIdFor(UUID runId, int position) {
		return UUID.nameUUIDFromBytes((runId + ":" + position).getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	private static int positionOf(ValidationFinding finding, IngestionProcessingResult result) {
		return result.findings().indexOf(finding);
	}

	/** @return whether a status means the run produced usable data */
	public static boolean isTerminalSuccess(IngestionStatus status) {
		return status == IngestionStatus.COMPLETED || status == IngestionStatus.COMPLETED_WITH_REJECTIONS;
	}

}
package com.fintech.cfo.ingestion.dto;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.fintech.cfo.ingestion.enums.IngestionStage;
import com.fintech.cfo.ingestion.enums.IngestionStatus;
import com.fintech.cfo.ingestion.enums.ParseStatus;
import com.fintech.cfo.ingestion.model.IngestionProcessingResult;
import com.fintech.cfo.ingestion.model.RejectedRow;
import com.fintech.cfo.ingestion.model.RowCoordinate;
import com.fintech.cfo.ingestion.model.ValidationFinding;

/**
 * What an ingestion run did, for a client that asked to start it.
 *
 * <p>A projection of {@link IngestionProcessingResult}, not a second source of
 * truth: every field is read off the model, so the response and the persisted run
 * cannot describe different outcomes.
 *
 * <p>Rejected rows are reported individually with their coordinates and reasons
 * rather than as a count, because "3 rows were rejected" does not let anyone fix
 * the export. Only the accepted rows' values are included; no uploaded content
 * other than the offending cell travels back (rule 5).
 *
 * <p>Records, immutable, no framework annotations: this module does not own the
 * web boundary, and keeping the transport concerns out of it is what lets the
 * shape be unit-tested.
 */
public record IngestionResponse(UUID ingestionRunId, UUID sourceFileId, IngestionStatus status,
		IngestionStage finalStage, ParseStatus parseStatus, int totalRows, int acceptedRows, int rejectedRows,
		int skippedRows, List<RejectedRowResponse> rejections, List<FindingResponse> findings, String failureReason) {

	/** Longest reason echoed back; the rest is truncated so the response stays bounded. */
	public static final int MAX_DETAIL_LENGTH = 500;

	public IngestionResponse {
		rejections = rejections == null ? List.of() : List.copyOf(rejections);
		findings = findings == null ? List.of() : List.copyOf(findings);
		failureReason = failureReason == null ? "" : failureReason;
	}

	public static IngestionResponse from(IngestionProcessingResult result) {
		Objects.requireNonNull(result, "result must not be null");
		return new IngestionResponse(result.ingestionRunId().orElse(null), result.sourceFileId().orElse(null),
				result.status(), result.finalStage(), result.parseStatus(), result.totalRowCount(),
				result.acceptedRowCount(), result.rejectedRowCount(), result.skippedRows(),
				result.rejectedRows().stream().map(RejectedRowResponse::from).toList(),
				result.findings().stream().map(FindingResponse::from).toList(), result.failureReason());
	}

	public boolean isSuccess() {
		return this.status != null && !this.status.isFailure();
	}

	/**
	 * One refused row: where it was, which column, and why.
	 *
	 * @param rowNumber 1-based row number in the source, counting the header
	 * @param sheetName the spreadsheet tab, empty for a delimited file
	 */
	public record RejectedRowResponse(long rowNumber, String sheetName, String sourceFileName, String columnName,
			String reason, String detail) {

		public RejectedRowResponse {
			sheetName = sheetName == null ? "" : sheetName;
			sourceFileName = sourceFileName == null ? "" : sourceFileName;
			columnName = columnName == null ? "" : columnName;
			reason = reason == null ? "" : reason;
			detail = detail == null ? "" : detail;
		}

		public static RejectedRowResponse from(RejectedRow row) {
			Objects.requireNonNull(row, "row must not be null");
			RowCoordinate coordinate = row.coordinate();
			return new RejectedRowResponse(coordinate.rowNumber(), coordinate.sheetName(), coordinate.sourceFileName(),
					row.columnName(), row.reason().name(), truncate(row.detail()));
		}

	}

	/**
	 * One structured observation about the file.
	 *
	 * @param rowNumber null for a file-level finding, which has no row
	 */
	public record FindingResponse(String errorType, String severity, String reason, Long rowNumber, String columnName,
			String message) {

		public FindingResponse {
			errorType = errorType == null ? "" : errorType;
			severity = severity == null ? "" : severity;
			reason = reason == null ? "" : reason;
			columnName = columnName == null ? "" : columnName;
			message = message == null ? "" : message;
		}

		public static FindingResponse from(ValidationFinding finding) {
			Objects.requireNonNull(finding, "finding must not be null");
			Long rowNumber = finding.coordinate() == null ? null : finding.coordinate().rowNumber();
			return new FindingResponse(finding.errorType().name(), finding.severity().name(), finding.reason().name(),
					rowNumber, finding.columnName(), truncate(finding.message()));
		}

	}

	private static String truncate(String value) {
		return value.length() <= MAX_DETAIL_LENGTH ? value : value.substring(0, MAX_DETAIL_LENGTH);
	}

}
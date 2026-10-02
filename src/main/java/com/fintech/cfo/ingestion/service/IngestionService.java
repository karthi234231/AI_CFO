package com.fintech.cfo.ingestion.service;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.fintech.cfo.ingestion.enums.IngestionErrorType;
import com.fintech.cfo.ingestion.enums.IngestionStage;
import com.fintech.cfo.ingestion.enums.IngestionStatus;
import com.fintech.cfo.ingestion.enums.ParseStatus;
import com.fintech.cfo.ingestion.model.FileParseResult;
import com.fintech.cfo.ingestion.model.FileRejection;
import com.fintech.cfo.ingestion.model.IngestionProcessingResult;
import com.fintech.cfo.ingestion.model.IngestionRequest;
import com.fintech.cfo.ingestion.model.UploadMetadata;
import com.fintech.cfo.ingestion.model.ValidationFinding;
import com.fintech.cfo.ingestion.model.ValidationResult;
import com.fintech.cfo.ingestion.parser.ParseOutcome;

/**
 * The end-to-end in-memory ingestion flow: gate, parse, validate, count.
 *
 * <p>This is the only place the stages are sequenced, and the sequencing is the
 * point. Parsing happens strictly after the security gate has admitted the
 * upload and strictly before any row is trusted; there is no path that reaches a
 * parser with unrefuted bytes, and no path that accepts a row that was not
 * validated. Each stage owns its own failures, so a corrupt workbook stops the
 * run without discarding the way a CSV with three bad rows kept going.
 *
 * <p>The accounting is total. Every input row is exactly one of accepted,
 * rejected or skipped, and {@code accepted + rejected + skipped} is the reader's
 * own total. The reasons stay on the rejections and the findings instead of being
 * collapsed into a status, so a run that refused rows can never be summarised as
 * "ingestion succeeded" — {@link IngestionStatus#COMPLETED_WITH_REJECTIONS} is
 * derived from the evidence, not chosen by a caller.
 *
 * <p>Nothing is persisted and no clock is read: {@code uploadedAt} and
 * {@code asOfDate} are injected, so the same upload re-run later produces the same
 * record (rule 3). No Spring, no database, no network — which is what lets the
 * whole flow be exercised as a unit test.
 */
public final class IngestionService {

	private final FileSecurityService fileSecurity;

	private final IngestionOrchestrator orchestrator;

	private final FileValidationService validation;

	public IngestionService() {
		this(new FileSecurityService(), new IngestionOrchestrator(), new FileValidationService());
	}

	public IngestionService(FileSecurityService fileSecurity, IngestionOrchestrator orchestrator,
			FileValidationService validation) {
		this.fileSecurity = Objects.requireNonNull(fileSecurity, "fileSecurity must not be null");
		this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator must not be null");
		this.validation = Objects.requireNonNull(validation, "validation must not be null");
	}

	/**
	 * @return the terminal result of the whole flow; never {@code null} and never
	 * throwing for bad input
	 */
	public IngestionProcessingResult ingest(IngestionRequest request) {
		Objects.requireNonNull(request, "request must not be null");

		IngestionProcessingResult.Builder result = IngestionProcessingResult.builder()
			.identifiers(request.ingestionRunId(), request.organizationId(), fileIdOf(request))
			.status(IngestionStatus.RUNNING)
			.finalStage(IngestionStage.FILE_SECURITY_VALIDATION);

		FileSecurityService.Admission admission = this.fileSecurity.admit(request);
		if (admission instanceof FileSecurityService.Refused refused) {
			// The gate refused, so no parser ran and there are no rows to account for.
			// Reporting zero rows rather than leaving them unset is the point: a refused
			// upload must not be able to look like a run that read a file and found
			// nothing in it.
			return result.status(IngestionStatus.REJECTED)
				.finalStage(IngestionStage.FILE_SECURITY_VALIDATION)
				.failureReason(refused.reason())
				.findings(refused.findings())
				.build();
		}

		UploadMetadata metadata = ((FileSecurityService.Admitted) admission).metadata();
		result.file(metadata).finalStage(IngestionStage.PARSING);

		ParseOutcome outcome = this.orchestrator.parse(request, metadata);
		result.parseStatus(outcome.status());

		return switch (outcome) {
			case ParseOutcome.Refused refused -> refusedAtParse(result, refused.rejection());
			case ParseOutcome.Empty ignored -> emptyRun(result);
			case ParseOutcome.Succeeded succeeded -> validated(result, request, succeeded.result());
			case ParseOutcome.PartiallyRead partial -> validated(result, request, partial.result());
		};
	}

	/**
	 * Runs the schema and row gates over what the reader produced and closes the
	 * accounting.
	 *
	 * <p>The parse status is preserved rather than reset: rows recovered from a
	 * partly-read file are usable, but the run must not claim the file was seen in
	 * full.
	 */
	private IngestionProcessingResult validated(IngestionProcessingResult.Builder result, IngestionRequest request,
			FileParseResult parse) {
		ValidationResult validated = this.validation.validate(parse, request.schema(), request.asOfDate(),
				request.limits());

		if (parse.status() == ParseStatus.PARTIAL) {
			// The reader already explained why it stopped; that reason is carried
			// forward instead of letting the row counts imply the file was fully
			// consumed.
			result.failureReason(parse.failureReason());
		}

		return result.finalStage(IngestionStage.DATA_QUALITY_VALIDATION)
			.acceptAll(validated.acceptedRows())
			.rejectAll(validated.rejectedRows())
			.findings(validated.findings())
			.skipRows(parse.skippedRows())
			.build();
	}

	/**
	 * The file could not be read. The reader's own reason becomes the run's reason,
	 * so the two can never disagree about why a run stopped.
	 */
	private IngestionProcessingResult refusedAtParse(IngestionProcessingResult.Builder result,
			FileRejection rejection) {
		return result.status(IngestionStatus.FAILED)
			.finalStage(IngestionStage.PARSING)
			.failureReason(rejection.reason() + ": " + rejection.detail())
			.finding(ValidationFinding.file(IngestionErrorType.PARSE, rejection.reason(), rejection.detail()))
			.build();
	}

	/**
	 * A readable file with no rows. Deliberately not a refusal: nothing was wrong,
	 * there was simply nothing there, and an operator has to be able to tell those
	 * two situations apart.
	 */
	private IngestionProcessingResult emptyRun(IngestionProcessingResult.Builder result) {
		return result.finalStage(IngestionStage.DATA_QUALITY_VALIDATION)
			.failureReason("the file contained no data rows")
			.build();
	}

	/**
	 * @return the admitted metadata, or empty when the gate refused; for callers
	 * that need the checksum without re-running the whole flow
	 */
	public Optional<UploadMetadata> screenOnly(IngestionRequest request) {
		FileSecurityService.Admission admission = this.fileSecurity.admit(request);
		return admission instanceof FileSecurityService.Admitted admitted ? Optional.of(admitted.metadata())
				: Optional.empty();
	}

	/**
	 * The source file id is caller-supplied text but is a {@code UUID} in
	 * {@code source_files} (V3), so a value that is not a UUID is a caller defect
	 * and is reported as one rather than being quietly defaulted to {@code null}.
	 */
	private static UUID fileIdOf(IngestionRequest request) {
		String sourceFileId = request.sourceFileId();
		try {
			return UUID.fromString(sourceFileId);
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalArgumentException("sourceFileId must be a UUID in this module, was " + sourceFileId,
					ex);
		}
	}

}
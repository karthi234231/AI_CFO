package com.fintech.cfo.ingestion.service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.fintech.cfo.ingestion.enums.FileSecurityStatus;
import com.fintech.cfo.ingestion.enums.FileType;
import com.fintech.cfo.ingestion.enums.IngestionErrorType;
import com.fintech.cfo.ingestion.enums.RejectionReason;
import com.fintech.cfo.ingestion.model.FileChecksum;
import com.fintech.cfo.ingestion.model.FileSecurityResult;
import com.fintech.cfo.ingestion.model.IngestionRequest;
import com.fintech.cfo.ingestion.model.SanitisedFilename;
import com.fintech.cfo.ingestion.model.UploadMetadata;
import com.fintech.cfo.ingestion.model.ValidationFinding;
import com.fintech.cfo.ingestion.security.FileUploadSecurityService;
import com.fintech.cfo.ingestion.security.UploadAuthorizationService;

/**
 * The gate every upload passes before a parser is allowed to look at it, and the
 * stage that turns a screened upload into the {@link UploadMetadata} the rest of
 * the pipeline works from.
 *
 * <p>Three questions, in the order that touches the fewest things:
 *
 * <ol>
 * <li><b>May this caller do this at all?</b> The upload authority and the tenant
 * match are both required. A refusal here is reported as
 * {@link RejectionReason#ACCESS_DENIED} rather than as a file problem, so an
 * audit can tell "this user may not upload" from "this file is bad".</li>
 * <li><b>Is this name safe?</b> Traversal is refused, not stripped, and what
 * survives is a {@link SanitisedFilename}. The raw name never leaves this class as
 * anything but an audit string.</li>
 * <li><b>Are these bytes what the name claims?</b> Dangerous signatures are
 * refuted first, then extension, declared content type and sniffed content must
 * agree.</li>
 * </ol>
 *
 * <p>The checksum is computed here, over the bytes that actually passed, because
 * rule 3 requires an input checksum for anything later claimed to be
 * reproducible, and because it must be the checksum of the accepted bytes rather
 * than of a re-read of a stream that may since have changed.
 *
 * <p>Pure: no Spring, no I/O beyond the byte array the caller supplies, and
 * nothing is persisted. What comes back is a decision, not a stored fact.
 */
public final class FileSecurityService {

	private final FileUploadSecurityService uploadSecurity;

	private final UploadAuthorizationService authorization;

	public FileSecurityService() {
		this(new FileUploadSecurityService(), new UploadAuthorizationService());
	}

	public FileSecurityService(FileUploadSecurityService uploadSecurity, UploadAuthorizationService authorization) {
		this.uploadSecurity = Objects.requireNonNull(uploadSecurity, "uploadSecurity must not be null");
		this.authorization = Objects.requireNonNull(authorization, "authorization must not be null");
	}

	/**
	 * Screens the upload without deciding the rest of the flow, for callers that
	 * want the raw findings.
	 */
	public FileSecurityResult screen(IngestionRequest request) {
		Objects.requireNonNull(request, "request must not be null");
		return this.uploadSecurity.screen(request.contentUnsafe(), request.originalFilename(), request.contentType(),
				request.limits());
	}

	/**
	 * Runs the whole pre-parse gate.
	 *
	 * @return {@link Admitted} with metadata whose security status is
	 * {@link FileSecurityStatus#PASSED}, or {@link Refused} carrying the findings.
	 * There is no third outcome and no {@code null}: an upload that was not
	 * screened cannot be confused with one that was.
	 */
	public Admission admit(IngestionRequest request) {
		Objects.requireNonNull(request, "request must not be null");

		if (!this.authorization.isAuthorized(request.principal(), request.organizationId())) {
			return new Refused(FileSecurityStatus.REJECTED, FileType.fromFilename(request.originalFilename()),
					List.of(ValidationFinding.file(IngestionErrorType.FILE_SECURITY, RejectionReason.ACCESS_DENIED,
							"the caller may not start an ingestion run for this organization")),
					"the caller is not authorised to upload for this organization");
		}

		FileSecurityResult screened = screen(request);
		if (!screened.allowsParsing()) {
			return new Refused(screened.status(), screened.declaredFileType(), screened.findings(), describe(screened));
		}

		SanitisedFilename filename = screened.filename()
			.orElseThrow(() -> new IllegalStateException("a passed file-security result must carry a sanitised name"));
		byte[] content = request.contentUnsafe();
		return new Admitted(new UploadMetadata(request.sourceFileId(), request.organizationId(), request.uploadedBy(),
				request.originalFilename(), filename.displayName(), screened.contentType(), screened.declaredFileType(),
				screened.detectedFileType(), content.length, FileChecksum.sha256(content), FileSecurityStatus.PASSED,
				request.uploadedAt(), request.sourceSystem(), ""));
	}

	private static String describe(FileSecurityResult screened) {
		return screened.firstError()
			.map(finding -> finding.reason() + ": " + finding.message())
			.orElse("the upload was refused by the file-security gate");
	}

	/**
	 * The result of the pre-parse gate.
	 *
	 * <p>Sealed so a caller cannot treat a refusal as a pass, which is the mistake
	 * that would put an unreviewed file in front of a parser.
	 */
	public sealed interface Admission permits FileSecurityService.Admitted, FileSecurityService.Refused {

		/** @return true only for {@link Admitted} */
		boolean admitted();

		/** @return the findings to attach to the run; never {@code null} */
		List<ValidationFinding> findings();

	}

	/**
	 * The upload may be parsed.
	 *
	 * @param metadata the immutable description of an upload that passed every gate
	 */
	public record Admitted(UploadMetadata metadata) implements Admission {

		public Admitted {
			Objects.requireNonNull(metadata, "metadata must not be null");
			if (!metadata.securityStatus().allowsParsing()) {
				throw new IllegalArgumentException("admitted metadata must be PASSED, was "
						+ metadata.securityStatus());
			}
		}

		@Override
		public boolean admitted() {
			return true;
		}

		@Override
		public List<ValidationFinding> findings() {
			return List.of();
		}

	}

	/**
	 * The upload may not be parsed.
	 *
	 * @param status          the security decision itself, so the value persisted to
	 *                        {@code source_files.security_status} is the decision and
	 *                        not a re-derivation of it
	 * @param declaredFileType what the name claimed, kept for the rejection record
	 * @param findings        the structured findings, file-level by construction
	 * @param reason          a content-free one-line explanation
	 */
	public record Refused(FileSecurityStatus status, FileType declaredFileType, List<ValidationFinding> findings,
			String reason) implements Admission {

		public Refused {
			Objects.requireNonNull(status, "status must not be null");
			Objects.requireNonNull(declaredFileType, "declaredFileType must not be null");
			findings = findings == null ? List.of() : List.copyOf(findings);
			reason = reason == null ? "" : reason;
		}

		@Override
		public boolean admitted() {
			return false;
		}

		/** @return the first blocking finding, when the gate produced one */
		public Optional<ValidationFinding> firstError() {
			return this.findings.stream().filter(ValidationFinding::isError).findFirst();
		}

	}

}
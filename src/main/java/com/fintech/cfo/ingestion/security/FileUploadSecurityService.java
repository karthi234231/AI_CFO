package com.fintech.cfo.ingestion.security;

import java.util.List;
import java.util.Objects;

import com.fintech.cfo.ingestion.enums.FileType;
import com.fintech.cfo.ingestion.enums.IngestionErrorType;
import com.fintech.cfo.ingestion.enums.RejectionReason;
import com.fintech.cfo.ingestion.enums.ValidationSeverity;
import com.fintech.cfo.ingestion.model.FileSecurityResult;
import com.fintech.cfo.ingestion.model.IngestionLimits;
import com.fintech.cfo.ingestion.model.SanitisedFilename;
import com.fintech.cfo.ingestion.model.ValidationFinding;
import com.fintech.cfo.ingestion.validator.UploadFileValidator;

/**
 * The untrusted-input gate, in the order that avoids touching the bytes for as
 * long as possible.
 *
 * <p>Pipeline: prove the filename is a leaf name, refute known-dangerous
 * signatures, then hand the sanitised name and bytes to the type gate. A
 * filename that carried a directory component is refused outright rather than
 * quietly normalised, because a genuine upload never has one and the attempt is
 * worth recording.
 *
 * <p>Everything downstream receives a {@link SanitisedFilename}, never the raw
 * string. That is the whole point of the type: there is no code path in which a
 * caller forgets to sanitise, because the unsanitised value is not passed on.
 */
public final class FileUploadSecurityService {

	private final FilenameSanitiser filenameSanitiser;

	private final MalwareScanService malwareScanner;

	private final UploadFileValidator uploadFileValidator;

	public FileUploadSecurityService() {
		this(new FilenameSanitiser(), new MalwareScanService(),
				new UploadFileValidator());
	}

	public FileUploadSecurityService(FilenameSanitiser filenameSanitiser, MalwareScanService malwareScanner,
			UploadFileValidator uploadFileValidator) {
		this.filenameSanitiser = Objects.requireNonNull(filenameSanitiser, "filenameSanitiser must not be null");
		this.malwareScanner = Objects.requireNonNull(malwareScanner, "malwareScanner must not be null");
		this.uploadFileValidator = Objects.requireNonNull(uploadFileValidator, "uploadFileValidator must not be null");
	}

	public FileSecurityResult screen(byte[] content, String filename, String contentType, IngestionLimits limits) {
		Objects.requireNonNull(limits, "limits must not be null");

		if (containsTraversal(filename)) {
			return FileSecurityResult.rejected(RejectionReason.PATH_TRAVERSAL_ATTEMPT,
					"the filename contains a directory traversal segment", com.fintech.cfo.ingestion.enums.FileType
							.fromFilename(filename),
					contentType);
		}

		MalwareScanService.ScanResult scan = this.malwareScanner.scan(content);
		if (!scan.clean()) {
			return FileSecurityResult.rejected(RejectionReason.EXECUTABLE_CONTENT, scan.detail(),
					FileType.fromFilename(filename), contentType);
		}

		SanitisedFilename sanitised = this.filenameSanitiser.sanitise(filename);
		List<ValidationFinding> notes = List.of();
		if (!sanitised.displayName().equals(filename == null ? "" : filename)) {
			notes = List.of(ValidationFinding
					.file(ValidationSeverity.WARNING, IngestionErrorType.FILE_SECURITY,
							RejectionReason.UNSAFE_FILENAME,
							"the uploaded filename was normalised to '" + sanitised.displayName() + "'")
					.withSeverity(ValidationSeverity.WARNING));
		}
		FileSecurityResult validated = this.uploadFileValidator.validate(content, sanitised, contentType, limits);
		if (validated.allowsParsing() && !notes.isEmpty()) {
			return FileSecurityResult.passed(sanitised, validated.declaredFileType(), validated.detectedFileType(),
					validated.contentType(), notes);
		}
		return validated;
	}

	public FileSecurityResult screen(byte[] content, String filename, String contentType) {
		return screen(content, filename, contentType, IngestionLimits.defaults());
	}

	/**
	 * A genuine browser upload is a leaf name. A separator, a drive letter or a
	 * {@code ..} segment in the supplied name is an attempt to influence where the
	 * bytes land, and is refused rather than stripped.
	 */
	static boolean containsTraversal(String filename) {
		if (filename == null || filename.isEmpty()) {
			return false;
		}
		if (filename.indexOf('\u0000') >= 0) {
			return true;
		}
		String normalised = filename.replace('\\', '/');
		if (normalised.startsWith("/") || normalised.contains("://")) {
			return true;
		}
		for (String segment : normalised.split("/")) {
			if ("..".equals(segment)) {
				return true;
			}
		}
		return false;
	}

}
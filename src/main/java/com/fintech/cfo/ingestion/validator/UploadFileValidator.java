package com.fintech.cfo.ingestion.validator;

import java.util.List;
import java.util.Objects;

import com.fintech.cfo.ingestion.enums.FileType;
import com.fintech.cfo.ingestion.enums.RejectionReason;
import com.fintech.cfo.ingestion.model.FileSecurityResult;
import com.fintech.cfo.ingestion.model.IngestionLimits;
import com.fintech.cfo.ingestion.model.SanitisedFilename;

/**
 * The gate a file must pass before any parser sees it.
 *
 * <p>Four independent claims are compared: the extension, the declared content
 * type, the bytes themselves, and the size. Agreement is required between the
 * first three; a file whose name says CSV but whose bytes are a ZIP container is
 * refused rather than coerced, because that mismatch is exactly how a renamed
 * payload gets read by the wrong parser.
 *
 * <p>An absent or generic content type is not treated as a lie. Browsers send
 * {@code application/octet-stream} for a great many honest uploads, so it is
 * accepted as "unspecified" and the decision rests on extension plus bytes.
 */
public final class UploadFileValidator {

	public FileSecurityResult validate(byte[] content, SanitisedFilename filename, String declaredContentType,
			IngestionLimits limits) {
		Objects.requireNonNull(filename, "filename must not be null");
		Objects.requireNonNull(limits, "limits must not be null");

		FileType declared = FileType.fromFilename(filename.displayName());
		if (!declared.isReadable()) {
			return FileSecurityResult.rejected(RejectionReason.UNSUPPORTED_FILE_TYPE,
					describeUnsupported(declared, filename), declared, declaredContentType);
		}
		if (content == null || content.length == 0) {
			return FileSecurityResult.rejected(RejectionReason.EMPTY_FILE, "the uploaded file has no content",
					declared, declaredContentType);
		}
		if (content.length > limits.maxFileBytes()) {
			return FileSecurityResult.rejected(RejectionReason.SIZE_LIMIT_EXCEEDED,
					"the file is " + content.length + " bytes and the limit is " + limits.maxFileBytes(), declared,
					declaredContentType);
		}

		FileType byContentType = FileType.fromContentType(declaredContentType);
		if (byContentType != FileType.UNKNOWN && byContentType != declared) {
			return FileSecurityResult.rejected(RejectionReason.EXTENSION_CONTENT_TYPE_MISMATCH,
					"the filename claims " + declared.code() + " but the content type claims " + byContentType.code(),
					declared, declaredContentType);
		}

		ContentSniffer.Kind kind = ContentSniffer.sniff(content);
		switch (kind) {
			case EMPTY -> {
				return FileSecurityResult.rejected(RejectionReason.EMPTY_FILE, "the uploaded file has no content",
						declared, declaredContentType);
			}
			case EXECUTABLE -> {
				return FileSecurityResult.rejected(RejectionReason.EXECUTABLE_CONTENT,
						"the content carries an executable signature", declared, declaredContentType);
			}
			case ZIP_PACKAGE -> {
				if (declared != FileType.XLSX) {
					return FileSecurityResult.rejected(RejectionReason.EXTENSION_CONTENT_TYPE_MISMATCH,
							"the content is a ZIP container but the filename claims " + declared.code(), declared,
							declaredContentType);
				}
			}
			case OLE2_PACKAGE, PDF -> {
				return FileSecurityResult.rejected(RejectionReason.UNSUPPORTED_FILE_TYPE,
						"the content is a " + ContentSniffer.describe(content) + " document, not "
								+ declared.code(),
						declared, declaredContentType);
			}
			case BINARY -> {
				return FileSecurityResult.rejected(RejectionReason.CONTENT_TYPE_MISMATCH,
						"the content is not readable text and is not a spreadsheet package", declared,
						declaredContentType);
			}
			case TEXT -> {
				if (declared != FileType.CSV) {
					return FileSecurityResult.rejected(RejectionReason.EXTENSION_CONTENT_TYPE_MISMATCH,
							"the content is plain text but the filename claims " + declared.code(), declared,
							declaredContentType);
				}
			}
		}

		FileType detected = declared == FileType.XLSX ? FileType.XLSX : FileType.CSV;
		return FileSecurityResult.passed(filename, declared, detected, declaredContentType, List.of());
	}

	private static String describeUnsupported(FileType declared, SanitisedFilename filename) {
		if (declared == FileType.UNSUPPORTED) {
			return "the " + filename.extension() + " format is recognised but not supported; export as CSV or XLSX";
		}
		String extension = filename.extension();
		if (extension.isEmpty()) {
			return "the upload has no file extension and cannot be routed to a parser";
		}
		return "the '" + extension + "' extension is not a supported upload format";
	}

}
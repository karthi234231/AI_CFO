package com.fintech.cfo.ingestion.dto;

import java.util.Objects;

import com.fintech.cfo.ingestion.enums.FileSecurityStatus;
import com.fintech.cfo.ingestion.model.UploadMetadata;

/**
 * What the file-security gate concluded about an upload.
 *
 * <p>Reports both the declared and the detected type separately rather than one
 * "fileType" field. Collapsing them is how a name that says CSV and bytes that are
 * a ZIP container come to be logged as agreeing.
 *
 * <p>Both filenames appear: the original for the audit trail, the sanitised one as
 * the only value that may be shown or reused. The checksum is here so a client can
 * prove it uploaded the same bytes it thinks it did, and because
 * {@code (organization_id, checksum_sha256)} is the de-duplication key in V3.
 *
 * <p>The bytes themselves are never in this record — only their length and their
 * digest (rule 5).
 */
public record UploadedFileResponse(String fileId, String originalFilename, String sanitizedFilename, String contentType,
		String declaredFileType, String detectedFileType, long sizeBytes, String checksumSha256,
		FileSecurityStatus securityStatus, String sourceSystem) {

	public UploadedFileResponse {
		Objects.requireNonNull(fileId, "fileId must not be null");
		originalFilename = originalFilename == null ? "" : originalFilename;
		sanitizedFilename = sanitizedFilename == null ? "" : sanitizedFilename;
		contentType = contentType == null ? "" : contentType;
		declaredFileType = declaredFileType == null ? "" : declaredFileType;
		detectedFileType = detectedFileType == null ? "" : detectedFileType;
		checksumSha256 = checksumSha256 == null ? "" : checksumSha256;
		sourceSystem = sourceSystem == null ? "" : sourceSystem;
		securityStatus = securityStatus == null ? FileSecurityStatus.PENDING : securityStatus;
	}

	public static UploadedFileResponse from(UploadMetadata metadata) {
		Objects.requireNonNull(metadata, "metadata must not be null");
		return new UploadedFileResponse(metadata.fileId(), metadata.originalFilename(), metadata.sanitizedFilename(),
				metadata.contentType(), metadata.declaredFileType().code(),
				metadata.detectedFileType() == null ? "" : metadata.detectedFileType().code(), metadata.sizeBytes(),
				metadata.checksum() == null ? "" : metadata.checksum().sha256(), metadata.securityStatus(),
				metadata.sourceSystem());
	}

	/** @return true when the upload may be handed to a parser */
	public boolean allowsParsing() {
		return this.securityStatus.allowsParsing();
	}

}
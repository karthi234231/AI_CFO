package com.fintech.cfo.ingestion.model;

import java.time.Instant;
import java.util.Objects;

import com.fintech.cfo.ingestion.enums.FileSecurityStatus;
import com.fintech.cfo.ingestion.enums.FileType;
import com.fintech.cfo.shared.domain.OrganizationId;
import com.fintech.cfo.shared.domain.UserId;

/**
 * Everything known about an upload before it is parsed, after the security and
 * file-type gates have run.
 *
 * <p>Mirrors the {@code source_files} columns of {@code V3__create_ingestion.sql}
 * as an immutable value — deliberately not a JPA entity, so this module can be
 * reasoned about and unit-tested with no database in the picture.
 *
 * <p>Two filenames are kept on purpose. {@code originalFilename} is what the
 * client sent and is retained for the audit trail; {@code sanitizedFilename} is
 * the only one that may ever reach a storage key, a log line or a downstream
 * display. Conflating them is how path traversal gets in.
 *
 * <p>{@code uploadedAt} is supplied by the caller rather than read from the
 * system clock, so an ingestion run is reproducible months later (rule 3).
 */
public record UploadMetadata(String fileId, OrganizationId organizationId,
		UserId uploadedBy, String originalFilename, String sanitizedFilename, String contentType, FileType declaredFileType,
		FileType detectedFileType, long sizeBytes, FileChecksum checksum, FileSecurityStatus securityStatus,
		Instant uploadedAt, String sourceSystem, String storageKey) {

	public UploadMetadata {
		fileId = requireText(fileId, "fileId");
		originalFilename = requireText(originalFilename, "originalFilename");
		sanitizedFilename = requireText(sanitizedFilename, "sanitizedFilename");
		declaredFileType = Objects.requireNonNull(declaredFileType, "declaredFileType must not be null");
		securityStatus = Objects.requireNonNull(securityStatus, "securityStatus must not be null");
		uploadedAt = Objects.requireNonNull(uploadedAt, "uploadedAt must not be null (inject the clock)");
		if (sizeBytes < 0) {
			throw new IllegalArgumentException("sizeBytes must not be negative");
		}
		organizationId = organizationId;
		uploadedBy = uploadedBy;
		contentType = contentType == null ? "" : contentType;
		detectedFileType = detectedFileType;
		checksum = checksum;
		sourceSystem = sourceSystem == null ? "" : sourceSystem;
		storageKey = storageKey == null ? "" : storageKey;
	}

	private static String requireText(String value, String field) {
		Objects.requireNonNull(value, field + " must not be null");
		String trimmed = value.trim();
		if (trimmed.isEmpty()) {
			throw new IllegalArgumentException(field + " must not be blank");
		}
		return trimmed;
	}

	/**
	 * @return true when the sniffed type and the declared type agree, or when the
	 * sniffed type is unknown because the content had no recognisable signature
	 */
	public boolean typeIsConsistent() {
		return this.detectedFileType == null || this.detectedFileType == FileType.UNKNOWN
				|| this.detectedFileType == this.declaredFileType;
	}

	/**
	 * @return the name safe to show a user and safe to place in a storage key
	 */
	public String displayName() {
		return this.sanitizedFilename;
	}

	public UploadMetadata withSecurityStatus(FileSecurityStatus status) {
		return new UploadMetadata(this.fileId, this.organizationId, this.uploadedBy, this.originalFilename,
				this.sanitizedFilename, this.contentType, this.declaredFileType, this.detectedFileType, this.sizeBytes,
				this.checksum, status, this.uploadedAt, this.sourceSystem, this.storageKey);
	}

	public UploadMetadata withChecksum(FileChecksum newChecksum) {
		return new UploadMetadata(this.fileId, this.organizationId, this.uploadedBy, this.originalFilename,
				this.sanitizedFilename, this.contentType, this.declaredFileType, this.detectedFileType, this.sizeBytes,
				newChecksum, this.securityStatus, this.uploadedAt, this.sourceSystem, this.storageKey);
	}

	public UploadMetadata withStorageKey(String key) {
		return new UploadMetadata(this.fileId, this.organizationId, this.uploadedBy, this.originalFilename,
				this.sanitizedFilename, this.contentType, this.declaredFileType, this.detectedFileType, this.sizeBytes,
				this.checksum, this.securityStatus, this.uploadedAt, this.sourceSystem, key);
	}

	@Override
	public String toString() {
		return "UploadMetadata[" + this.fileId + " " + this.sanitizedFilename + " " + this.declaredFileType
				+ " " + this.sizeBytes + "B " + this.securityStatus + "]";
	}

}
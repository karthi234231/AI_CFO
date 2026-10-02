package com.fintech.cfo.ingestion.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.fintech.cfo.ingestion.enums.FileSecurityStatus;
import com.fintech.cfo.ingestion.enums.FileType;
import com.fintech.cfo.shared.domain.OrganizationId;
import com.fintech.cfo.shared.domain.UserId;

/**
 * Immutable value mirroring one {@code source_files} row of
 * {@code V3__create_ingestion.sql}.
 *
 * <p>Not a JPA entity. This module has no persistence: the repository layer that
 * will map these columns to a table belongs to the wiring milestone, and keeping
 * the two apart is what lets the whole ingestion path be unit-tested in memory.
 *
 * <p>The column widths of V3 are enforced here rather than discovered later as a
 * failed insert: {@code original_filename} is 512, {@code content_type} 255,
 * {@code storage_key} 1024, {@code checksum_sha256} exactly 64.
 */
public record SourceFile(UUID id, OrganizationId organizationId, String originalFilename, String contentType,
		String storageKey, long sizeBytes, FileChecksum checksum, FileType declaredFileType, FileType detectedFileType,
		FileSecurityStatus securityStatus, UserId uploadedBy, Instant uploadedAt, Instant createdAt) {

	public static final int MAX_FILENAME_LENGTH = 512;
	public static final int MAX_CONTENT_TYPE_LENGTH = 255;
	public static final int MAX_STORAGE_KEY_LENGTH = 1024;

	public SourceFile {
		Objects.requireNonNull(id, "id must not be null");
		Objects.requireNonNull(organizationId, "organizationId must not be null (organization_id is the tenant boundary)");
		Objects.requireNonNull(checksum, "checksum must not be null (V3 makes it NOT NULL)");
		Objects.requireNonNull(declaredFileType, "declaredFileType must not be null (V3 makes it NOT NULL)");
		Objects.requireNonNull(securityStatus, "securityStatus must not be null (V3 defaults it to PENDING)");
		originalFilename = require(originalFilename, MAX_FILENAME_LENGTH, "originalFilename");
		contentType = contentType == null ? "" : require(contentType, MAX_CONTENT_TYPE_LENGTH, "contentType");
		storageKey = require(storageKey, MAX_STORAGE_KEY_LENGTH, "storageKey");
		if (sizeBytes < 0) {
			throw new IllegalArgumentException("size_bytes must not be negative");
		}
		uploadedAt = uploadedAt == null ? Instant.EPOCH : uploadedAt;
		createdAt = createdAt == null ? uploadedAt : createdAt;
	}

	public static SourceFile of(UUID id, OrganizationId organizationId, UploadMetadata metadata) {
		Objects.requireNonNull(metadata, "metadata must not be null");
		return new SourceFile(id, organizationId, metadata.originalFilename(), metadata.contentType(),
				metadata.storageKey(), metadata.sizeBytes(), metadata.checksum(), metadata.declaredFileType(),
				metadata.detectedFileType(), metadata.securityStatus(), metadata.uploadedBy(), metadata.uploadedAt(),
				metadata.uploadedAt());
	}

	/** V3 unique index {@code ux_source_files_org_checksum}: one copy per tenant. */
	public boolean duplicates(SourceFile other) {
		return other != null && this.organizationId.equals(other.organizationId)
				&& this.checksum.matches(other.checksum);
	}

	private static String require(String value, int maxLength, String field) {
		Objects.requireNonNull(value, field + " must not be null (V3 declares it NOT NULL)");
		if (value.isBlank()) {
			throw new IllegalArgumentException(field + " must not be blank");
		}
		if (value.length() > maxLength) {
			throw new IllegalArgumentException(field + " exceeds the V3 column width of " + maxLength + " characters");
		}
		return value;
	}

}
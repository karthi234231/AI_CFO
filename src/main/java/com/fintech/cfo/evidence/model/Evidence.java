package com.fintech.cfo.evidence.model;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.fintech.cfo.evidence.enums.EvidenceType;
import com.fintech.cfo.shared.domain.OrganizationId;
import com.fintech.cfo.shared.exception.ValidationException;
import com.fintech.cfo.shared.validation.Preconditions;

/**
 * One proof that a claim can be checked, mirroring a row of {@code evidences} in V7.
 *
 * <p>The row is a claim about proof, not the proof itself. It carries a title, a
 * description, a source locator, an optional storage key and a content hash; it never
 * carries the bytes of a source document, because a copy inside a business row can
 * neither be independently re-read nor independently deleted.
 *
 * <h2>What the compact constructor refuses</h2>
 *
 * <ul>
 * <li>A {@link EvidenceType} that {@link EvidenceType#requiresSourceFile()} with no
 * {@code sourceFileId}. {@code CHAR(64)}-style truncation is not the risk here;
 * storing an unlocatable {@code SOURCE_ROW} is, because a reviewer would be asked to
 * verify a row nobody can find.</li>
 * <li>A type that {@link EvidenceType#requiresSourceRow()} with no row number: a file
 * reference identifies a container, not the fact being asserted.</li>
 * <li>A row number of zero or less. Row numbering in a parser is 1-based, and a
 * zero-based number stored beside a 1-based parser is an off-by-one that would send a
 * reviewer to the wrong line and look correct while doing it.</li>
 * </ul>
 *
 * <p>{@code version} is the V7 optimistic-lock counter. {@link #withArtifact} and
 * {@link #withStorageKey} both increment it, so a lost update is detectable rather
 * than silently overwriting the storage pointer of evidence someone else has already
 * read.
 *
 * <p><b>What "immutable" means here, precisely.</b> It does not mean the row is
 * frozen: {@code storage_key} may be attached once and cleared once when its object
 * is destroyed, and {@code content_hash} may be restated when a stored object is
 * re-verified. It means the two columns that define <em>what was claimed and where it
 * came from</em> - {@code evidence_type} and the source locator - never change after
 * registration, so a figure cited from this row can never be silently re-pointed at a
 * different piece of data while keeping its old citation.
 *
 * <p><b>Referential integrity.</b> {@code source_file_id} is a plain {@code UUID}
 * rather than a reference to an ingestion type, because {@code evidences.source_file_id}
 * is a foreign key onto a table another module owns. The cost is that this type cannot
 * prove the file exists or belongs to the same tenant; that check belongs to the
 * persistence pass, and it belongs there because it needs the query, not the value.
 * {@code version} is present for the same reason: a future repository needs a column it
 * can put in a {@code WHERE} clause, and a value object cannot conjure one.
 *
 * <p>One stale reference is left in place above rather than edited: the paragraph on
 * {@code version} links {@code #withStorageKey}, a method that was renamed to
 * {@link #withArtifact}. Both names describe the same single transition, and the link
 * is recorded in the module's explain document instead of being silently corrected.
 *
 * @param description free text; never a monetary figure or contract clause, because
 *                    this column is rendered in audit detail
 */
public record Evidence(
		UUID id,
		OrganizationId organizationId,
		EvidenceType evidenceType,
		String title,
		@Nullable String description,
		@Nullable UUID sourceFileId,
		@Nullable Long sourceRowNumber,
		@Nullable String storageKey,
		ContentHash contentHash,
		Instant createdAt,
		Instant updatedAt,
		long version) {

	/** Width of {@code evidences.title} in V7. */
	public static final int MAX_TITLE_LENGTH = 500;

	/** Width of {@code evidences.description} in V7. */
	public static final int MAX_DESCRIPTION_LENGTH = 2000;

	/** Width of {@code evidences.storage_key} in V7. */
	public static final int MAX_STORAGE_KEY_LENGTH = 1024;

	/**
	 * Compact constructor: the single gate every evidence row passes through.
	 *
	 * <p>Enforced here rather than in a service so that no factory, mapper or future
	 * repository can produce an unusable row. A row that is invalid because it names
	 * no source is discovered at insert time and refused, which is the only moment at
	 * which the refusal is still cheap.
	 *
	 * <p>Identity and tenancy are checked before anything optional: a null id or a
	 * null {@code organizationId} means the row has no tenant boundary at all, and
	 * every later check would be about a row that should not exist.
	 */
	public Evidence {
		if (id == null) {
			throw new ValidationException("id must not be null");
		}
		if (organizationId == null) {
			throw new ValidationException(
					"organizationId must not be null (organization_id is the tenant boundary)");
		}
		if (evidenceType == null) {
			throw new ValidationException("evidenceType must not be null");
		}
		title = com.fintech.cfo.shared.validation.Preconditions.requireText(title, "title", MAX_TITLE_LENGTH);
		description = com.fintech.cfo.shared.validation.Preconditions.optionalText(description, "description", MAX_DESCRIPTION_LENGTH);
		storageKey = com.fintech.cfo.shared.validation.Preconditions.optionalText(storageKey, "storageKey", MAX_STORAGE_KEY_LENGTH);
		if (contentHash == null) {
			throw new ValidationException("contentHash must not be null");
		}
		if (createdAt == null || updatedAt == null) {
			throw new ValidationException("createdAt and updatedAt must both be supplied; evidence never dates itself");
		}
		if (version < 0) {
			throw new ValidationException("version must not be negative");
		}
		if (sourceRowNumber != null && sourceRowNumber <= 0) {
			// Refused here and again in SourceLocation: both types can be built
			// independently, and a 0-based number stored beside a 1-based parser is
			// an off-by-one that points a reviewer at the wrong line while looking
			// entirely correct.
			throw new ValidationException("sourceRowNumber must be 1-based and positive");
		}
		// The two type-driven checks below are the module's core rule (§5 of the
		// implementation rules): an evidence row that cannot name what it was read
		// from is worse than a missing row, because a reviewer will trust it. They
		// run after the scalar checks so the failure a caller sees first is the
		// cheapest one to fix.
		if (evidenceType.requiresSourceFile() && sourceFileId == null) {
			throw new ValidationException(evidenceType.code()
					+ " evidence must name the source file it was taken from; a proof nobody can re-read is not proof");
		}
		if (evidenceType.requiresSourceRow() && sourceRowNumber == null) {
			throw new ValidationException(
					evidenceType.code() + " evidence must name the source row, not just the file that contains it");
		}
	}

	/** A newly registered row with no stored artifact and no source locator yet. */
	// @param id caller-supplied: evidence ids are referenced from other modules'
	// records, so generating one here would make a batch of registrations
	// non-reproducible for no benefit
	// @param organizationId the tenant, taken from the authenticated principal by the
	// caller and never from a request body
	// @param evidenceType decides which source columns become mandatory
	// @param title short human label, rendered in audit detail
	// @param description free text; blank collapses to null
	// @param sourceFileId file the proof was read from, or null for derived evidence
	// @param sourceRowNumber 1-based row within that file, or null
	// @param contentHash digest of the evidence content, never of the storage key
	// @param now the registration instant, supplied by the caller for determinism
	// @return a new evidence row at version 0 with no stored object attached
	// @throws ValidationException if the type requires a source the caller did not
	//         supply, or any column width or tenant boundary is violated
	public static Evidence register(UUID id, OrganizationId organizationId, EvidenceType evidenceType, String title,
			@Nullable String description, @Nullable UUID sourceFileId, @Nullable Long sourceRowNumber,
			ContentHash contentHash, Instant now) {
		// "no source locator yet" describes the artifact side: the storage key is the
		// only column left unset by this factory. The source columns are passed
		// straight through, so a SOURCE_ROW can be registered already pointing at the
		// row it was read from, which is the common case rather than an exception.
		// Version starts at 0 to match the V7 DEFAULT, so an in-memory row and a row
		// read back from the database compare equal before any update.
		return new Evidence(id, organizationId, evidenceType, title, description, sourceFileId, sourceRowNumber, null,
				contentHash, now, now, 0L);
	}

	/**
	 * The source locator, or {@code null} when this row is not tied to uploaded data.
	 *
	 * <p>Derived rather than stored: the record keeps the two columns separately, as
	 * V7 declares them, and this is the only place they are read together. A second
	 * copy of the pair held as its own field would be free to drift from the columns
	 * it is supposed to mirror.
	 *
	 * @return the file and optional row, or {@code null} when no file is named
	 */
	public @Nullable SourceLocation sourceLocator() {
		// Null sourceFileId is the discriminator, not the row number: evidence about a
		// whole file legitimately has a file and no row, whereas a row number with no
		// file cannot be located at all and is refused by the constructor.
		if (this.sourceFileId == null) {
			return null;
		}
		return new SourceLocation(this.sourceFileId, this.sourceRowNumber);
	}

	/**
	 * Whether the proof is backed by a stored object that can be fetched and re-hashed.
	 *
	 * <p>Not the same question as {@link #isIndependentlyVerifiable()}. A contractual
	 * term is verifiable against the contract and is stored nowhere; a snapshot may be
	 * stored and still not be verifiable by anyone outside this system. Keeping the
	 * two apart stops a caller from substituting one for the other in a report.
	 *
	 * @return true when a storage key is present
	 */
	public boolean isStoredExternally() {
		// storageKey is normalised to null rather than "" by the compact constructor,
		// so a plain null check is sufficient and cannot be fooled by whitespace.
		return this.storageKey != null;
	}

	/**
	 * Whether the proof can be checked without trusting this system's arithmetic.
	 *
	 * <p>A reviewer's note cannot. This is what separates a defensible finding from an
	 * opinion that happens to be stored next to one.
	 */
	public boolean isIndependentlyVerifiable() {
		return this.evidenceType.isIndependentlyVerifiable();
	}

	/**
	 * Attaches a stored artifact, advancing the optimistic-lock version.
	 *
	 * <p>The storage pointer is the one column that may be filled in later, and only
	 * once. Everything else - type, title, source locator, digest - is fixed at
	 * registration, so the identity of a claim cannot be edited after a report has
	 * cited it.
	 *
	 * @param artifact pointer to the object stored behind this evidence row
	 * @param now the instant of the change, supplied by the caller
	 * @return a new evidence row carrying the storage key, at version + 1
	 * @throws ValidationException if the artifact is null or points at a different
	 *         object than this row already holds
	 */
	public Evidence withArtifact(EvidenceArtifact artifact, Instant now) {
		if (artifact == null) {
			throw new ValidationException("artifact must not be null");
		}
		// The immutability rule, and the one place it is enforced: an evidence row may
		// acquire its stored object once, but it may never be re-pointed at a different
		// one. Re-pointing would leave a reviewer holding a hash of content that is no
		// longer the content this row refers to - a record that verifies and is wrong.
		// Re-attaching the same key is allowed, because re-uploading identical bytes
		// under one key is the same evidence, not a new claim.
		if (this.storageKey != null && !this.storageKey.equals(artifact.storageKey())) {
			throw new ValidationException("evidence " + this.id
					+ " already points at a different stored object; evidence is immutable once stored");
		}
		// createdAt is carried over and version incremented, so an update is visibly a
		// later state of the same row rather than a replacement of it.
		return new Evidence(this.id, this.organizationId, this.evidenceType, this.title, this.description,
				this.sourceFileId, this.sourceRowNumber, artifact.storageKey(), this.contentHash, this.createdAt, now,
				this.version + 1);
	}

	/**
	 * Clears the storage pointer. Only meaningful when the object has actually been removed.
	 *
	 * <p>The pointer is cleared; the hash is not. Retaining the digest is what lets a
	 * later re-upload of the same bytes be recognised as the same content rather than
	 * as a new proof, and it keeps the record of what the object was even after the
	 * object itself has gone under a retention policy.
	 *
	 * @param now the instant of the change, supplied by the caller so the update is
	 *            reproducible under a fixed clock
	 * @return the same evidence with no storage key, or this instance when there was none
	 */
	public Evidence withoutStorage(Instant now) {
		// Idempotent by returning this rather than a copy with a bumped version: a
		// second call changes nothing, so making it look like an edit would train
		// callers to expect a version conflict where none occurred.
		if (this.storageKey == null) {
			return this;
		}
		return new Evidence(this.id, this.organizationId, this.evidenceType, this.title, this.description,
				this.sourceFileId, this.sourceRowNumber, null, this.contentHash, this.createdAt, now, this.version + 1);
	}

	/** Restates the content hash, advancing the version. Used when a stored object is re-verified. */
	public Evidence withContentHash(ContentHash newHash, Instant now) {
		if (newHash == null) {
			throw new ValidationException("contentHash must not be null");
		}
		// Deliberately not conditional on whether the hash actually changed. A
		// re-verification that finds a difference must be able to record the observed
		// digest even when the row is about to be flagged as tampered with, and
		// suppressing the version bump for a no-op would hide that a check ran.
		return new Evidence(this.id, this.organizationId, this.evidenceType, this.title, this.description,
				this.sourceFileId, this.sourceRowNumber, this.storageKey, newHash, this.createdAt, now,
				this.version + 1);
	}

	/**
	 * Single-line audit detail. Carries identifiers and coordinates only - never money.
	 *
	 * <p>Assembled from {@link #sourceLocator()} rather than from the raw columns so
	 * the audit line cannot disagree with the domain view of the same row, and shaped
	 * {@code key=value} joined by {@code |} so it survives a log parser that expects
	 * stable field names.
	 *
	 * @return a pipe-delimited summary carrying type, id, version, source and stored flag
	 */
	public String auditDetail() {
		return "type=" + this.evidenceType.code() + "|evidenceId=" + this.id + "|version=" + this.version + "|"
				+ (this.sourceLocator() == null ? "source=none" : "source=" + this.sourceLocator().describe())
				+ "|stored=" + (this.isStoredExternally() ? "true" : "false");
	}



}

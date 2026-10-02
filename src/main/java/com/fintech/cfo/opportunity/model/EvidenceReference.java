package com.fintech.cfo.opportunity.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.fintech.cfo.shared.domain.SourceReference;
import com.fintech.cfo.shared.exception.ValidationException;

/**
 * A pointer to one piece of supporting evidence, with a checksum.
 *
 * <p>This record deliberately holds a locator and a digest, never the artefact. The
 * evidence itself lives in object storage and the canonical source row lives in its
 * owning module; keeping a copy here would mean two systems could disagree about what
 * was seen, and a disagreement about an artefact is indistinguishable from a
 * disagreement about money.
 *
 * <p>{@code checksum} is required rather than optional because a locator alone is not
 * evidence: it says where something was, not what was there. With the digest, a reader
 * can prove the content behind a reported amount is the content that was signed off,
 * and a later re-read that produces a different digest is a detectable problem rather
 * than a silent substitution.
 *
 * @param evidenceId          identity of the stored artefact
 * @param evidenceType        what kind of artefact it is, in the owning module's terms
 * @param locator             where it can be retrieved from
 * @param checksum            digest of the content at the moment it was captured
 * @param capturedAt          when the artefact was captured, for freshness comparison
 * @param calculationResultId the result this evidence backs, when it backs one
 * @param source              the originating source row, for end-to-end lineage
 */
public record EvidenceReference(
		UUID evidenceId,
		String evidenceType,
		String locator,
		String checksum,
		Instant capturedAt,
		@Nullable UUID calculationResultId,
		@Nullable SourceReference source) {

	/** Width of {@code opportunity_findings.title} in V8, the longest evidence text. */
	static final int MAX_EVIDENCE_TYPE_LENGTH = 48;

	public EvidenceReference {
		// A locator is not evidence on its own, so the digest is required rather
		// than optional: without it a reader can prove where the artefact was, not
		// that it was the same artefact that was signed off.
		if (evidenceId == null) {
			throw new ValidationException("evidenceId must not be null");
		}
		evidenceType = requireText(evidenceType, "evidenceType", MAX_EVIDENCE_TYPE_LENGTH);
		// Not persisted in this module - it is the evidence module's storage key -
		// but bounded here so an unbounded string cannot be written into an
		// opportunity row as if it were a document.
		locator = requireText(locator, "locator", 1024);
		checksum = requireText(checksum, "checksum", 128);
		if (capturedAt == null) {
			throw new ValidationException("capturedAt must not be null");
		}
	}

	/**
	 * Whether the evidence can be traced all the way back to an ingested row.
	 *
	 * @return true when a source reference is attached, false when the artefact
	 *         stands alone
	 */
	public boolean hasSourceLineage() {
		return this.source != null;
	}

	private static String requireText(String value, String field, int maxLength) {
		Objects.requireNonNull(value, field + " must not be null");
		String trimmed = value.trim();
		if (trimmed.isEmpty()) {
			throw new ValidationException(field + " must not be blank");
		}
		if (trimmed.length() > maxLength) {
			throw new ValidationException(field + " must not exceed " + maxLength + " characters");
		}
		return trimmed;
	}

}
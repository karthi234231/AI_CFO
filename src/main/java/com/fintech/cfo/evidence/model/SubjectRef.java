package com.fintech.cfo.evidence.model;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.fintech.cfo.evidence.enums.SourceType;
import com.fintech.cfo.shared.exception.ValidationException;

/**
 * A typed pointer at any subject, wherever it is owned.
 *
 * <p>The pair V7 stores as {@code from_type}/{@code from_id} in
 * {@code evidence_references} and as {@code subject_type}/{@code subject_id} in
 * {@code evidence_snapshots}. Wrapping it once means a lookup can never mix a subject
 * id with the wrong subject type, which is the mistake that turns "the evidence for
 * this invoice" into "some evidence, found by id".
 *
 * <p>{@link SourceType} is open by design, so a subject owned by another module -
 * an investigation, an opportunity - can be referenced without this module importing
 * that module. Tenancy is carried by the enclosing row, never here: a subject
 * reference on its own names nothing until it is read through an
* {@link com.fintech.cfo.shared.domain.OrganizationId}-scoped query.
 *
 * @param subjectType the kind of thing referred to, from an open vocabulary
 * @param subjectId   its identifier, meaningful only together with the type
 */
public record SubjectRef(SourceType subjectType, UUID subjectId) {

	/**
	 * Compact constructor: the pair is only a pointer when both halves are present.
	 *
	 * <p>There is deliberately no tenancy check here. This type names a subject, not a
	 * row, and whether that subject exists and belongs to the caller is a question
	 * only a tenant-scoped query can answer.
	 */
	public SubjectRef {
		if (subjectType == null) {
			throw new ValidationException("subjectType must not be null");
		}
		if (subjectId == null) {
			throw new ValidationException("subjectId must not be null");
		}
	}

	/** The fixed hop of the chain this reference is, or {@code null} for a foreign subject. */
	public @Nullable SourceType wellKnownType() {
		// Returning the same value or null rather than a boolean, so a caller that has
		// already resolved the type does not pay for a second lookup - and so that the
		// transition from "unknown subject" to "known subject" is visible in the type
		// rather than flattened into a flag.
		return this.subjectType.isWellKnown() ? this.subjectType : null;
	}

	/**
	 * @return the {@code TYPE:id} form used in audit lines and error messages; carries
	 *         no attribute of the subject itself, so it is safe to log
	 */
	@Override
	public String toString() {
		return this.subjectType.code() + ":" + this.subjectId;
	}

}

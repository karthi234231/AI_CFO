package com.fintech.cfo.opportunity.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.fintech.cfo.shared.domain.Money;
import com.fintech.cfo.shared.domain.OrganizationId;
import com.fintech.cfo.shared.domain.SourceReference;
import com.fintech.cfo.shared.exception.ValidationException;

/**
 * One affected transaction and the amount this opportunity claims against it.
 *
 * <p>Maps column-for-column onto {@code opportunity_impacts} in
 * {@code V8__create_opportunities.sql}, plus {@code external_reference} and a source
 * pointer, which are the fields that let a reader get from the row to the ingested
 * record without a second query against another module.
 *
 * <p>{@code createdAt} is carried but not derived: it comes from the caller's clock,
 * so a detection re-run over the same inputs produces the same rows. The same applies
 * to {@link OpportunityFinding}, {@link OpportunityReview} and
 * {@link OpportunityLifecycleEvent}.
 *
 * @param id                 row identity
 * @param organizationId     tenant boundary, never taken from a request
 * @param opportunityId      the opportunity this row contributes to
 * @param entityType         kind of canonical record the money sits on
 * @param entityId           its identity
 * @param amount             this row's signed share of the net impact
 * @param externalReference  the human-facing identifier, such as an invoice number
 * @param source             the ingested row behind it
 * @param createdAt          when the contribution was recorded
 */
public record OpportunityImpact(
		UUID id,
		OrganizationId organizationId,
		UUID opportunityId,
		String entityType,
		UUID entityId,
		Money amount,
		@Nullable String externalReference,
		@Nullable SourceReference source,
		Instant createdAt) {

	public OpportunityImpact {
		// Every null is refused here rather than at the call site: a contribution
		// row missing its tenancy cannot be detected later, and a missing amount
		// would silently count as zero in an aggregate.
		if (id == null) {
			throw new ValidationException("opportunity impact id must not be null");
		}
		if (organizationId == null) {
			throw new ValidationException("opportunity impact organizationId must not be null");
		}
		if (opportunityId == null) {
			throw new ValidationException("opportunity impact opportunityId must not be null");
		}
		// Trimmed and length-checked against the V8 column, so an over-long entity
		// type is refused here rather than truncated by the database.
		entityType = requireText(entityType, "entityType", AffectedTransactionRef.MAX_ENTITY_TYPE_LENGTH);
		if (entityId == null) {
			throw new ValidationException("opportunity impact entityId must not be null");
		}
		if (amount == null) {
			throw new ValidationException("opportunity impact amount must not be null");
		}
		// Optional, but never blank: an empty string would be stored as a reference
		// that resolves to nothing.
		if (externalReference != null) {
			externalReference = externalReference.trim();
			if (externalReference.isEmpty()) {
				throw new ValidationException("externalReference must be null or non-blank");
			}
		}
		if (createdAt == null) {
			throw new ValidationException("opportunity impact createdAt must not be null");
		}
	}

	/**
	 * Turns a detector's contribution into a stored row, assigning the identity,
	 * tenancy and timestamp this module owns rather than accepting them from a caller.
	 *
	 * @param reference      the detector contribution: amount, entity and source
	 * @param id             identity for the new row
	 * @param organizationId tenant the row belongs to, never taken from a request
	 * @param opportunityId  the opportunity the row contributes to
	 * @param createdAt      when the contribution was recorded
	 * @return a stored-form contribution row
	 * @throws NullPointerException if {@code reference} is null
	 */
	public static OpportunityImpact from(AffectedTransactionRef reference, UUID id, OrganizationId organizationId,
			UUID opportunityId, Instant createdAt) {
		Objects.requireNonNull(reference, "reference must not be null");
		return new OpportunityImpact(id, organizationId, opportunityId, reference.entityType(), reference.entityId(),
				reference.contribution(), reference.externalReference(), reference.source(), createdAt);
	}

	/** Whether this contribution carries the money in the same direction as the record. */
	public boolean isFavourable() {
		// Sign convention, stated here because nothing else in this module fixes it:
		// the amount is the detector's net variance share, and
		// financialtruth.Variance defines variance as actual - expected, so a
		// positive amount is money the customer overpaid and is recoverable. This
		// predicate therefore selects the negative direction. Whoever implements
		// detection must keep the two consistent - a contribution sign flip here
		// would turn a recoverable overpayment into an undercharge claim.
		return this.amount.isNegative();
	}

	private static String requireText(String value, String field, int maxLength) {
		// Trimming is normalisation, not leniency: it means a value typed with
		// surrounding whitespace is stored once and compares equal everywhere.
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
package com.fintech.cfo.opportunity.model;

import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.fintech.cfo.shared.domain.Money;
import com.fintech.cfo.shared.domain.SourceReference;
import com.fintech.cfo.shared.exception.ValidationException;

/**
 * One affected transaction and this opportunity's claim against it, as supplied by
 * whatever detected the deviation.
 *
 * <p>This is the module's <em>input</em> form. It carries no identity, tenancy or
 * creation time, because those are not the detector's to decide;
 * {@link OpportunityImpact#from} assigns them. The split exists so that the authority
 * over the persisted rows stays inside this module: a caller cannot choose an
 * {@code organizationId} for a contribution it detected, which is the same reasoning
 * that keeps an organization id out of request bodies.
 *
 * <p>The contributions of one detection must partition the net impact exactly - see
 * {@code OpportunityDetectionService}, which checks it. Allowing a residual would mean
 * the stored total and the sum of its rows could differ by an amount nobody
 * attributed, which is the shape of every unexplained-variance balance ever reported.
 * A rounding residual must therefore be attributed to one row rather than left over.
 *
 * <p>No currency is carried on this record. A contribution is denominated in the
 * opportunity's currency, and {@code Money} enforces that on every operation, so a
 * mixed-currency detection cannot silently add like amounts together - it fails at
 * the first subtraction instead.
 *
 * @param entityType         kind of canonical record the money sits on, for example an
 *                           invoice line
 * @param entityId           its identity
 * @param contribution       this record's signed share of the net impact, in the
 *                           opportunity's currency
 * @param externalReference  the human-facing identifier, such as an invoice number
 * @param source             the ingested row behind it, for end-to-end traceability
 */
public record AffectedTransactionRef(
		String entityType,
		UUID entityId,
		Money contribution,
		@Nullable String externalReference,
		@Nullable SourceReference source) {

	/** Width of {@code opportunity_impacts.entity_type} in V8. */
	public static final int MAX_ENTITY_TYPE_LENGTH = 64;

	public AffectedTransactionRef {
		// Checked against the V8 width here so a detector that names an entity type
		// longer than the column is rejected at the point it is described, not by a
		// truncation at write time.
		entityType = requireText(entityType, "entityType", MAX_ENTITY_TYPE_LENGTH);
		if (entityId == null) {
			throw new ValidationException("entityId must not be null");
		}
		if (contribution == null) {
			throw new ValidationException("contribution must not be null");
		}
		// Null when the source row has no human-facing identifier; blank is refused,
		// because a blank reference reads as a reference that was lost in transit.
		if (externalReference != null) {
			externalReference = externalReference.trim();
			if (externalReference.isEmpty()) {
				throw new ValidationException("externalReference must be null or non-blank");
			}
		}
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
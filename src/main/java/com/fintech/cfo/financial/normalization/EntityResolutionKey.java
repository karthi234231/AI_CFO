package com.fintech.cfo.financial.normalization;

import java.io.Serializable;

import com.fintech.cfo.shared.domain.OrganizationId;
import com.fintech.cfo.shared.exception.ValidationException;

/**
 * Identity under which an upstream record is deduplicated into a canonical
 * financial row.
 *
 * <p>Mirrors the partial unique index declared in V4 on both
 * {@code ux_customers_org_source_key} and {@code ux_products_org_source_key}:
 * {@code (organization_id, source_system, external_key) WHERE external_key IS
 * NOT NULL}. The tenant is therefore part of the key, not a filter applied
 * around it - two tenants may legitimately carry the same external identifier,
 * so a key built without {@code organization_id} would be wrong rather than
 * merely imprecise.
 *
 * <p>All three components are required. A row with no external key falls outside
 * the index entirely, which is why {@code Customer} and {@code Product} report a
 * {@code null} resolution key rather than manufacturing one for that case.
 * Widths are not re-checked here: the producing row has already bounded
 * {@code sourceSystem} to {@code VARCHAR(64)} and {@code externalKey} to
 * {@code VARCHAR(255)}.
 */
public record EntityResolutionKey(
		OrganizationId organizationId,
		String sourceSystem,
		String externalKey) implements Serializable {

	public EntityResolutionKey {
		// All three components are mandatory. There is deliberately no
		// "no external key" representation: a record with no external key falls
		// outside the partial unique index, so it has no key, and its owner
		// (Customer, Product, FinancialTransaction) reports null from
		// resolutionKey() instead of manufacturing one.
		if (organizationId == null) {
			throw new ValidationException("organizationId must not be null");
		}
		// Trimmed, so a key built from a padded VARCHAR and one built from a trimmed
		// string are the same key. Case is NOT normalised: the source system code is
		// an exact identifier, and folding case would merge two identifiers the
		// upstream system treats as distinct.
		sourceSystem = requireText(sourceSystem, "sourceSystem");
		externalKey = requireText(externalKey, "externalKey");
	}

	/**
	 * Builds the key for one upstream record.
	 *
	 * <p>A factory rather than a bare constructor call at the call sites, so the
	 * compact constructor stays the single place that enforces the invariant the
	 * index already enforces in the database.
	 *
	 * @param organizationId owning tenant; part of the key, not a filter around it
	 * @param sourceSystem   persisted source system code, matching the
	 *                       {@code source_system} column
	 * @param externalKey    the upstream identifier; must not be null or blank,
	 *                       because a blank key would deduplicate every such record
	 *                       together
	 * @return the resolution key for that record
	 * @throws ValidationException if any component is null or blank
	 */
	public static EntityResolutionKey of(OrganizationId organizationId, String sourceSystem,
			String externalKey) {
		return new EntityResolutionKey(organizationId, sourceSystem, externalKey);
	}

	/**
	 * Trims a key component and refuses it when nothing is left.
	 *
	 * <p>Blank is rejected rather than accepted, because a blank external key is
	 * indistinguishable across rows: every unkeyed record would collapse into one
	 * under the unique index.
	 *
	 * @param value  the component as received
	 * @param field  component name, used only for the error message
	 * @return the trimmed component
	 * @throws ValidationException if {@code value} is null or blank
	 */
	private static String requireText(String value, String field) {
		if (value == null) {
			throw new ValidationException(field + " must not be null");
		}
		String trimmed = value.trim();
		if (trimmed.isEmpty()) {
			throw new ValidationException(field + " must not be blank");
		}
		return trimmed;
	}

}

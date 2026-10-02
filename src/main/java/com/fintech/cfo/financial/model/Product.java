package com.fintech.cfo.financial.model;

import java.io.Serializable;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.fintech.cfo.financial.enums.SourceSystem;
import com.fintech.cfo.financial.normalization.EntityResolutionKey;
import com.fintech.cfo.shared.domain.CurrencyCode;
import com.fintech.cfo.shared.domain.OrganizationId;
import com.fintech.cfo.shared.domain.SourceReference;
import com.fintech.cfo.shared.exception.ValidationException;
import com.fintech.cfo.shared.validation.Preconditions;

/**
 * Immutable mirror of the V4 {@code products} row.
 *
 * <p>Deduplicated by {@code ux_products_org_source_key} exactly as
 * {@link Customer} is, so the resolution key is nullable in the same way and for
 * the same reason. {@code currency} is the price currency of the catalogue
 * entry; it never converts a line that was billed in another currency.
 */
public record Product(
		UUID id,
		OrganizationId organizationId,
		@Nullable String externalKey,
		@Nullable String sku,
		String name,
		@Nullable String description,
		@Nullable String unitOfMeasure,
		CurrencyCode currency,
		SourceReference source,
		long version) implements Serializable {

	/** {@code products.external_key VARCHAR(255)}. */
	public static final int MAX_EXTERNAL_KEY_LENGTH = 255;

	/** {@code products.sku VARCHAR(120)}. */
	public static final int MAX_SKU_LENGTH = 120;

	/** {@code products.name VARCHAR(255)}. */
	public static final int MAX_NAME_LENGTH = 255;

	/** {@code products.description VARCHAR(2000)}. */
	public static final int MAX_DESCRIPTION_LENGTH = 2000;

	/** {@code products.unit_of_measure VARCHAR(32)}. */
	public static final int MAX_UNIT_OF_MEASURE_LENGTH = 32;

	public Product {
	// NOTE: the id null-check below is indented with a single tab while the rest of
	// this constructor uses two. Cosmetic only; left as-is so the diff stays
	// comment-only.
	Preconditions.requireNonNull(id, "id");
		// Tenancy is a constructor invariant, so a product can never be addressed
		// by an unscoped lookup.
		Preconditions.requireNonNull(organizationId, "organizationId");
		// NOTE: sku is declared @Nullable and products.sku is nullable in V4, yet
		// this first pass calls requireText on it. Any product constructed without a
		// SKU is therefore rejected here before the optionalText pass below ever
		// runs. The ordering is intentional in neither direction - flagging it
		// rather than changing it, since a SKU-less catalogue entry is a real input.
		sku = Preconditions.requireText(sku, "sku", MAX_SKU_LENGTH);
		// The display name is mandatory and is what a contract term is matched
		// against, so it is never defaulted to a placeholder.
		name = Preconditions.requireText(name, "name", MAX_NAME_LENGTH);
		// externalKey may be null; that is the "outside the partial unique index,
		// therefore not deduplicated" case, which resolutionKey() reports as null.
		externalKey = Preconditions.optionalText(externalKey, "externalKey", MAX_EXTERNAL_KEY_LENGTH);
		// Re-applied as optional so the trimmed/null-normalised value is what the
		// record ends up holding.
		sku = Preconditions.optionalText(sku, "sku", MAX_SKU_LENGTH);
		description = Preconditions.optionalText(description, "description", MAX_DESCRIPTION_LENGTH);
		// Left null rather than defaulted to a piece count: the source never
		// asserted one, and inventing it would make a rendered contract term wrong.
		unitOfMeasure = Preconditions.optionalText(unitOfMeasure, "unitOfMeasure", MAX_UNIT_OF_MEASURE_LENGTH);
		// Catalogue price currency. Informational only: it is never a conversion
		// authority for a billed invoice line, whose currency comes from the line.
		Preconditions.requireNonNull(currency, "currency");
		// Lineage is mandatory: a catalogue entry nothing can trace is not evidence.
		Preconditions.requireNonNull(source, "source");
		if (version < 0) {
			throw new ValidationException("version must not be negative");
		}
	}

	/**
	 * Key this product is deduplicated by, or {@code null} without an external key.
	 *
	 * <p>Mirrors {@code ux_products_org_source_key (organization_id, source_system,
	 * external_key) WHERE external_key IS NOT NULL}. Note that {@code sku} is
	 * deliberately absent from the key even though {@code ix_products_org_sku}
	 * exists: the schema keeps two rows sharing a SKU apart, so merging on it here
	 * would be stricter than the database and would reprice historic invoice lines.
	 *
	 * @return the tenant/source/external-key triple, or {@code null} when
	 *         {@code externalKey} is absent
	 */
	public @Nullable EntityResolutionKey resolutionKey() {
		if (this.externalKey == null) {
			return null;
		}
		return EntityResolutionKey.of(this.organizationId, sourceSystem().code(), this.externalKey);
	}

	/**
	 * Source system this product was resolved from.
	 *
	 * @return the lineage's source system as a closed-set constant
	 * @throws ValidationException if the lineage carries a code outside
	 *                              {@code SourceSystem}
	 */
	public SourceSystem sourceSystem() {
		return SourceSystem.fromCode(this.source.sourceSystem());
	}



}

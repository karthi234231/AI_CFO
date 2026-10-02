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
 * Immutable mirror of the V4 {@code customers} row.
 *
 * <p>{@code external_key} is nullable because {@code ux_customers_org_source_key}
 * is a partial unique index: a customer with no external key is not
 * deduplicated by the database and therefore must not claim a resolution key
 * either. {@link #resolutionKey()} returns {@code null} in exactly that case,
 * which keeps the in-memory rule identical to the schema rule.
 *
 * <p>{@code created_at} / {@code updated_at} are deliberately absent: V4 gives
 * them database defaults and the persistence pass will own them through JPA
 * auditing. Adding them here would mean calling {@code now()} inside the domain,
 * which the determinism rule forbids.
 */
public record Customer(
		UUID id,
		OrganizationId organizationId,
		@Nullable String externalKey,
		String name,
		@Nullable String email,
		@Nullable String taxIdentifier,
		CurrencyCode currency,
		SourceReference source,
		long version) implements Serializable {

	/** {@code customers.name VARCHAR(255)}. */
	public static final int MAX_NAME_LENGTH = 255;

	/** {@code customers.external_key VARCHAR(255)}. */
	public static final int MAX_EXTERNAL_KEY_LENGTH = 255;

	/** {@code customers.tax_identifier VARCHAR(120)}. */
	public static final int MAX_TAX_IDENTIFIER_LENGTH = 120;

	public Customer {
		Preconditions.requireNonNull(id, "id");
		// Tenancy is a constructor invariant, so no accessor can hand out a
		// customer whose repository lookup would be unscoped.
		Preconditions.requireNonNull(organizationId, "organizationId");
		// The display name is the only mandatory free-text field: an AR statement
		// row with no counterparty name cannot be presented to a user at all.
		name = Preconditions.requireText(name, "name", MAX_NAME_LENGTH);
		// externalKey is trimmed but may be null: null is the signal that this row
		// falls outside ux_customers_org_source_key and therefore must not be
		// deduplicated against anything.
		externalKey = Preconditions.optionalText(externalKey, "externalKey", MAX_EXTERNAL_KEY_LENGTH);
		// 320 is the customers.email column width and also the RFC 5321 maximum
		// path length, so the bound is the schema's and the standard's at once.
		email = Preconditions.optionalText(email, "email", 320);
		// Tax identifiers (GSTIN, VAT, PAN) are deliberately not reformatted: the
		// shapes differ by jurisdiction and normalising them would break the very
		// values the tax team searches by.
		taxIdentifier = Preconditions.optionalText(taxIdentifier, "taxIdentifier", MAX_TAX_IDENTIFIER_LENGTH);
		// A customer carries a currency because it is the default currency of its
		// invoices. It never converts an amount: Money enforces that at the point
		// of arithmetic instead.
		Preconditions.requireNonNull(currency, "currency");
		// Lineage is mandatory. A canonical customer that cannot be traced to the
		// uploaded row it came from is not evidence of anything.
		Preconditions.requireNonNull(source, "source");
		// Optimistic-locking column, managed by the persistence pass.
		if (version < 0) {
			throw new ValidationException("version must not be negative");
		}
	}

	/**
	 * Key this customer is deduplicated by, or {@code null} when the source
	 * supplied no external key.
	 *
	 * <p>Mirrors {@code ux_customers_org_source_key (organization_id,
	 * source_system, external_key) WHERE external_key IS NOT NULL}: the tenant is
	 * part of the key, so two tenants may legitimately carry the same external
	 * identifier.
	 */
	public @Nullable EntityResolutionKey resolutionKey() {
		// Null, not a manufactured key: the partial index does not cover this row,
		// so any key built here would make the in-memory dedup rule stricter than
		// the database one and would merge two genuinely different customers.
		if (this.externalKey == null) {
			return null;
		}
		// sourceSystem() rather than source.sourceSystem() directly, so an
		// unrecognised lineage code fails loudly instead of producing a key that no
		// stored row can ever match.
		return EntityResolutionKey.of(this.organizationId, sourceSystem().code(), this.externalKey);
	}

	/**
	 * Source system this customer was resolved from.
	 *
	 * @return the lineage's source system as a closed-set constant
	 * @throws ValidationException if the lineage carries a code outside
	 *                              {@code SourceSystem}
	 */
	public SourceSystem sourceSystem() {
		return SourceSystem.fromCode(this.source.sourceSystem());
	}



}

package com.fintech.cfo.financial.dto;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * API representation of a canonical {@code Customer}.
 *
 * <p>Currency is exposed as an ISO-4217 string rather than as a
 * {@code CurrencyCode} object so the wire format stays a plain scalar and does
 * not change shape when new currencies are added.
 *
 * @param id             canonical customer id
 * @param organizationId owning tenant
 * @param externalKey    source-supplied identifier, may be absent
 * @param name           display name
 * @param email          contact email, may be absent
 * @param taxIdentifier  tax registration identifier, may be absent
 * @param currency       ISO-4217 currency code
 * @param sourceSystem   upstream system code
 * @param sourceRecordId lineage pointer back to the source record
 * @param sourceFileId   source file, may be absent
 * @param sourceRowNumber source row within the file, may be absent
 * @param version        optimistic-locking version
 */
// externalKey is nullable for the same reason it is nullable in the domain: it is
// the third component of a partial unique index, so its absence means "not
// deduplicated by source", not "unknown". A client must not treat a null
// externalKey as an invitation to look this customer up by name.
// version is surfaced so a client performing an edit can send it back and have the
// write rejected if someone else changed the row in between.
public record CustomerResponse(
		UUID id,
		UUID organizationId,
		@Nullable String externalKey,
		String name,
		@Nullable String email,
		@Nullable String taxIdentifier,
		String currency,
		String sourceSystem,
		String sourceRecordId,
		@Nullable String sourceFileId,
		@Nullable Long sourceRowNumber,
		long version) {
}

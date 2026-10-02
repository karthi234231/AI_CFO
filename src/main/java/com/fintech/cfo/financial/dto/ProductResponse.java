package com.fintech.cfo.financial.dto;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * API representation of a canonical {@code Product}.
 *
 * @param id              canonical product id
 * @param organizationId  owning tenant
 * @param externalKey     source-supplied identifier, may be absent
 * @param sku             stock keeping unit, may be absent
 * @param name            display name
 * @param description     free-text description, may be absent
 * @param unitOfMeasure   unit symbol, may be absent
 * @param currency        ISO-4217 currency code of the catalogue price
 * @param sourceSystem    upstream system code
 * @param sourceRecordId  lineage pointer back to the source record
 * @param sourceFileId    source file, may be absent
 * @param sourceRowNumber source row within the file, may be absent
 * @param version         optimistic-locking version
 */
// sku and externalKey are independently nullable: the SKU is a catalogue label
// and the external key is the dedup component, and a row may have either, both or
// neither. currency is the catalogue price currency and is informational only -
// it is never applied to a billed invoice line, because this system holds no rate.
public record ProductResponse(
		UUID id,
		UUID organizationId,
		@Nullable String externalKey,
		@Nullable String sku,
		String name,
		@Nullable String description,
		@Nullable String unitOfMeasure,
		String currency,
		String sourceSystem,
		String sourceRecordId,
		@Nullable String sourceFileId,
		@Nullable Long sourceRowNumber,
		long version) {
}

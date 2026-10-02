package com.fintech.cfo.contract.dto;

import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.fintech.cfo.contract.model.Contract;
import com.fintech.cfo.shared.domain.CurrencyCode;

/**
 * Wire representation of a contract.
 *
 * <p>Built from the value type, never the reverse, so the API can never expose a
 * persistence concern. Closed sets are carried as their V5 code strings: those
 * columns are the contract between this system and its consumers, and a
 * serialised enum object would be a second, undocumented one.
 *
 * <p>No amounts appear here because {@code contracts} holds none; prices live on
 * {@link PricingTermResponse}. The window is exposed as the two raw dates plus
 * {@code openEnded}, so a client does not have to infer the third from a null.
 */
public record ContractResponse(
		UUID id,
		UUID organizationId,
		@Nullable UUID customerId,
		String contractNumber,
		@Nullable String title,
		String status,
		CurrencyCode currency,
		LocalDate effectiveFrom,
		@Nullable LocalDate effectiveTo,
		boolean openEnded,
		@Nullable Instant signedAt,
		@Nullable String documentReference,
		long version,
		@Nullable Boolean canSupplyTermsOnAsOfDate) implements Serializable {

	public static ContractResponse from(Contract contract) {
		return from(contract, null);
	}

	/**
	 * @param asOfDate date to report term applicability for, or null to omit it
	 */
	public static ContractResponse from(Contract contract, @Nullable LocalDate asOfDate) {
		return new ContractResponse(contract.id(), contract.organizationId().value(), contract.customerId(),
				contract.contractNumber(), contract.title(), contract.status().code(), contract.currency(),
				contract.effectiveFrom(), contract.effectiveTo(), contract.effectiveTo() == null, contract.signedAt(),
				contract.documentReference(), contract.version(),
				asOfDate == null ? null : contract.canSupplyTermsOn(asOfDate));
	}

}

package com.fintech.cfo.contract.dto;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.fintech.cfo.contract.model.PricingTerm;
import com.fintech.cfo.shared.domain.CurrencyCode;
import com.fintech.cfo.shared.domain.Money;

/**
 * Wire representation of a price line.
 *
 * <p>The bounds travel with the price rather than only in the contract document,
 * because a client that has to ask for them before it can charge cannot display
 * whether a price is clamped. Amounts keep the {@code NUMERIC(20,6)} scale of
 * {@code pricing_terms}; a {@code double} in this field would already have lost
 * cents before the invoice was rendered.
 *
 * @param inForceOnAsOfDate whether this price line covers the date the response is
 * being served for, null when no date is in question
 */
public record PricingTermResponse(
		UUID id,
		UUID organizationId,
		UUID contractId,
		@Nullable UUID productId,
		@Nullable UUID customerId,
		String pricingType,
		@Nullable Money unitPrice,
		@Nullable Money priceMinimum,
		@Nullable Money priceMaximum,
		CurrencyCode currency,
		LocalDate effectiveFrom,
		LocalDate effectiveTo,
		boolean openEnded,
		int termVersion,
		long version,
		@Nullable Boolean inForceOnAsOfDate) implements Serializable {

	public static PricingTermResponse from(PricingTerm term) {
		return from(term, null);
	}

	public static PricingTermResponse from(PricingTerm term, @Nullable LocalDate asOfDate) {
		return new PricingTermResponse(term.id(), term.organizationId().value(), term.contractId(), term.productId(),
				term.customerId(), term.pricingType().code(), term.unitPrice(), term.priceMinimum(),
				term.priceMaximum(), term.currency(), term.effectiveWindow().effectiveFrom(),
				term.effectiveWindow().effectiveTo(), term.effectiveWindow().isOpenEnded(), term.termVersion(),
				term.version(), asOfDate == null ? null : term.effectiveWindow().contains(asOfDate));
	}

	public boolean isContractWide() {
		return this.productId == null && this.customerId == null;
	}

	public boolean publishesPrice() {
		return this.unitPrice != null;
	}

}
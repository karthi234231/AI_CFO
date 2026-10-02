package com.fintech.cfo.contract.dto;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.fintech.cfo.contract.model.DiscountTerm;
import com.fintech.cfo.shared.domain.CurrencyCode;
import com.fintech.cfo.shared.domain.Money;

/**
 * Wire representation of a discount line.
 *
 * <p>{@code discountValue} is exposed as the raw {@code NUMERIC(20,6)} decimal
 * exactly as stored, not pre-multiplied into a percentage string. For
 * {@code PERCENTAGE} the unit is percentage points and for {@code FIXED_AMOUNT} it
 * is a currency amount, and {@code discountType} is what tells a client which.
 * Rescaling the value here would destroy the correspondence with the stored row
 * that an audit re-read depends on.
 *
 * @param currency nullable in V5 and for a percentage discount; non-null for a
 * fixed-amount discount and whenever a cap is present
 * @param inForceOnAsOfDate whether this line covers the date the response is being
 * served for, null when no date is in question
 */
public record DiscountTermResponse(
		UUID id,
		UUID organizationId,
		UUID contractId,
		@Nullable UUID productId,
		@Nullable UUID customerId,
		String discountType,
		BigDecimal discountValue,
		@Nullable Money maxDiscountAmount,
		@Nullable CurrencyCode currency,
		LocalDate effectiveFrom,
		LocalDate effectiveTo,
		boolean openEnded,
		int termVersion,
		long version,
		@Nullable Boolean inForceOnAsOfDate) implements Serializable {

	public static DiscountTermResponse from(DiscountTerm term) {
		return from(term, null);
	}

	public static DiscountTermResponse from(DiscountTerm term, @Nullable LocalDate asOfDate) {
		return new DiscountTermResponse(term.id(), term.organizationId().value(), term.contractId(), term.productId(),
				term.customerId(), term.discountType().code(), term.discountValue(), term.maxDiscountAmount(),
				term.currency(), term.effectiveWindow().effectiveFrom(), term.effectiveWindow().effectiveTo(),
				term.effectiveWindow().isOpenEnded(), term.termVersion(), term.version(),
				asOfDate == null ? null : term.effectiveWindow().contains(asOfDate));
	}

	public boolean isContractWide() {
		return this.productId == null && this.customerId == null;
	}

}
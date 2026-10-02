package com.fintech.cfo.contract.model;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.fintech.cfo.contract.enums.DiscountType;
import com.fintech.cfo.shared.domain.CurrencyCode;
import com.fintech.cfo.shared.domain.Money;
import com.fintech.cfo.shared.domain.OrganizationId;
import com.fintech.cfo.shared.exception.ValidationException;

/**
 * Immutable mirror of the V5 {@code discount_terms} row.
 *
 * <p>{@code discount_value} has no currency of its own and its unit is decided
 * entirely by {@code discount_type}:
 *
 * <ul>
 * <li>{@link DiscountType.Percentage} - a percentage of the amount being
 * discounted, so no currency is needed and {@code currency} is free to be
 * null.</li>
 * <li>{@link DiscountType.FixedAmount} - an absolute amount, which cannot be
 * expressed without a currency, so {@code currency} is required here even though
 * the column is nullable in V5.</li>
 * </ul>
 *
 * <p>{@code max_discount_amount} is the contractual ceiling on the discount as
 * granted and is always an amount, so it requires {@code currency} too.
 *
 * @param productId nullable in V5: a contract-wide default discount
 * @param customerId nullable in V5: a contract-wide default discount
 */
public record DiscountTerm(
		UUID id,
		OrganizationId organizationId,
		UUID contractId,
		@Nullable UUID productId,
		@Nullable UUID customerId,
		DiscountType discountType,
		BigDecimal discountValue,
		@Nullable Money maxDiscountAmount,
		@Nullable CurrencyCode currency,
		EffectiveWindow effectiveWindow,
		int termVersion,
		long version) implements ScopedTerm, Serializable {

	public DiscountTerm {
		Objects.requireNonNull(id, "id must not be null");
		Objects.requireNonNull(organizationId, "organizationId must not be null");
		Objects.requireNonNull(contractId, "contractId must not be null");
		Objects.requireNonNull(discountType, "discountType must not be null");
		Objects.requireNonNull(discountValue, "discountValue must not be null");
		Objects.requireNonNull(effectiveWindow, "effectiveWindow must not be null");
		if (termVersion < ContractTerm.MIN_TERM_VERSION) {
			throw new ValidationException("termVersion must be at least " + ContractTerm.MIN_TERM_VERSION);
		}
		if (version < 0) {
			throw new ValidationException("version must not be negative");
		}
		if (discountValue.signum() <= 0) {
			throw new ValidationException("discountValue must be greater than zero");
		}
		if (!discountType.isPercentageValid(discountValue)) {
			throw new ValidationException("a PERCENTAGE discountValue must be in (0, 100]");
		}
		if (discountType.isMonetary() && currency == null) {
			throw new ValidationException("a FIXED_AMOUNT discount requires a currency");
		}
		if (maxDiscountAmount != null) {
			if (currency == null) {
				throw new ValidationException("maxDiscountAmount requires a currency");
			}
			if (!maxDiscountAmount.currency().equals(currency)) {
				throw new ValidationException("maxDiscountAmount must be in the discount term currency");
			}
			if (maxDiscountAmount.isNegative()) {
				throw new ValidationException("maxDiscountAmount must not be negative");
			}
		}
	}

	/**
	 * Convenience taking the raw V5 date columns.
	 */
	public DiscountTerm(UUID id, OrganizationId organizationId, UUID contractId, @Nullable UUID productId,
			@Nullable UUID customerId, DiscountType discountType, BigDecimal discountValue,
			@Nullable Money maxDiscountAmount, @Nullable CurrencyCode currency, LocalDate effectiveFrom,
			@Nullable LocalDate effectiveTo, int termVersion) {
		this(id, organizationId, contractId, productId, customerId, discountType, discountValue, maxDiscountAmount,
				currency, EffectiveWindow.of(effectiveFrom, effectiveTo), termVersion, 0L);
	}

	@Override
	public LocalDate effectiveFrom() {
		return this.effectiveWindow.effectiveFrom();
	}

	/**
	 * The absolute amount this term grants, for a fixed-amount discount. Null for a
	 * percentage discount, whose value only becomes an amount once an amount to
	 * discount has been supplied.
	 */
	@Nullable
	public Money fixedDiscountAmount() {
		// Null for a percentage, whose value only becomes an amount once an amount
		// to discount exists. Returning null rather than zero keeps "this term has no
		// absolute amount" distinct from "this term grants nothing".
		return this.discountType.isMonetary() ? Money.of(this.discountValue, this.currency) : null;
	}

	@Override
	public String canonical() {
		return CanonicalText.join(this.id, this.organizationId, this.contractId, this.productId, this.customerId,
				this.discountType.code(), CanonicalText.decimal(this.discountValue),
				CanonicalText.money(this.maxDiscountAmount),
				CanonicalText.text(this.currency == null ? null : this.currency.value()),
				this.effectiveWindow.canonical(), this.termVersion, this.version);
	}

}

package com.fintech.cfo.contract.model;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.fintech.cfo.contract.enums.PricingType;
import com.fintech.cfo.shared.domain.CurrencyCode;
import com.fintech.cfo.shared.domain.Money;
import com.fintech.cfo.shared.domain.OrganizationId;
import com.fintech.cfo.shared.exception.ValidationException;

/**
 * Immutable mirror of the V5 {@code pricing_terms} row.
 *
 * <p>Three invariants are enforced here rather than at a boundary, because each
 * one, if it slips through, quietly changes a monetary result:
 *
 * <ul>
 * <li>every {@link Money} component is already in this row's {@code currency}.
 * V5 has no per-column currency, so a price line read as {@code Money} must be
 * pinned to {@code currency} or the mismatch is invisible.</li>
 * <li>{@code price_minimum <= price_maximum} when both are present, otherwise
 * clamping has no consistent answer.</li>
 * <li>the price and its bounds are non-negative. A negative bound is not a
 * price.</li>
 * </ul>
 *
 * <p>{@code unit_price} is nullable in V5 and legitimately stays null for a
 * {@link PricingType.Tiered} row that publishes only a band.
 *
 * @param productId nullable in V5: a contract-wide default price
 * @param customerId nullable in V5: a contract-wide default price
 */
public record PricingTerm(
		UUID id,
		OrganizationId organizationId,
		UUID contractId,
		@Nullable UUID productId,
		@Nullable UUID customerId,
		PricingType pricingType,
		@Nullable Money unitPrice,
		@Nullable Money priceMinimum,
		@Nullable Money priceMaximum,
		CurrencyCode currency,
		EffectiveWindow effectiveWindow,
		int termVersion,
		long version) implements ScopedTerm, Serializable {

	public PricingTerm {
		Objects.requireNonNull(id, "id must not be null");
		Objects.requireNonNull(organizationId, "organizationId must not be null");
		Objects.requireNonNull(contractId, "contractId must not be null");
		Objects.requireNonNull(pricingType, "pricingType must not be null");
		Objects.requireNonNull(currency, "currency must not be null");
		Objects.requireNonNull(effectiveWindow, "effectiveWindow must not be null");
		if (termVersion < ContractTerm.MIN_TERM_VERSION) {
			throw new ValidationException("termVersion must be at least " + ContractTerm.MIN_TERM_VERSION);
		}
		if (version < 0) {
			throw new ValidationException("version must not be negative");
		}
		requireSameCurrency(unitPrice, currency, "unitPrice");
		requireSameCurrency(priceMinimum, currency, "priceMinimum");
		requireSameCurrency(priceMaximum, currency, "priceMaximum");
		requireNonNegative(unitPrice, "unitPrice");
		requireNonNegative(priceMinimum, "priceMinimum");
		requireNonNegative(priceMaximum, "priceMaximum");
		if (priceMinimum != null && priceMaximum != null && priceMinimum.compareTo(priceMaximum) > 0) {
			throw new ValidationException("priceMinimum must not exceed priceMaximum");
		}
	}

	/**
	 * Convenience for a single published price with no bounds, taking the raw V5
	 * date columns.
	 */
	public PricingTerm(UUID id, OrganizationId organizationId, UUID contractId, @Nullable UUID productId,
			@Nullable UUID customerId, PricingType pricingType, @Nullable Money unitPrice, CurrencyCode currency,
			LocalDate effectiveFrom, @Nullable LocalDate effectiveTo, int termVersion) {
		this(id, organizationId, contractId, productId, customerId, pricingType, unitPrice, null, null, currency,
				EffectiveWindow.of(effectiveFrom, effectiveTo), termVersion, 0L);
	}

	@Override
	public LocalDate effectiveFrom() {
		return this.effectiveWindow.effectiveFrom();
	}

	/**
	 * Whether this row applies to every product and customer on the contract, that
	 * is a published default rather than a negotiated rate.
	 */
	public boolean isContractWide() {
		return this.productId == null && this.customerId == null;
	}

	/**
	 * Whether this row supplies a price, as opposed to only bounding someone
	 * else's price. A {@link PricingType.Tiered} row with a null
	 * {@code unit_price} is a band, not a price.
	 */
	public boolean publishesPrice() {
		return this.unitPrice != null;
	}

	@Override
	public String canonical() {
		return CanonicalText.join(this.id, this.organizationId, this.contractId, this.productId, this.customerId,
				this.pricingType.code(), CanonicalText.money(this.unitPrice), CanonicalText.money(this.priceMinimum),
				CanonicalText.money(this.priceMaximum), this.currency.value(), this.effectiveWindow.canonical(),
				this.termVersion, this.version);
	}

	private static void requireSameCurrency(@Nullable Money money, CurrencyCode currency, String field) {
		if (money != null && !money.currency().equals(currency)) {
			throw new ValidationException(field + " must be in the pricing term currency " + currency.value());
		}
	}

	private static void requireNonNegative(@Nullable Money money, String field) {
		if (money != null && money.isNegative()) {
			throw new ValidationException(field + " must not be negative");
		}
	}

}

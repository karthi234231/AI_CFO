package com.fintech.cfo.contract.dto;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.fintech.cfo.contract.enums.PricingType;
import com.fintech.cfo.contract.model.EffectiveWindow;
import com.fintech.cfo.shared.domain.Money;

/**
 * A unit price that was in force on a date, together with the price line it came
 * from.
 *
 * <p>{@code declaredUnitPrice} is what the contract publishes; {@code unitPrice} is
 * what a caller must charge. The two differ only when a candidate price was
 * clamped into {@code price_minimum} / {@code price_maximum}. Keeping both is what
 * lets an investigator see that the agreed price and the billed price diverged and
 * by how much, instead of seeing only a number that is quietly not the contract's.
 *
 * <p>The originating {@code pricingTermId}, {@code termVersion} and
 * {@code effectiveWindow} are carried on the result, so a stored calculation can
 * prove which price version it used without re-reading the terms.
 *
 * @param unitPrice price to charge, in the contract currency, at
 * {@code NUMERIC(20,6)}
 * @param declaredUnitPrice price as published by the term, null for a
 * bounds-only row
 * @param priceMinimum lower bound from the term, null when unbounded below
 * @param priceMaximum upper bound from the term, null when unbounded above
 * @param clamped whether {@code unitPrice} differs from the price offered
 * @param pricingTermId originating {@code pricing_terms.id}
 * @param pricingType basis of the price
 * @param termVersion version of the price line applied
 * @param effectiveWindow window the price line applied in
 */
public record ResolvedPrice(
		Money unitPrice,
		@Nullable Money declaredUnitPrice,
		@Nullable Money priceMinimum,
		@Nullable Money priceMaximum,
		boolean clamped,
		UUID pricingTermId,
		PricingType pricingType,
		int termVersion,
		EffectiveWindow effectiveWindow) implements Serializable {

	public ResolvedPrice {
		Objects.requireNonNull(unitPrice, "unitPrice must not be null");
		Objects.requireNonNull(pricingTermId, "pricingTermId must not be null");
		Objects.requireNonNull(pricingType, "pricingType must not be null");
		Objects.requireNonNull(effectiveWindow, "effectiveWindow must not be null");
	}

	/**
	 * How far the charge was moved by clamping, in the contract currency.
	 *
	 * <p>Null when nothing was clamped: "no adjustment" must not be reported as a
	 * variance of zero against an implied baseline, because zero is also what a
	 * genuine nil adjustment would look like.
	 */
	@Nullable
	public Money clampedAmount() {
		if (!this.clamped || this.declaredUnitPrice == null) {
			return null;
		}
		return this.unitPrice.subtract(this.declaredUnitPrice);
	}

}

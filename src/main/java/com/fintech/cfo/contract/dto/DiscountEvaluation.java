package com.fintech.cfo.contract.dto;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.fintech.cfo.contract.enums.DiscountType;
import com.fintech.cfo.contract.model.EffectiveWindow;
import com.fintech.cfo.shared.domain.Money;

/**
 * Outcome of applying the discount in force on a date to an amount.
 *
 * <p><strong>The absence of a discount is a result, not an omission.</strong> Most
 * invoices legitimately carry no discount, so a missing term yields
 * {@link #none(Money)}: a discount of zero, a net equal to the gross, a null term
 * id and a term version of 0. That is deliberately different from a missing
 * <em>price</em>, where there is nothing sensible to charge and the caller is made
 * to fail loudly. {@link #hasDiscountTerm()} keeps the two apart.
 *
 * <p><strong>Composition policy: one term, never compounded.</strong> Exactly one
 * discount term is applied, and it is applied to the amount the caller supplied.
 * The service never subtracts one discount and then applies another, because V5
 * stores no stacking or compounding flag and silently compounding two negotiated
 * discounts would change a price no one agreed to. A caller that wants compounding
 * must pass an already-discounted base deliberately; {@code grossAmount} records
 * the base actually used, so that choice is visible on the result rather than
 * hidden in the caller's arithmetic.
 *
 * @param grossAmount the amount the discount was applied to, at
 * {@code NUMERIC(20,4)}
 * @param discountAmount the discount granted after capping
 * @param netAmount {@code grossAmount - discountAmount}
 * @param capAmount the cap that bound, null when nothing was capped, so "no cap" is
 * never reported as "capped at zero"
 * @param capReason why the discount was reduced
 * @param discountType basis of the term that applied, null when none applied
 * @param discountValue the term's value as stored, null when none applied
 * @param discountTermId originating {@code discount_terms.id}, null when none
 * applied
 * @param termVersion version of the discount term applied, 0 when none applied
 * @param effectiveWindow window the term applied in, null when none applied
 */
public record DiscountEvaluation(
		Money grossAmount,
		Money discountAmount,
		Money netAmount,
		@Nullable Money capAmount,
		DiscountCapReason capReason,
		@Nullable DiscountType discountType,
		@Nullable BigDecimal discountValue,
		@Nullable UUID discountTermId,
		int termVersion,
		@Nullable EffectiveWindow effectiveWindow) implements Serializable {

	public DiscountEvaluation {
		Objects.requireNonNull(grossAmount, "grossAmount must not be null");
		Objects.requireNonNull(discountAmount, "discountAmount must not be null");
		Objects.requireNonNull(netAmount, "netAmount must not be null");
		Objects.requireNonNull(capReason, "capReason must not be null");
		if (grossAmount.isNegative()) {
			throw new IllegalArgumentException("grossAmount must not be negative");
		}
		if (discountAmount.isNegative()) {
			throw new IllegalArgumentException("discountAmount must not be negative");
		}
	}

	/**
	 * No discount applied because no discount term was in force.
	 *
	 * <p>The amount is returned as supplied; {@code DiscountService} normalises it
	 * before calling, and a DTO does not decide a calculation's rounding policy.
	 */
	public static DiscountEvaluation none(Money grossAmount) {
		Objects.requireNonNull(grossAmount, "grossAmount must not be null");
		return new DiscountEvaluation(grossAmount, Money.zero(grossAmount.currency()), grossAmount, null,
				DiscountCapReason.None.INSTANCE, null, null, null, 0, null);
	}

	public boolean hasDiscountTerm() {
		return this.discountTermId != null;
	}

	public boolean wasCapped() {
		return this.capReason.isCapped();
	}

}

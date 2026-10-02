package com.fintech.cfo.contract.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.fintech.cfo.contract.dto.DiscountCapReason;
import com.fintech.cfo.contract.dto.DiscountEvaluation;
import com.fintech.cfo.contract.enums.DiscountType;
import com.fintech.cfo.contract.model.DiscountTerm;
import com.fintech.cfo.contract.model.ScopedTerm;
import com.fintech.cfo.shared.domain.CurrencyCode;
import com.fintech.cfo.shared.domain.Money;
import com.fintech.cfo.shared.exception.BusinessRuleException;

/**
 * Applies the discount in force on a date to an amount.
 *
 * <h2>Composition policy: exactly one term, never compounded</h2>
 *
 * <p>V5 has no stacking flag, no compounding flag and no ordering between
 * discount terms, so "apply all applicable discounts in order" has no
 * representation in the schema and would be an invention of this code rather than
 * something the data says. Exactly one discount term is therefore selected - by
 * specificity first, then by {@link EffectiveTermResolver}'s version tie-break -
 * and applied once.
 *
 * <p><strong>A percentage applies to the amount the caller supplies, not to a
 * price this service recomputed.</strong> That makes compounding an explicit act
 * by the caller rather than a hidden default: calling {@code evaluate} with an
 * already-discounted base is how a caller compounds, and the result records that
 * base in {@code grossAmount} so a reader can see that is what happened. Silently
 * re-basing a percentage against a net amount would make two identical contracts
 * bill differently depending on call order.
 *
 * <h2>Caps</h2>
 *
 * <p>Two independent ceilings, applied in this order:
 *
 * <ol>
 * <li>{@code max_discount_amount} from the term - a ceiling a contract author
 * wrote down.</li>
 * <li>The gross amount itself - a discount larger than the amount being discounted
 * would make the invoice a document the supplier owes money on, which is not a
 * discount.</li>
 * </ol>
 *
 * <p>Whichever bound the grant actually reached is reported as {@link
 * DiscountCapReason}, and the binding amount is reported as {@code capAmount}. A
 * result that says only "capped" without saying which ceiling is not much use to
 * the finance reviewer who has to decide whether the contract needs renegotiating.
 *
 * <h2>Currency</h2>
 *
 * <p>Never converted. A term in another currency is refused rather than
 * converted, because this module has no FX component and an invented rate would
 * make the result unreproducible.
 *
 * <p>Stateless.
 */
public final class DiscountService {

	private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

	/**
	 * Scale at which the rate is divided out of 100.
	 *
	 * <p>A division by 100 always terminates, so a scale this far above the
	 * hand-out scale makes the quotient exact and the only rounding in the whole
	 * percentage path the single hand-out the module promises. Dividing at
	 * {@link MonetaryScale#AMOUNT_SCALE} instead rounded the rate factor itself and
	 * could reduce a real grant to zero.
	 */
	private static final int RATE_DIVISION_SCALE = 18;

	private final TermSelector selector;

	public DiscountService(TermSelector selector) {
		this.selector = Objects.requireNonNull(selector, "selector must not be null");
	}

	/**
	 * Selects the applicable discount and applies it. No applicable term is a valid
	 * answer: a discount of zero, with the reason recorded as
	 * {@link DiscountCapReason.None}.
	 */
	public DiscountEvaluation evaluateFor(List<DiscountTerm> terms, @Nullable UUID productId,
			@Nullable UUID customerId, Money grossAmount, LocalDate asOfDate) {
		Objects.requireNonNull(terms, "terms must not be null");
		Objects.requireNonNull(grossAmount, "grossAmount must not be null");
		Objects.requireNonNull(asOfDate, "asOfDate must not be null");
		DiscountTerm term = this.selector.selectInForce(terms, productId, customerId, asOfDate).orElse(null);
		return evaluate(grossAmount, term, asOfDate);
	}

	/**
	 * Applies a specific term, or none when {@code term} is null.
	 */
	public DiscountEvaluation evaluate(Money grossAmount, @Nullable DiscountTerm term, LocalDate asOfDate) {
		Objects.requireNonNull(grossAmount, "grossAmount must not be null");
		Objects.requireNonNull(asOfDate, "asOfDate must not be null");
		Money gross = MonetaryScale.amount(grossAmount);
		if (gross.isNegative()) {
			// Normalised first, so the negativity test and the cap comparisons all run
			// at amount scale rather than at whatever scale the caller supplied.
			throw new BusinessRuleException("grossAmount must not be negative");
		}
		if (term == null) {
			// A legitimate answer, not an omission: most invoices carry no discount.
			// Reported as a zero discount with DiscountCapReason.None so the caller
			// can tell "no discount term" from "a discount of zero was granted".
			return DiscountEvaluation.none(gross);
		}
		// The window is re-checked even though TermSelector already filtered on it.
		// evaluate() is public and can be called with a hand-picked term; re-checking
		// keeps that path from reaching a different answer than evaluateFor().
		if (!term.effectiveWindow().contains(asOfDate)) {
			throw new BusinessRuleException("discount term " + term.id() + " is not in force on " + asOfDate);
		}
		// Exhaustive over DiscountType: adding a variant fails compilation here
		// rather than silently defaulting to a percentage.
		Money computed = switch (term.discountType()) {
			case DiscountType.Percentage percentage -> percentageOf(gross, term);
			case DiscountType.FixedAmount fixed -> fixedAmount(gross, term);
		};
		// Capping happens after the full grant is computed, so `computedDiscount`
		// can still answer what the contract entitled the customer to.
		Cap cap = applyCaps(computed, gross, term.maxDiscountAmount());
		// Net is derived from the capped grant, never from the raw one: a discount
		// larger than the invoice must not produce a negative net.
		Money net = MonetaryScale.amount(gross.subtract(cap.granted()));
		return new DiscountEvaluation(gross, cap.granted(), net, cap.amount(), cap.reason(), term.discountType(),
				term.discountValue(), term.id(), term.termVersion(), term.effectiveWindow());
	}

	/**
	 * The discount that would be granted, before capping. Useful for asking "what
	 * did this contract entitle the customer to" separately from "what was
	 * charged".
	 */
	public Money computedDiscount(Money grossAmount, DiscountTerm term, LocalDate asOfDate) {
		Objects.requireNonNull(grossAmount, "grossAmount must not be null");
		Objects.requireNonNull(term, "term must not be null");
		Money gross = MonetaryScale.amount(grossAmount);
		return switch (term.discountType()) {
			case DiscountType.Percentage percentage -> percentageOf(gross, term);
			case DiscountType.FixedAmount fixed -> fixedAmount(gross, term);
		};
	}

	/**
	 * A percentage grant, at amount scale.
	 *
	 * <p>The rate is divided out of 100 explicitly rather than as a scale shift on
	 * the rate, so a rate of 12.5 cannot be read as 1250%. The division is taken at
	 * {@link #RATE_DIVISION_SCALE}, deliberately far wider than the hand-out scale,
	 * so the quotient is exact and the multiply runs at working precision
	 * (DECIMAL128) before a single HALF_UP round to the end.
	 *
	 * <p><strong>One rounding, not two.</strong> Dividing at the amount scale first
	 * - as this method used to - rounds the rate factor to four places before it is
	 * ever applied. A gross of 0.002 at 12.5% is exactly 0.00025, and rounding the
	 * quotient first reduces it to 0.00002 * 12.5 = 0.00025 only by luck of scale;
	 * for any rate whose factor needs a fifth decimal the grant silently collapses
	 * to zero. That is the hidden intermediate rounding
	 * {@link MonetaryScale} exists to forbid, and it made a real discount vanish.
	 */
	private static Money percentageOf(Money gross, DiscountTerm term) {
		// Three steps, one rounding. The divide by 100 is explicit rather than a
		// scale shift on the rate, so a rate of 12.5 cannot be read as 1250%. The
		// multiply keeps working precision (DECIMAL128) and only the final
		// withScale rounds - an intermediate round would be a hidden second one.
		Money discount = gross.divide(ONE_HUNDRED, RATE_DIVISION_SCALE, MonetaryScale.ROUNDING_MODE)
				.multiply(term.discountValue(), MonetaryScale.WORKING_PRECISION)
				.withScale(MonetaryScale.AMOUNT_SCALE, MonetaryScale.ROUNDING_MODE);
		return MonetaryScale.amount(discount);
	}

	/**
	 * A fixed grant, at amount scale. A fixed discount larger than the amount being
	 * discounted is not refused here; it is capped, so the reported reason says
	 * which bound bound it.
	 */
	private static Money fixedAmount(Money gross, DiscountTerm term) {
		Money fixed = term.fixedDiscountAmount();
		if (fixed == null) {
			throw new BusinessRuleException("fixed discount term " + term.id() + " has no currency");
		}
		if (!fixed.currency().equals(gross.currency())) {
			throw new BusinessRuleException("discount term " + term.id() + " is in " + fixed.currency().value()
					+ " but the amount is in " + gross.currency().value());
		}
		return MonetaryScale.amount(fixed);
	}

	/**
	 * Applies the two ceilings and reports which one bound.
	 *
	 * <p>Equal to a bound counts as reaching it, so {@code capAmount} is never a
	 * bound the grant merely equalled and never null while a cap is reported.
	 */
	private static Cap applyCaps(Money computed, Money gross, @Nullable Money maxDiscountAmount) {
		Money granted = computed;
		DiscountCapReason reason = DiscountCapReason.None.INSTANCE;
		Money bound = null;
		if (maxDiscountAmount != null) {
			// A cap in another currency is refused rather than converted: this module
			// has no FX component and an invented rate would make the result
			// irreproducible.
			if (!maxDiscountAmount.currency().equals(gross.currency())) {
				throw new BusinessRuleException("maxDiscountAmount is in " + maxDiscountAmount.currency().value()
						+ " but the amount is in " + gross.currency().value());
			}
			Money ceiling = MonetaryScale.amount(maxDiscountAmount);
			// `>=`, not `>`: a grant exactly equal to the ceiling has reached it.
			// The alternative reports MAX_DISCOUNT_AMOUNT without ever capping
			// anything, which sends a reviewer looking for a reduction that did not
			// happen.
			if (granted.compareTo(ceiling) >= 0) {
				granted = ceiling;
				reason = DiscountCapReason.MaxDiscountAmount.INSTANCE;
				bound = ceiling;
			}
		}
		// Applied second and able to override the first, so a cap above the gross
		// amount still cannot produce a discount larger than the invoice. The reason
		// is overwritten rather than combined: only one reason is reported, and it is
		// the one that actually bound the final figure.
		//
		// Strictly greater, unlike the max_discount_amount test above. This ceiling
		// exists to stop the grant exceeding the invoice; a grant that already equals
		// or is below the invoice was never reduced by it. Reporting
		// GROSS_AMOUNT_LIMIT there - a zero discount on a zero gross, for instance -
		// tells a reviewer a cap was applied when no figure moved, which is the same
		// false report the max_discount_amount comment above warns about.
		if (granted.compareTo(gross) > 0) {
			granted = gross;
			reason = DiscountCapReason.GrossAmountLimit.INSTANCE;
			bound = gross;
		}
		return new Cap(granted, reason, bound);
	}

	/**
	 * Every discount term that could have applied, most specific first, each applied
	 * on its own. Lets a caller see what the alternatives would have produced
	 * without any of them being combined.
	 */
	public List<DiscountEvaluation> evaluateEach(Money grossAmount, List<DiscountTerm> terms,
			@Nullable UUID productId, @Nullable UUID customerId, LocalDate asOfDate) {
		return this.selector.candidatesInForce(terms, productId, customerId, asOfDate).stream()
				.map(term -> evaluate(grossAmount, term, asOfDate))
				.toList();
	}

	/**
	 * Every discount term in force for this contract, most specific first.
	 */
	public List<DiscountTerm> applicableTerms(List<DiscountTerm> terms, @Nullable UUID productId,
			@Nullable UUID customerId, LocalDate asOfDate) {
		return this.selector.candidatesInForce(terms, productId, customerId, asOfDate);
	}

	/**
	 * Which ceiling bound the grant, and by how much. A private carrier rather than
	 * three out-parameters.
	 */
	private record Cap(Money granted, DiscountCapReason reason, @Nullable Money amount) {
	}

}
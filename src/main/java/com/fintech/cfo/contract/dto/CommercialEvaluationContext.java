package com.fintech.cfo.contract.dto;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import com.fintech.cfo.shared.domain.CurrencyCode;
import com.fintech.cfo.shared.domain.Money;

/**
 * The transaction facts a commercial rule is evaluated against, for one date.
 *
 * <p>Every monetary input is already in {@code currency}. The context carries no FX
 * rate and performs no conversion: a rule threshold is expressed in the
 * transaction's own currency, and converting either side would put a rate that
 * cannot be reproduced into the result.
 *
 * <p>All inputs except {@code asOfDate} and {@code currency} are optional. A rule
 * whose input is absent evaluates to "not applicable" rather than to a pass or a
 * fail: a minimum-charge rule has no opinion about a transaction that carries no
 * gross amount. Absence of evidence is reported as absence, never as a satisfied
 * rule.
 *
 * @param asOfDate date the rules were resolved for, recorded on every result so an
 * evaluation can be traced to the terms it used
 */
public record CommercialEvaluationContext(
		LocalDate asOfDate,
		CurrencyCode currency,
		@Nullable Money grossAmount,
		@Nullable BigDecimal quantity,
		@Nullable Money unitPrice,
		@Nullable Money discountAmount,
		@Nullable Integer paymentTermDays) implements Serializable {

	public CommercialEvaluationContext {
		// Only the two fields every rule needs unconditionally are required. The rest
		// are nullable by design: their absence is what makes a dependent rule
		// "not applicable" rather than "failed".
		Objects.requireNonNull(asOfDate, "asOfDate must not be null");
		Objects.requireNonNull(currency, "currency must not be null");
	}

	/**
	 * The common invoice case: an amount, a quantity and a unit price.
	 */
	public static CommercialEvaluationContext forInvoice(LocalDate asOfDate, CurrencyCode currency, Money grossAmount,
			@Nullable BigDecimal quantity, @Nullable Money unitPrice) {
		return new CommercialEvaluationContext(asOfDate, currency, grossAmount, quantity, unitPrice, null, null);
	}

	public boolean hasGrossAmount() {
		return this.grossAmount != null;
	}

	public boolean hasQuantity() {
		return this.quantity != null;
	}

	public boolean hasUnitPrice() {
		return this.unitPrice != null;
	}

	public boolean hasDiscountAmount() {
		return this.discountAmount != null;
	}

	public boolean hasPaymentTermDays() {
		return this.paymentTermDays != null;
	}

	/**
	 * Whether a monetary input is present in the context's own currency. A mismatch
	 * is a caller bug that would otherwise be reported as a rule violation.
	 *
	 * @return true when the amount is non-null and in this context's currency; a
	 * null amount yields false, since a missing input is not "in" any currency
	 */
	public boolean isInCurrency(Money amount) {
		// Compared by value, not identity: two CurrencyCode instances for the same
		// currency must compare equal, and this is the only currency check on the
		// path into rule evaluation.
		return amount != null && amount.currency().equals(this.currency);
	}

	public CommercialEvaluationContext withGrossAmount(Money amount) {
		return new CommercialEvaluationContext(this.asOfDate, this.currency, amount, this.quantity, this.unitPrice,
				this.discountAmount, this.paymentTermDays);
	}

	public CommercialEvaluationContext withDiscountAmount(Money amount) {
		return new CommercialEvaluationContext(this.asOfDate, this.currency, this.grossAmount, this.quantity,
				this.unitPrice, amount, this.paymentTermDays);
	}

	public CommercialEvaluationContext withPaymentTermDays(Integer days) {
		return new CommercialEvaluationContext(this.asOfDate, this.currency, this.grossAmount, this.quantity,
				this.unitPrice, this.discountAmount, days);
	}

	public CommercialEvaluationContext withUnitPrice(Money amount) {
		return new CommercialEvaluationContext(this.asOfDate, this.currency, this.grossAmount, this.quantity, amount,
				this.discountAmount, this.paymentTermDays);
	}

}

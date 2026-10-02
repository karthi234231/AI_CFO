package com.fintech.cfo.financialtruth.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fintech.cfo.financialtruth.enums.CalculationType;
import com.fintech.cfo.financialtruth.enums.DiscountType;
import com.fintech.cfo.financialtruth.enums.PricingType;
import com.fintech.cfo.financialtruth.model.CalculationInput;
import com.fintech.cfo.financialtruth.model.DiscountTerm;
import com.fintech.cfo.financialtruth.model.InvoiceLineInput;
import com.fintech.cfo.financialtruth.model.PricingTerm;
import com.fintech.cfo.shared.domain.CurrencyCode;
import com.fintech.cfo.shared.domain.Money;
import com.fintech.cfo.shared.domain.OrganizationId;
import com.fintech.cfo.shared.domain.SourceReference;
import com.fintech.cfo.shared.domain.UserId;
import com.fintech.cfo.shared.exception.ValidationException;

/**
 * A client request to run a calculation.
 *
 * <h2>Why the organization id is not a field here</h2>
 * There is deliberately no organization id on this request. Tenant scope comes from
 * the authenticated {@code SecurityPrincipal}, never from a request body - accepting
 * one here would let a caller run another tenant's reconciliation. The scope is
 * passed to {@link #toInput(OrganizationId)} by whoever already holds the principal.
 *
 * <p>Amounts arrive as strings and are parsed here with
 * {@link BigDecimal#BigDecimal(String)}, never through {@code double}. A JSON number
 * has already passed through binary floating point by the time it deserialises, so
 * money must not be modelled as a JSON number.
 */
public record RunCalculationRequest(
		UUID runId,
		CalculationType calculationType,
		String invoiceNumber,
		LocalDate invoiceDate,
		LocalDate asOfDate,
		List<InvoiceLineRequest> lines,
		List<PricingTermRequest> pricingTerms,
		List<DiscountTermRequest> discountTerms) {

	public RunCalculationRequest {
		if (runId == null) {
			throw new ValidationException("runId must be supplied by the caller");
		}
		if (calculationType == null) {
			throw new ValidationException("calculationType must not be null");
		}
		if (invoiceNumber == null || invoiceNumber.isBlank()) {
			throw new ValidationException("invoiceNumber must not be blank");
		}
		if (invoiceDate == null || asOfDate == null) {
			throw new ValidationException("invoiceDate and asOfDate must both be supplied; the calculation never "
					+ "assumes today's date");
		}
		lines = lines == null ? List.of() : List.copyOf(lines);
		pricingTerms = pricingTerms == null ? List.of() : List.copyOf(pricingTerms);
		discountTerms = discountTerms == null ? List.of() : List.copyOf(discountTerms);
		if (lines.isEmpty()) {
			throw new ValidationException("an invoice with no lines cannot be reconciled");
		}
	}

	/**
	 * @param organizationId tenant scope taken from the authenticated principal, not
	 *                       from this request
	 * @param triggeredBy    the user the run is attributed to, for audit; may be null
	 *                       for a batch-triggered run
	 * @return the frozen snapshot the engine reads, with terms converted from their
	 *         string wire form
	 */
	public CalculationInput toInput(OrganizationId organizationId, UserId triggeredBy) {
		// Converted eagerly and in list order. The engine must never see a wire-shaped
		// record, and the resulting snapshot is defensively copied by CalculationInput,
		// so nothing here can be mutated after the checksum is taken.
		List<InvoiceLineInput> lineInputs = new ArrayList<>(this.lines.size());
		for (InvoiceLineRequest line : this.lines) {
			lineInputs.add(line.toLineInput());
		}
		List<PricingTerm> pricing = new ArrayList<>(this.pricingTerms.size());
		for (PricingTermRequest term : this.pricingTerms) {
			pricing.add(term.toTerm());
		}
		List<DiscountTerm> discount = new ArrayList<>(this.discountTerms.size());
		for (DiscountTermRequest term : this.discountTerms) {
			discount.add(term.toTerm());
		}
		return new CalculationInput(organizationId, this.calculationType, this.invoiceNumber, this.invoiceDate,
				this.asOfDate, lineInputs, pricing, discount, triggeredBy);
	}

	/**
	 * One invoice line as supplied by a client.
	 *
	 * @param unitPrice        invoiced unit price as a decimal string
	 * @param discountAmount   discount granted as a decimal string; omit for none
	 * @param sourceSystem     origin of the line, required so the result stays traceable
	 * @param sourceRecordId   origin row identifier
	 */
	public record InvoiceLineRequest(
			int lineNumber,
			String productKey,
			String quantity,
			String unitPrice,
			String discountAmount,
			String taxAmount,
			String currency,
			String sourceSystem,
			String sourceRecordId,
			String sourceFileId,
			Long sourceRowNumber) {

		public InvoiceLineRequest {
			if (unitPrice == null || unitPrice.isBlank()) {
				throw new ValidationException("unitPrice must be supplied; a line without a price is not a line");
			}
			if (currency == null || currency.isBlank()) {
				throw new ValidationException("a line must carry a currency");
			}
			if (sourceSystem == null || sourceSystem.isBlank() || sourceRecordId == null || sourceRecordId.isBlank()) {
				throw new ValidationException("sourceSystem and sourceRecordId are mandatory: a reported amount "
						+ "with no way back to its source row is not shippable");
			}
		}

		public InvoiceLineInput toLineInput() {
			// Currency parsed first: it is the reference every other amount on the line is
			// checked against by InvoiceLineInput's compact constructor.
			CurrencyCode parsedCurrency = CurrencyCode.of(this.currency);
			// Quantity stays null when absent rather than defaulting to zero. A zero
			// quantity would look like a priced line that happens to be free; null makes
			// the line unusable, which is what it is.
			BigDecimal parsedQuantity = this.quantity == null || this.quantity.isBlank() ? null
					: new BigDecimal(this.quantity.trim());
			return new InvoiceLineInput(this.lineNumber, this.productKey, parsedQuantity,
					Money.of(this.unitPrice, parsedCurrency), amountOrZero(this.discountAmount, parsedCurrency),
					amountOrZero(this.taxAmount, parsedCurrency),
					SourceReference.of(this.sourceSystem, "invoice_line", this.sourceRecordId, this.sourceFileId,
							this.sourceRowNumber));
		}

		private static Money amountOrZero(String value, CurrencyCode currency) {
			// Absent discount or tax means zero, unlike absent quantity. "No discount was
			// applied" is a fact about the invoice; "no quantity was billed" is a gap in the
			// data. The distinction is why this helper exists but the quantity path does not.
			return value == null || value.isBlank() ? Money.zero(currency) : Money.of(value, currency);
		}
	}

	/**
	 * A contract pricing term as supplied by a client.
	 *
	 * <p>No minimum or maximum bound on the wire: an externally supplied term is not asked
	 * to police itself, and the calculator will not invent a constraint the caller did not
	 * state.
	 */
	public record PricingTermRequest(
			String productKey,
			String termId,
			String pricingType,
			String unitPrice,
			String currency,
			LocalDate effectiveFrom,
			LocalDate effectiveTo,
			int termVersion) {

		public PricingTermRequest {
			if (currency == null || currency.isBlank()) {
				throw new ValidationException("a pricing term must carry a currency");
			}
			if (pricingType == null || pricingType.isBlank()) {
				throw new ValidationException("pricingType must be supplied; guessing one would be guessing a price");
			}
		}

		/**
		 * Converts the wire form of a pricing term.
		 *
		 * <p>Minimum and maximum bounds are not exposed on the wire, so a term built from
		 * a request carries none and {@code assertWithinDeclaredBounds} has nothing to
		 * check against. The unit price may be null: a term with no price is reported as
		 * incomplete input rather than being read as a zero price.
		 */
		public PricingTerm toTerm() {
			return new PricingTerm(this.productKey, this.termId, PricingType.valueOf(this.pricingType.trim()),
					this.unitPrice == null || this.unitPrice.isBlank() ? null : new BigDecimal(this.unitPrice.trim()),
					null, null, CurrencyCode.of(this.currency), this.effectiveFrom, this.effectiveTo, this.termVersion);
		}
	}

	/**
	 * A contract discount term as supplied by a client.
	 *
	 * <p>{@code currency} is optional here because a percentage carries none; the domain
	 * type is what insists on one for a fixed amount or a cap.
	 */
	public record DiscountTermRequest(
			String productKey,
			String termId,
			String discountType,
			String discountValue,
			String maxDiscountAmount,
			String currency,
			LocalDate effectiveFrom,
			LocalDate effectiveTo,
			int termVersion) {

		public DiscountTermRequest {
			if (discountType == null || discountType.isBlank()) {
				throw new ValidationException("discountType must be supplied");
			}
		}

		/**
		 * Converts the wire form of a discount term.
		 *
		 * <p>Currency may be absent, because a percentage term legitimately has none.
		 * For a {@code FIXED_AMOUNT} or a capped term the {@code DiscountTerm} compact
		 * constructor then rejects the combination, which is why the absence is passed
		 * through rather than defaulted here.
		 */
		public DiscountTerm toTerm() {
			return new DiscountTerm(this.productKey, this.termId, DiscountType.valueOf(this.discountType.trim()),
					this.discountValue == null || this.discountValue.isBlank() ? null
							: new BigDecimal(this.discountValue.trim()),
					this.maxDiscountAmount == null || this.maxDiscountAmount.isBlank() ? null
							: new BigDecimal(this.maxDiscountAmount.trim()),
					this.currency == null || this.currency.isBlank() ? null : CurrencyCode.of(this.currency),
					this.effectiveFrom, this.effectiveTo, this.termVersion);
		}
	}

}
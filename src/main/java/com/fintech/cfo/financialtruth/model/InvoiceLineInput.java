package com.fintech.cfo.financialtruth.model;

import java.math.BigDecimal;

import com.fintech.cfo.shared.domain.CurrencyCode;
import com.fintech.cfo.shared.domain.Money;
import com.fintech.cfo.shared.domain.SourceReference;
import com.fintech.cfo.shared.exception.ValidationException;

/**
 * One normalised invoice line: the actual side of a comparison.
 *
 * <p>This is a plain value carrier with no persistence identity. It exists so a
 * calculation can be exercised and reproduced from a snapshot without reading the
 * database, which is what makes {@code calculation_runs.input_checksum} meaningful.
 *
 * <p>All money on a line shares the line currency; the compact constructor rejects
 * a line that mixes currencies rather than letting it reach an arithmetic operation.
 */
public record InvoiceLineInput(
		int lineNumber,
		String productKey,
		BigDecimal quantity,
		Money unitPrice,
		Money discountAmount,
		Money taxAmount,
		SourceReference source) {

	public InvoiceLineInput {
		if (lineNumber <= 0) {
			throw new ValidationException("lineNumber must be greater than 0");
		}
		if (unitPrice == null) {
			throw new ValidationException("unitPrice must not be null");
		}
		// Discount and tax are required but may be zero. The distinction matters: a null
		// would have to mean "not read", and the engine has no way to tell that apart
		// from "read as nil" once the line is in a checksum. Callers supply zero.
		if (discountAmount == null) {
			throw new ValidationException(
					"discountAmount must not be null; a line without a discount is supplied at zero by the caller");
		}
		if (taxAmount == null) {
			throw new ValidationException("taxAmount must not be null; a line without tax is supplied at zero by the caller");
		}
		// Currency agreement enforced here rather than at the first arithmetic operation,
		// so a mixed-currency line fails at the boundary with a field-level message.
		requireSameCurrency(unitPrice, discountAmount, "discountAmount");
		requireSameCurrency(unitPrice, taxAmount, "taxAmount");
	}

	/**
	 * Currency of the line, taken from the price every other amount was checked against.
	 *
	 * <p>The unit price is the reference because the compact constructor has already
	 * proved the discount and tax amounts match it.
	 */
	public CurrencyCode currency() {
		return this.unitPrice.currency();
	}

	/**
	 * Whether this line carries enough arithmetic for a real comparison.
	 *
	 * <p>A missing or non-positive quantity cannot yield a meaningful line truth, so
	 * rules report the line as {@code INCOMPLETE_INPUTS} rather than dividing by or
	 * guessing at it.
	 */
	public boolean hasUsableQuantity() {
		return this.quantity != null && this.quantity.signum() > 0;
	}

	/**
	 * Stable textual form for checksums. Field order is fixed and no map or set is
	 * involved, so re-reading the same line from storage produces the same string.
	 */
	public String canonicalForm() {
		return "line[" + this.lineNumber
				+ "|product=" + RoundingPolicy.canonicalText(this.productKey)
				+ "|qty=" + RoundingPolicy.canonicalNumber(this.quantity)
				+ "|unitPrice=" + RoundingPolicy.canonicalMoney(this.unitPrice)
				+ "|discount=" + RoundingPolicy.canonicalMoney(this.discountAmount)
				+ "|tax=" + RoundingPolicy.canonicalMoney(this.taxAmount)
				+ "|source=" + (this.source == null ? "-" : this.source.toString())
				+ "]";
	}

	/**
	 * Convenience factory for the common shape: quantity and unit price only, no
	 * discount and no tax.
	 *
	 * <p>The two absent amounts become explicit zeroes in the line currency, so the
	 * compact constructor's non-null requirement is satisfied here rather than at every
	 * call site.
	 *
	 * @param lineNumber 1-based position of the line on its invoice
	 * @param productKey product or SKU the line was billed against
	 * @param quantity   billed quantity; may be null, which makes the line unusable for
	 *                   arithmetic rather than zero
	 * @param unitPrice  invoiced unit price, which also fixes the line currency
	 * @param source     pointer back to the invoice row this line was read from
	 */
	public static InvoiceLineInput of(int lineNumber, String productKey, BigDecimal quantity, Money unitPrice,
			SourceReference source) {
		return new InvoiceLineInput(lineNumber, productKey, quantity, unitPrice,
				Money.zero(unitPrice.currency()), Money.zero(unitPrice.currency()), source);
	}

	private static void requireSameCurrency(Money reference, Money candidate, String field) {
		if (!reference.currency().equals(candidate.currency())) {
			throw new ValidationException(field + " currency " + candidate.currency().value()
					+ " does not match line currency " + reference.currency().value());
		}
	}

}
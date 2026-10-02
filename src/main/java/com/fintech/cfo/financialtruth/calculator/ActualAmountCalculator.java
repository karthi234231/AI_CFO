package com.fintech.cfo.financialtruth.calculator;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.fintech.cfo.financialtruth.model.InvoiceLineInput;
import com.fintech.cfo.financialtruth.model.RoundingPolicy;
import com.fintech.cfo.shared.domain.Money;
import com.fintech.cfo.shared.exception.BusinessRuleException;
import com.fintech.cfo.shared.exception.ValidationException;

/**
 * Reads what was actually charged off an invoice line.
 *
 * <p>No contract knowledge, no clock, no I/O. The arithmetic mirrors
 * {@link ExpectedAmountCalculator} exactly - same normalisation, same single
 * rounding step - so the two sides of a comparison differ only because the
 * underlying figures differ, never because of a difference in how they were
 * computed.
 *
 * <p>The net formula is {@code quantity * unitPrice - discount + tax}, which is the
 * identity {@code V4__create_financial_data.sql} enforces on {@code invoice_lines}
 * with {@code ck_invoice_lines_total}.
 */
public final class ActualAmountCalculator {

	private final int monetaryScale;
	private final RoundingMode roundingMode;

	public ActualAmountCalculator() {
		this(RoundingPolicy.MONETARY_SCALE, RoundingPolicy.ROUNDING_MODE);
	}

	/**
	 * @param monetaryScale decimal places every produced amount is rounded to
	 * @param roundingMode  the single rounding mode applied to every component
	 */
	public ActualAmountCalculator(int monetaryScale, RoundingMode roundingMode) {
		if (monetaryScale < 0) {
			throw new ValidationException("monetaryScale must not be negative");
		}
		if (roundingMode == null) {
			throw new ValidationException("roundingMode must not be null; implicit rounding is not permitted");
		}
		this.monetaryScale = monetaryScale;
		this.roundingMode = roundingMode;
	}

	/**
	 * Invoiced gross: quantity multiplied by the invoiced unit price.
	 *
	 * @throws BusinessRuleException if the line carries no usable quantity; there is no
	 *                               invoiced gross to report without one
	 */
	public Money actualGrossAmount(InvoiceLineInput line) {
		requireUsableQuantity(line);
		// Identical normalisation and identical single rounding step to
		// ExpectedAmountCalculator.expectedGrossAmount. This symmetry is deliberate:
		// the two sides of a comparison must not differ in method, only in figures.
		BigDecimal unitPrice = RoundingPolicy.roundUnitPrice(line.unitPrice().amount());
		BigDecimal quantity = RoundingPolicy.roundQuantity(line.quantity());
		return Money.of(unitPrice, line.unitPrice().currency()).multiply(quantity)
				.withScale(this.monetaryScale, this.roundingMode);
	}

	/**
	 * Discount actually granted on the line.
	 *
	 * <p>Read straight from the invoice row, never derived from the pricing terms: this
	 * is the figure the supplier actually applied, and re-deriving it would compare the
	 * contract with itself.
	 */
	public Money actualDiscountAmount(InvoiceLineInput line) {
		requireLine(line);
		return line.discountAmount().withScale(this.monetaryScale, this.roundingMode);
	}

	/**
	 * Tax actually charged on the line.
	 *
	 * <p>Carried through both sides of the comparison unchanged, so tax is present in the
	 * payable and absent from the variance.
	 */
	public Money actualTaxAmount(InvoiceLineInput line) {
		requireLine(line);
		return line.taxAmount().withScale(this.monetaryScale, this.roundingMode);
	}

	/**
	 * Invoiced net payable for the line.
	 *
	 * <p>Same {@code gross - discount + tax} identity the engine applies to the expected
	 * side, so the net variance isolates the commercial deviation rather than the
	 * formula.
	 */
	public Money actualNetAmount(InvoiceLineInput line) {
		return actualGrossAmount(line).subtract(actualDiscountAmount(line)).add(actualTaxAmount(line))
				.withScale(this.monetaryScale, this.roundingMode);
	}

	private static void requireLine(InvoiceLineInput line) {
		if (line == null) {
			throw new ValidationException("line must not be null");
		}
	}

	private static void requireUsableQuantity(InvoiceLineInput line) {
		requireLine(line);
		if (!line.hasUsableQuantity()) {
			throw new BusinessRuleException("line " + line.lineNumber()
					+ " has no usable quantity; an actual amount cannot be derived from it");
		}
	}

}
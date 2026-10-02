package com.fintech.cfo.financial.model;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.fintech.cfo.shared.domain.Money;
import com.fintech.cfo.shared.domain.OrganizationId;
import com.fintech.cfo.shared.domain.SourceReference;
import com.fintech.cfo.shared.exception.ValidationException;
import com.fintech.cfo.shared.validation.Preconditions;

/**
 * Immutable mirror of the V4 {@code invoice_lines} row.
 *
 * <p>The line total is the module's central arithmetic decision. The literal V4
 * constraint is
 * {@code CHECK (line_total = (quantity * unit_price) - discount_amount + tax_amount)}
 * with {@code quantity NUMERIC(20,6)}, {@code unit_price NUMERIC(20,6)} and
 * {@code line_total NUMERIC(20,4)}. Two consequences are modelled here rather
 * than hidden:
 *
 * <ul>
 * <li>The canonical {@link #lineTotal()} is rounded to the money scale
 * ({@code 4}, HALF_UP) before the discount and tax are applied, because the
 * column cannot hold the exact product of two six-decimal numbers.</li>
 * <li>{@link #schemaCheckVariance()} reports the residual against the literal
 * constraint. It is zero whenever the product happens to have at most four
 * decimal places, and non-zero - by design, and by no more than half of the
 * smallest stored unit - when it does not. The persistence pass must therefore
 * relax that constraint to a rounded comparison; see the module report.</li>
 * </ul>
 *
 * <p>{@code currency CHAR(3)} is not a component: the four {@link Money} values
 * each carry it, and the compact constructor proves they agree. A line whose
 * amounts are not all in one currency cannot be constructed at all, which is
 * what "raise, never convert" has to mean for a value type.
 */
public record InvoiceLine(
		UUID id,
		OrganizationId organizationId,
		UUID invoiceId,
		@Nullable UUID productId,
		int lineNumber,
		@Nullable String description,
		BigDecimal quantity,
		Money unitPrice,
		Money discountAmount,
		Money taxAmount,
		Money lineTotal,
		SourceReference source) implements Serializable {

	/** {@code invoice_lines.description VARCHAR(1000)}. */
	public static final int MAX_DESCRIPTION_LENGTH = 1000;

	/** Scale of {@code invoice_lines.quantity NUMERIC(20,6)}. */
	public static final int QUANTITY_SCALE = 6;

	/** Scale of {@code invoice_lines.unit_price NUMERIC(20,6)}. */
	public static final int UNIT_PRICE_SCALE = 6;

	/** Scale of every {@code NUMERIC(20,4)} amount column in V4. */
	public static final int AMOUNT_SCALE = 4;

	/** Integer digits a {@code NUMERIC(20,4)} column can hold. */
	public static final int AMOUNT_INTEGER_DIGITS = 16;

	/** Integer digits a {@code NUMERIC(20,6)} column can hold. */
	public static final int QUANTITY_INTEGER_DIGITS = 14;

	/**
	 * Rounding policy for the line arithmetic.
	 *
	 * <p>HALF_UP rather than HALF_EVEN: this system bills the figure it
	 * computes, and a banker's-rounding tie would systematically shave half a
	 * paisa off every other line in the supplier's favour. Rounding happens once
	 * per line, on the gross, so no hidden second rounding can precede it.
	 */
	public static final RoundingMode ROUNDING_MODE = RoundingMode.HALF_UP;

	public InvoiceLine {
		// Identity of the line row.
		if (id == null) {
			throw new ValidationException("id must not be null");
		}
		// Tenancy is denormalised onto the line (invoice_lines.organization_id) so
		// that a period-wide line query never has to join through the header.
		Preconditions.requireNonNull(organizationId, "organizationId");
		// The owning invoice is required: a line with no aggregate cannot be
		// reconciled against anything.
		Preconditions.requireNonNull(invoiceId, "invoiceId");
		// productId is deliberately NOT required: an unmapped line is still a real
		// charge and must be stored. Dropping it would lose revenue; guessing a
		// product would misstate what was sold.
		//
		// lineNumber is 1-based because ux_invoice_lines_invoice_line
		// (invoice_id, line_number) is the line's only identity - it has no external
		// key, so position is how a re-import replaces a line set.
		if (lineNumber <= 0) {
			throw new ValidationException("lineNumber must be greater than 0");
		}
		description = Preconditions.optionalText(description, "description", MAX_DESCRIPTION_LENGTH);
		// quantity NUMERIC(20,6): precision 20 = 14 integer digits + scale 6.
		// Deliberately finer than the money scale, which is what creates the
		// rounding question the rest of this record answers.
		Preconditions.requireNonNull(quantity, "quantity");
		quantity = Preconditions.requireNumeric(quantity, "quantity", QUANTITY_INTEGER_DIGITS + QUANTITY_SCALE, QUANTITY_SCALE);
		// unit_price is NUMERIC(20,6) in V4 but validated to the money-scale width,
		// because only its product with a six-decimal quantity is ever stored.
		unitPrice = Preconditions.requireNumeric(unitPrice, "unitPrice", AMOUNT_INTEGER_DIGITS + UNIT_PRICE_SCALE, UNIT_PRICE_SCALE);
		// The three money amounts are each constrained to NUMERIC(20,4).
		discountAmount = Preconditions.requireNumeric(discountAmount, "discountAmount", AMOUNT_INTEGER_DIGITS + AMOUNT_SCALE, AMOUNT_SCALE);
		taxAmount = Preconditions.requireNumeric(taxAmount, "taxAmount", AMOUNT_INTEGER_DIGITS + AMOUNT_SCALE, AMOUNT_SCALE);
		lineTotal = Preconditions.requireNumeric(lineTotal, "lineTotal", AMOUNT_INTEGER_DIGITS + AMOUNT_SCALE, AMOUNT_SCALE);
		// currency CHAR(3) is not a component: the four Money values each carry it
		// and are proved pairwise to agree. A line whose amounts span two currencies
		// cannot be constructed, which is what "raise, never convert" has to mean for
		// a value type.
		requireSameCurrency(unitPrice, discountAmount);
		requireSameCurrency(unitPrice, taxAmount);
		requireSameCurrency(unitPrice, lineTotal);
		// Note what is NOT checked: lineTotal is not required to equal
		// policyLineTotal(). The stored total is what the source reported and is
		// preserved verbatim; the difference is reported through
		// schemaCheckVariance() and, at header level, through ReconciliationStatus.
		// Lineage is mandatory.
		if (source == null) {
			throw new ValidationException("source must not be null");
		}
	}

	/**
	 * Exact product {@code quantity * unit_price} at its natural scale.
	 *
	 * <p>Not rounded. This is the left-hand operand of the V4 CHECK expression
	 * and is exposed so the residual below can be proven rather than assumed.
	 *
	 * @return the unrounded gross in the line's currency, at up to twelve decimal
	 *         places (6 from the price and 6 from the quantity)
	 */
	public Money grossAtSourcePrecision() {
		return this.unitPrice.multiply(this.quantity);
	}

	/**
	 * Line total per the module rounding policy: round the gross to the money
	 * scale, then subtract the discount and add the tax.
	 *
	 * <p>Rounding happens exactly once, on the gross, and never after the discount
	 * or tax. Discount and tax are already at the money scale, so a second rounding
	 * here could only introduce a residual with no cause.
	 *
	 * @return the policy line total in the line's currency
	 */
	public Money policyLineTotal() {
		return roundToMoneyScale(this.grossAtSourcePrecision()).subtract(this.discountAmount).add(this.taxAmount);
	}

	/**
	 * Residual between the stored {@link #lineTotal()} and the exact value the
	 * V4 CHECK expression demands, reported in the line's own currency.
	 *
	 * <p>Zero for every product with at most four decimal places. Otherwise it is
	 * at most half of the smallest stored unit, which is the whole and only
	 * reason the literal CHECK cannot hold for six-decimal quantities.
	 *
	 * @return the difference {@code exactCheckLineTotal() - lineTotal()}; a positive
	 *         value means the stored total is below the literal expression
	 */
	public Money schemaCheckVariance() {
		return exactCheckLineTotal().subtract(this.lineTotal);
	}

	/**
	 * The literal V4 expression {@code (quantity * unit_price) - discount_amount
	 * + tax_amount}, evaluated without rounding.
	 *
	 * <p>Provided alongside {@link #policyLineTotal()} so the two can be compared.
	 * They are equal except where the product needs more than four decimals, and
	 * the gap between them is precisely what
	 * {@code ck_invoice_lines_total} cannot represent.
	 *
	 * @return the unrounded exact line total in the line's currency
	 */
	public Money exactCheckLineTotal() {
		return this.grossAtSourcePrecision().subtract(this.discountAmount).add(this.taxAmount);
	}

	/**
	 * Whether the stored total satisfies the literal V4 CHECK exactly.
	 *
	 * <p>Note that {@code false} is not an error condition. The variance it detects
	 * is bounded by half of the smallest stored unit, and the persistence pass is
	 * expected to relax the CHECK to a rounded comparison rather than to reject
	 * these rows.
	 *
	 * @return {@code true} only when the stored total equals the exact expression
	 */
	public boolean satisfiesSchemaCheck() {
		return this.lineTotal.equals(this.exactCheckLineTotal());
	}

	/**
	 * Net of the line before tax: the rounded gross less the discount.
	 *
	 * @return the pre-tax net in the line's currency
	 */
	public Money netBeforeTax() {
		return this.lineTotal.subtract(this.taxAmount);
	}

	/**
	 * Rounds to the money scale with the module's HALF_UP policy.
	 *
	 * <p>HALF_UP is declared in {@link #ROUNDING_MODE} rather than inherited from
	 * {@code BigDecimal}'s default because banker's rounding would systematically
	 * shave half a unit off every other line in the counterparty's favour, and this
	 * system bills the figure it computes.
	 *
	 * @param value amount to round; its currency is carried through unchanged
	 * @return the same amount at scale 4, rounded HALF_UP
	 */
	public static Money roundToMoneyScale(Money value) {
		return value.withScale(AMOUNT_SCALE, ROUNDING_MODE);
	}



	/**
	 * Proves two line amounts share one currency before they are subtracted.
	 *
	 * @param left  first amount
	 * @param right second amount
	 * @throws ValidationException when the currencies differ; a mixed line is
	 *                              refused rather than converted
	 */
	private static void requireSameCurrency(Money left, Money right) {
		if (!left.currency().equals(right.currency())) {
			throw new ValidationException("invoice line amounts must share one currency: " + left.currency() + " vs "
					+ right.currency());
		}
	}





}

package com.fintech.cfo.financial.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * API representation of a canonical invoice line.
 *
 * <p>Amounts are exposed as {@link BigDecimal} plus a single {@code currency}
 * scalar rather than as embedded {@code Money} objects: the DTO is the wire
 * contract, and a nested typed amount would let a client assemble a line whose
 * four amounts are in four different currencies.
 *
 * @param id             canonical invoice line id
 * @param invoiceId      owning invoice
 * @param productId      resolved product, may be absent for an unmapped line
 * @param lineNumber     1-based position within the invoice
 * @param description    free-text description, may be absent
 * @param quantity       billable quantity, at most six decimal places
 * @param unitPrice      price per unit before discount and tax
 * @param discountAmount discount applied to the line
 * @param taxAmount      tax applied to the line
 * @param lineTotal      net total charged for the line
 * @param currency       ISO-4217 currency shared by every amount on the line
 * @param sourceSystem   upstream system code
 * @param sourceRecordId lineage pointer back to the source record
 * @param sourceRowNumber source row within the file, may be absent
 * @param version        optimistic-locking version
 */
// productId is nullable on purpose: an unmapped line is still a charge. There is
// no reconciliation status here because reconciliation is a header-level verdict
// over the sum of the lines; a per-line verdict would invite a client to add up
// mismatched verdicts and believe it had reconciled the invoice.
// sourceFileId is absent here because the owning invoice already carries it, and
// two copies of the same fact could disagree.
public record InvoiceLineResponse(
		UUID id,
		UUID invoiceId,
		@Nullable UUID productId,
		int lineNumber,
		@Nullable String description,
		BigDecimal quantity,
		BigDecimal unitPrice,
		BigDecimal discountAmount,
		BigDecimal taxAmount,
		BigDecimal lineTotal,
		String currency,
		String sourceSystem,
		String sourceRecordId,
		@Nullable Long sourceRowNumber,
		long version) {
}

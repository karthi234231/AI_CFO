package com.fintech.cfo.financial.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * API representation of a canonical invoice, including its lines when the
 * caller asked for them.
 *
 * <p>The totals a source system reported are returned alongside the lines so a
 * reviewer can see both the figures that were trusted and the figures that can
 * be recomputed from the detail. Where the two disagree the invoice is reported
 * with its {@code ReconciliationStatus}; the discrepancy is never silently
 * corrected here.
 *
 * @param id                  canonical invoice id
 * @param organizationId      owning tenant
 * @param customerId          billed customer
 * @param accountingPeriodId  assigned reporting period, may be absent
 * @param invoiceNumber       human-readable invoice number
 * @param invoiceDate         issue date
 * @param dueDate             payment due date, may be absent
 * @param status              invoice lifecycle code
 * @param subtotalAmount      reported subtotal
 * @param taxAmount           reported tax
 * @param totalAmount         reported total
 * @param currency            ISO-4217 currency shared by every amount
 * @param externalKey         source-supplied identifier, may be absent
 * @param reconciliationStatus outcome of recomputing the totals from the lines
 * @param lines               invoice lines, empty when not requested
 * @param sourceSystem        upstream system code
 * @param sourceRecordId      lineage pointer back to the source record
 * @param sourceFileId        source file, may be absent
 * @param sourceRowNumber     source row within the file, may be absent
 * @param version             optimistic-locking version
 */
public record InvoiceResponse(
		UUID id,
		UUID organizationId,
		UUID customerId,
		@Nullable UUID accountingPeriodId,
		String invoiceNumber,
		LocalDate invoiceDate,
		@Nullable LocalDate dueDate,
		String status,
		BigDecimal subtotalAmount,
		BigDecimal taxAmount,
		BigDecimal totalAmount,
		String currency,
		@Nullable String externalKey,
		String reconciliationStatus,
		List<InvoiceLineResponse> lines,
		String sourceSystem,
		String sourceRecordId,
		@Nullable String sourceFileId,
		@Nullable Long sourceRowNumber,
		long version) {

	// Defensive copy. The list arrives from a repository result that may be reused
	// or cleared by the persistence layer; List.copyOf also rejects null elements
	// and produces an immutable list, so a client cannot mutate the projection
	// after the mapper returned it. An empty list is legal and means "lines were
	// not requested" - it is never null, so a client never has to null-check.
	public InvoiceResponse {
		lines = List.copyOf(lines);
	}
}

package com.fintech.cfo.financial.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * API representation of a canonical financial transaction.
 *
 * @param id                  canonical transaction id
 * @param organizationId      owning tenant
 * @param invoiceId           related invoice, may be absent
 * @param customerId          related customer, may be absent
 * @param accountingPeriodId  assigned reporting period, may be absent
 * @param transactionDate     business date of the movement
 * @param transactionType     nature of the movement
 * @param amount              signed amount as stored
 * @param currency            ISO-4217 currency of the amount
 * @param description         free-text description, may be absent
 * @param externalKey         source-supplied identifier, may be absent
 * @param sourceSystem        upstream system code
 * @param sourceRecordId      lineage pointer back to the source record
 * @param sourceFileId        source file, may be absent
 * @param sourceRowNumber     source row within the file, may be absent
 * @param version             optimistic-locking version
 */
// amount is the signed value exactly as stored. It is never normalised to a
// magnitude and never flipped to match the type's expected sign: an inverted sign
// from a source is an audit finding, and a response that hid it would make the
// finding unreproducible from the row itself. transactionType is the code only -
// the cash-flow classification is the consumer's decision, so that a reporting
// rule can change without a data migration.
public record TransactionResponse(
		UUID id,
		UUID organizationId,
		@Nullable UUID invoiceId,
		@Nullable UUID customerId,
		@Nullable UUID accountingPeriodId,
		LocalDate transactionDate,
		String transactionType,
		BigDecimal amount,
		String currency,
		@Nullable String description,
		@Nullable String externalKey,
		String sourceSystem,
		String sourceRecordId,
		@Nullable String sourceFileId,
		@Nullable Long sourceRowNumber,
		long version) {
}

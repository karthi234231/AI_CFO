package com.fintech.cfo.financial.mapper;

import java.math.BigDecimal;
import java.util.UUID;

import org.mapstruct.MapperConfig;
import org.mapstruct.Named;

import com.fintech.cfo.financial.enums.InvoiceStatus;
import com.fintech.cfo.financial.enums.ReconciliationStatus;
import com.fintech.cfo.financial.enums.TransactionType;
import com.fintech.cfo.shared.domain.CurrencyCode;
import com.fintech.cfo.shared.domain.Money;
import com.fintech.cfo.shared.domain.OrganizationId;
import com.fintech.cfo.shared.domain.SourceReference;

/**
 * Shared conversions between the shared domain value types and the flat wire
 * shapes the {@code financial.dto} records use.
 *
 * <p>Every method here exists because the source type is a hand-written
 * immutable class rather than a JavaBean or a record: MapStruct's default
 * accessor strategy recognises {@code getX()} and record component accessors,
 * but {@code CurrencyCode.value()}, {@code Money.amount()} and the five
 * {@code SourceReference} accessors are none of those, so implicit field
 * copying silently fails. Declaring each conversion explicitly is what keeps a
 * silently dropped amount from reaching a report.
 *
 * <p>The {@link SourceReference} accessors are {@link Named} because four of
 * them convert the same source type to the same target type; an unqualified
 * lookup would be ambiguous. The remaining conversions are unique by source
 * type and are qualified only where the call site reads better that way.
 *
 * <p>It is an interface despite being injected like a bean because
 * {@code @MapperConfig} types are never instantiated by MapStruct; they only
 * supply shared conversions through {@code uses = ...} on the real mappers.
 * The generated {@code *Impl} classes expect it as an injected helper, so a
 * concrete implementation of this interface must exist on the classpath for
 * Spring to wire them. That implementation lives in
 * {@code FinancialMappingSupportBean} (same package, package-private): it is
 * a separate top-level type rather than an inner class because MapStruct
 * treats a type referenced by {@code uses} as a helper source, so nesting it
 * inside the config would risk it being picked up as an additional mapping
 * provider.
 */
@MapperConfig
public interface FinancialMappingSupport {

	/**
	 * Unwraps the tenant id so the response carries a plain {@code UUID}.
	 *
	 * @param organizationId tenant value object
	 * @return the raw UUID written to {@code organization_id} in every V4 table
	 */
	@Named("organizationUuid")
	default UUID organizationUuid(OrganizationId organizationId) {
		return organizationId.value();
	}

	/**
	 * Renders a currency as its ISO-4217 string.
	 *
	 * <p>Explicitly a scalar rather than a nested currency object so the wire
	 * format does not change shape when a currency gains attributes.
	 *
	 * @param currency currency value object
	 * @return the ISO-4217 code
	 */
	@Named("currencyCode")
	default String currencyCode(CurrencyCode currency) {
		return currency.value();
	}

	/**
	 * Unwraps a {@link Money} to its numeric part.
	 *
	 * <p>The currency is dropped here on purpose: every DTO carries one sibling
	 * {@code currency} field, and a client that could assemble a line whose
	 * amounts span currencies is a bug this shape prevents.
	 *
	 * @param money amount bound to one currency
	 * @return the numeric value, at the scale the domain stored it
	 */
	@Named("amount")
	default BigDecimal amount(Money money) {
		return money.amount();
	}

	/**
	 * One of the five {@code SourceReference} projections. All five share the same
	 * source type and target type, which is exactly why they are
	 * {@link Named}: an unqualified lookup would be ambiguous.
	 *
	 * @param source lineage pointer
	 * @return the upstream system code
	 */
	@Named("sourceSystem")
	default String sourceSystem(SourceReference source) {
		return source.sourceSystem();
	}

	/**
	 * @param source lineage pointer
	 * @return the identifier of the record inside the upstream system
	 */
	@Named("sourceRecordId")
	default String sourceRecordId(SourceReference source) {
		return source.sourceRecordId();
	}

	/**
	 * @param source lineage pointer
	 * @return the ingested file id, or {@code null} when the record did not come
	 *         from a file
	 */
	@Named("sourceFileId")
	default String sourceFileId(SourceReference source) {
		return source.sourceFileId();
	}

	/**
	 * @param source lineage pointer
	 * @return the 1-based row within that file, or {@code null} when unavailable
	 */
	@Named("sourceRowNumber")
	default Long sourceRowNumber(SourceReference source) {
		return source.sourceRowNumber();
	}

	/**
	 * Renders the lifecycle status as its persisted code.
	 *
	 * <p>The predicates ({@code requiresSettlement}, {@code isTerminal}) are
	 * deliberately not projected: a client must read the code and apply its own
	 * policy rather than inherit this module's receivables rules.
	 *
	 * @param status invoice lifecycle status
	 * @return the code stored in {@code invoices.status}
	 */
	@Named("invoiceStatusCode")
	default String invoiceStatusCode(InvoiceStatus status) {
		return status.code();
	}

	/**
	 * @param transactionType nature of the movement
	 * @return the code stored in {@code financial_transactions.transaction_type}
	 */
	@Named("transactionTypeCode")
	default String transactionTypeCode(TransactionType transactionType) {
		return transactionType.code();
	}

	/**
	 * @param reconciliationStatus outcome of recomputing the totals
	 * @return the code reported on the invoice response
	 */
	@Named("reconciliationCode")
	default String reconciliationCode(ReconciliationStatus reconciliationStatus) {
		return reconciliationStatus.code();
	}

}

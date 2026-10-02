package com.fintech.cfo.financial.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import com.fintech.cfo.financial.dto.TransactionResponse;
import com.fintech.cfo.financial.model.FinancialTransaction;

/**
 * Maps the canonical {@link FinancialTransaction} record onto its API
 * representation.
 *
 * <p>The stored sign of the amount is returned verbatim. Normalising it to a
 * magnitude would hide a source that inverted a sign, which is an audit
 * finding rather than a formatting detail.
 */
@Mapper(config = FinancialMappingSupport.class, uses = FinancialMappingSupport.class)
public interface TransactionMapper {

	/**
	 * Currency of a transaction.
	 *
	 * <p>Sourced from the whole record rather than from {@code amount}, because the
	 * transaction has no separate currency component: the currency is carried by
	 * the {@code Money} amount, which is the only reason the two can never drift.
	 *
	 * @param transaction canonical transaction
	 * @return the ISO-4217 code of the stored amount
	 */
	@Named("transactionCurrency")
	default String transactionCurrency(FinancialTransaction transaction) {
		return transaction.currency().value();
	}

	// Project the canonical transaction.
	//
	// @param transaction canonical transaction record
	// @return the wire representation, lineage flattened into four fields
	@Mapping(target = "organizationId", source = "organizationId", qualifiedByName = "organizationUuid")
	// The type is projected as its code only. The cash-flow and sign-expectation
	// predicates are not exposed, so a client cannot derive a period's cash flow
	// from this response without applying its own classification rules.
	@Mapping(target = "transactionType", source = "transactionType", qualifiedByName = "transactionTypeCode")
	// The amount is unwrapped with its sign intact. TransactionMapper never
	// normalises to a magnitude and never applies the type's expected sign: a
	// source that inverted a sign is an audit finding, and a mapper that hid it
	// would make the finding unreproducible from the stored row.
	@Mapping(target = "amount", source = "amount", qualifiedByName = "amount")
	@Mapping(target = "currency", source = "transaction", qualifiedByName = "transactionCurrency")
	// Lineage is flattened so a reported movement can be traced to the exact
	// uploaded row without a second call.
	@Mapping(target = "sourceSystem", source = "source", qualifiedByName = "sourceSystem")
	@Mapping(target = "sourceRecordId", source = "source", qualifiedByName = "sourceRecordId")
	@Mapping(target = "sourceFileId", source = "source", qualifiedByName = "sourceFileId")
	@Mapping(target = "sourceRowNumber", source = "source", qualifiedByName = "sourceRowNumber")
	// Not projected: accountingPeriodId's absence is meaningful (the transaction
	// has not been placed in a period yet), so it is returned as null rather than
	// resolved to a default period by the mapper.
	TransactionResponse toResponse(FinancialTransaction transaction);

}

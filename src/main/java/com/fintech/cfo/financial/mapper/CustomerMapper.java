package com.fintech.cfo.financial.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.fintech.cfo.financial.dto.CustomerResponse;
import com.fintech.cfo.financial.model.Customer;

/**
 * Maps the canonical {@link Customer} record onto its API representation.
 *
 * <p>Every domain accessor on {@code Customer} is a record component or a
 * hand-written method, so each target is qualified explicitly through
 * {@link FinancialMappingSupport}; an unqualified mapping would leave the tenant,
 * currency and all four lineage fields null without failing the build.
 *
 * <p>Note what is <em>not</em> projected: the resolution key, and the external
 * key it is built from. Both are ingestion concerns, and exposing the resolved
 * identity on a read API would invite a client to conclude that a customer it
 * fetched by id is the only row a given source identifier maps to.
 */
@Mapper(config = FinancialMappingSupport.class, uses = FinancialMappingSupport.class)
public interface CustomerMapper {

	// Project the canonical customer.
	//
	// @param customer canonical customer record
	// @return the wire representation, lineage flattened into four fields
	@Mapping(target = "organizationId", source = "organizationId", qualifiedByName = "organizationUuid")
	@Mapping(target = "currency", source = "currency", qualifiedByName = "currencyCode")
	@Mapping(target = "sourceSystem", source = "source", qualifiedByName = "sourceSystem")
	@Mapping(target = "sourceRecordId", source = "source", qualifiedByName = "sourceRecordId")
	@Mapping(target = "sourceFileId", source = "source", qualifiedByName = "sourceFileId")
	@Mapping(target = "sourceRowNumber", source = "source", qualifiedByName = "sourceRowNumber")
	CustomerResponse toResponse(Customer customer);

}

package com.fintech.cfo.financial.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.fintech.cfo.financial.dto.ProductResponse;
import com.fintech.cfo.financial.model.Product;

/**
 * Maps the canonical {@link Product} record onto its API representation.
 *
 * <p>Same contract as {@code CustomerMapper}: tenant, currency and the four
 * lineage fields are qualified through {@link FinancialMappingSupport}, because
 * none of the domain accessors is a JavaBean getter and an implicit mapping would
 * null them without failing.
 *
 * <p>{@code currency} here is the catalogue price currency and is projected as a
 * label only. Nothing downstream may treat it as a rate or convert a billed line
 * with it.
 */
@Mapper(config = FinancialMappingSupport.class, uses = FinancialMappingSupport.class)
public interface ProductMapper {

	// Project the canonical product.
	//
	// @param product canonical catalogue entry
	// @return the wire representation, lineage flattened into four fields
	@Mapping(target = "organizationId", source = "organizationId", qualifiedByName = "organizationUuid")
	@Mapping(target = "currency", source = "currency", qualifiedByName = "currencyCode")
	@Mapping(target = "sourceSystem", source = "source", qualifiedByName = "sourceSystem")
	@Mapping(target = "sourceRecordId", source = "source", qualifiedByName = "sourceRecordId")
	@Mapping(target = "sourceFileId", source = "source", qualifiedByName = "sourceFileId")
	@Mapping(target = "sourceRowNumber", source = "source", qualifiedByName = "sourceRowNumber")
	ProductResponse toResponse(Product product);

}

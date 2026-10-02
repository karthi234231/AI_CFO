package com.fintech.cfo.financial.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.ValueMapping;

import com.fintech.cfo.financial.enums.SourceSystem;
import com.fintech.cfo.financial.enums.SourceSystemFamily;

/**
	 * Converts a {@link SourceSystem} into the {@link SourceSystemFamily} that
	 * governs how its amounts and dates are parsed.
 *
	 * <p>Declared with an exhaustive {@link ValueMapping} so that adding a source
 * system is a compile-time obligation rather than a silent fall-through to a
 * default profile that could misread Indian digit grouping as a decimal comma.
 *
 * <p>This is the only mapper in the module with {@code componentModel = "spring"}:
 * the others are pure projections that a caller may hold by hand, whereas the
 * family lookup is a stateless bean consulted once per ingested batch.
 */
@Mapper(componentModel = "spring")
public interface SourceSystemMapper {

	// Two systems share CLOUD_ACCOUNTING and GENERIC maps to UNKNOWN. A family is
	// therefore not one-to-one with a system, which is why the projection lives
	// here rather than being derived on the enum.
	//
	// Project a source system to its parsing profile.
	//
	// @param sourceSystem the system a batch arrived from
	// @return the family whose digit grouping and date format govern parsing
	@ValueMapping(source = "TALLY", target = "INDIAN_ERP")
	@ValueMapping(source = "SAP", target = "GLOBAL_ERP")
	@ValueMapping(source = "ZOHO_BOOK", target = "CLOUD_ACCOUNTING")
	@ValueMapping(source = "QUICKBOOKS", target = "CLOUD_ACCOUNTING")
	@ValueMapping(source = "GENERIC", target = "UNKNOWN")
	SourceSystemFamily toFamily(SourceSystem sourceSystem);

}

package com.fintech.cfo.contract.dto;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.fintech.cfo.contract.model.CommercialRule;
import com.fintech.cfo.contract.model.Contract;
import com.fintech.cfo.contract.model.ContractTerm;
import com.fintech.cfo.contract.model.DiscountTerm;
import com.fintech.cfo.contract.model.PricingTerm;

/**
 * The whole question being asked: which commercial terms of this contract applied
 * to this product, for this customer, on this date?
 *
 * <p>Bundled as one value so that the question, and therefore the input checksum,
 * has a single unambiguous shape. The term collections are supplied already narrowed
 * to one contract by the caller; this module holds no repository and queries
 * nothing.
 *
 * <p>{@code productId} and {@code customerId} are null for a contract-wide
 * question. Null collections are normalised to empty, so "no terms of that kind"
 * and "not supplied" cannot produce two different checksums for the same
 * commercial position.
 */
public record EffectiveTermsQuery(
		Contract contract,
		List<ContractTerm> contractTerms,
		List<PricingTerm> pricingTerms,
		List<DiscountTerm> discountTerms,
		List<CommercialRule> commercialRules,
		@Nullable UUID productId,
		@Nullable UUID customerId,
		LocalDate asOfDate) implements Serializable {

	public EffectiveTermsQuery {
		Objects.requireNonNull(contract, "contract must not be null");
		Objects.requireNonNull(asOfDate, "asOfDate must not be null");
		contractTerms = contractTerms == null ? List.of() : List.copyOf(contractTerms);
		pricingTerms = pricingTerms == null ? List.of() : List.copyOf(pricingTerms);
		discountTerms = discountTerms == null ? List.of() : List.copyOf(discountTerms);
		commercialRules = commercialRules == null ? List.of() : List.copyOf(commercialRules);
	}

	/**
	 * A contract-wide question: the published defaults, with no product or customer
	 * scoping.
	 */
	public static EffectiveTermsQuery forContract(Contract contract, LocalDate asOfDate, List<ContractTerm> contractTerms,
			List<PricingTerm> pricingTerms, List<DiscountTerm> discountTerms, List<CommercialRule> commercialRules) {
		return new EffectiveTermsQuery(contract, contractTerms, pricingTerms, discountTerms, commercialRules, null, null,
				asOfDate);
	}

	/**
	 * Stable identification of the question for the input checksum.
	 */
	public String queryKey() {
		// A single pipe-delimited string rather than a structured hash input. The "-"
		// placeholder is what keeps an absent product id distinguishable from a UUID
		// whose text happens to be "-", so a contract-wide question and a scoped one
		// cannot produce the same key for the same commercial position.
		return this.contract.id() + "|" + (this.productId == null ? "-" : this.productId) + "|"
				+ (this.customerId == null ? "-" : this.customerId) + "|" + this.asOfDate;
	}

}

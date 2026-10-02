package com.fintech.cfo.contract.dto;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import com.fintech.cfo.contract.enums.ContractTermType;
import com.fintech.cfo.contract.model.CommercialRule;
import com.fintech.cfo.contract.model.Contract;
import com.fintech.cfo.contract.model.ContractTerm;
import com.fintech.cfo.contract.model.DiscountTerm;
import com.fintech.cfo.contract.model.PricingTerm;

/**
 * Everything a calculation used, resolved for one date.
 *
 * <p>This is the reproducibility record. It holds the whole in-force set, not just
 * the winners, in precedence order, plus an {@code inputChecksum} over all of it.
 * A stored run can therefore be re-checked by re-resolving the same terms and
 * comparing checksums, without keeping a copy of every term row alongside every
 * calculation.
 *
 * <p>A null {@code pricingTerm} is a hard error at resolution time, not a possible
 * state of this record. {@code discountTerm} and {@code contractTerms} are
 * legitimately empty.
 *
 * @param contract the contract the terms were resolved against
 * @param asOfDate date resolved for
 * @param contractTerms clauses in force, in precedence order
 * @param pricingTerm price line in force, never null
 * @param discountTerm discount line in force, null when none applied
 * @param commercialRules rules in force, in precedence order
 * @param inputChecksum SHA-256 over the canonical form of every input
 */
public record EffectiveTerms(
		Contract contract,
		LocalDate asOfDate,
		List<ContractTerm> contractTerms,
		PricingTerm pricingTerm,
		@Nullable DiscountTerm discountTerm,
		List<CommercialRule> commercialRules,
		String inputChecksum) implements Serializable {

	public EffectiveTerms {
		Objects.requireNonNull(contract, "contract must not be null");
		Objects.requireNonNull(asOfDate, "asOfDate must not be null");
		Objects.requireNonNull(pricingTerm, "pricingTerm must not be null");
		Objects.requireNonNull(commercialRules, "commercialRules must not be null");
		Objects.requireNonNull(inputChecksum, "inputChecksum must not be null");
		contractTerms = contractTerms == null ? List.of() : List.copyOf(contractTerms);
		commercialRules = List.copyOf(commercialRules);
	}

	/**
	 * The clause of a given type that was in force, if any. A contract routinely
	 * has several clause types in force at once.
	 */
	public Optional<ContractTerm> contractTerm(ContractTermType termType) {
		Objects.requireNonNull(termType, "termType must not be null");
		return this.contractTerms.stream().filter(term -> term.termType().equals(termType)).findFirst();
	}

	public boolean hasDiscountTerm() {
		return this.discountTerm != null;
	}

}

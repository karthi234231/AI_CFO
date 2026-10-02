package com.fintech.cfo.contract.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.fintech.cfo.contract.enums.ContractTermType;
import com.fintech.cfo.contract.model.Contract;
import com.fintech.cfo.contract.model.ContractTerm;

/**
 * Resolution of narrative contract clauses by type and as-of date.
 *
 * <p>Stateless and deterministic: the as-of date is supplied by the caller and no
 * clock is read, so a historical calculation re-derives the same clause.
 */
public final class ContractTermService {

	private final EffectiveTermResolver resolver;

	public ContractTermService() {
		this(new EffectiveTermResolver());
	}

	public ContractTermService(EffectiveTermResolver resolver) {
		this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
	}

	/**
	 * The clause of a given type in force on a date, or empty.
	 */
	public Optional<ContractTerm> contractTermInForce(Contract contract, List<ContractTerm> contractTerms,
			ContractTermType termType, LocalDate asOfDate) {
		this.resolver.requireContractInForce(contract, asOfDate);
		Objects.requireNonNull(termType, "termType must not be null");
		Objects.requireNonNull(contractTerms, "contractTerms must not be null");
		// Identity rather than equals, valid because ContractTermType is a sealed set
		// of singletons whose instances are all the same object. The list is taken
		// as already narrowed to this contract; contractTermsInForce is the variant
		// that applies the contractId predicate itself.
		return this.resolver.findInForce(contractTerms, asOfDate, term -> term.termType() == termType);
	}

	/**
	 * Every clause in force on a date for the contract, in precedence order.
	 */
	public List<ContractTerm> contractTermsInForce(Contract contract, List<ContractTerm> contractTerms,
			LocalDate asOfDate) {
		this.resolver.requireContractInForce(contract, asOfDate);
		Objects.requireNonNull(contractTerms, "contractTerms must not be null");
		// Scoped to this contract by id, then filtered and sorted by the resolver. A
		// clause naming another contract is dropped rather than returned: that
		// disagreement is a defect, not a near miss.
		return this.resolver.inForceOn(contractTerms, asOfDate, term -> term.contractId().equals(contract.id()));
	}
}

package com.fintech.cfo.contract.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.fintech.cfo.contract.dto.DiscountEvaluation;
import com.fintech.cfo.contract.dto.EffectiveTerms;
import com.fintech.cfo.contract.dto.EffectiveTermsQuery;
import com.fintech.cfo.contract.dto.ResolvedPrice;
import com.fintech.cfo.contract.enums.ContractTermType;
import com.fintech.cfo.contract.enums.PricingType;
import com.fintech.cfo.contract.model.Contract;
import com.fintech.cfo.contract.model.ContractTerm;
import com.fintech.cfo.contract.model.DiscountTerm;
import com.fintech.cfo.contract.model.PricingTerm;
import com.fintech.cfo.shared.domain.Money;
import com.fintech.cfo.shared.exception.BusinessRuleException;

/**
 * Commercial-term resolution for one contract, one product, one customer and
 * one date.
 *
 * <p>Every method takes the as-of date explicitly and reads no clock, so a
 * calculation performed eighteen months ago re-derives identically. That is the
 * reason {@code term_version} exists in V5, and it is why no default price is
 * ever substituted: a fabricated price would be indistinguishable from a real
 * one on re-run.
 *
 * <p><strong>Which currency applies.</strong> A contract is denominated in one
 * currency ({@code contracts.currency NOT NULL}) and its price lines in another
 * ({@code pricing_terms.currency NOT NULL}). In a real contract those agree. If
 * they do not, this module raises {@link BusinessRuleException} rather than
 * converting: FX belongs to a separately audited component that does not exist
 * yet, and a price converted here would be unreproducible.
 *
 * <p>Stateless, constructor-injected. The no-argument constructor exists for
 * callers with no reason to substitute the collaborators.
 */
public final class ContractService {

	private final EffectiveTermResolver resolver;
	private final TermSelector selector;
	private final PriceResolutionService pricing;
	private final DiscountService discount;
	private final EffectiveTermsService effectiveTerms;

	public ContractService() {
		EffectiveTermResolver effectiveResolver = new EffectiveTermResolver();
		TermSelector effectiveSelector = new TermSelector(effectiveResolver);
		this.resolver = effectiveResolver;
		this.selector = effectiveSelector;
		this.pricing = new PriceResolutionService(effectiveResolver, effectiveSelector);
		this.discount = new DiscountService(effectiveSelector);
		this.effectiveTerms = new EffectiveTermsService(effectiveResolver, effectiveSelector, this.pricing);
	}

	public ContractService(EffectiveTermResolver resolver, PriceResolutionService pricing, DiscountService discount) {
		EffectiveTermResolver effectiveResolver = Objects.requireNonNull(resolver, "resolver must not be null");
		this.resolver = effectiveResolver;
		this.selector = new TermSelector(effectiveResolver);
		this.pricing = Objects.requireNonNull(pricing, "pricing must not be null");
		this.discount = Objects.requireNonNull(discount, "discount must not be null");
		this.effectiveTerms = new EffectiveTermsService(effectiveResolver, this.selector, this.pricing);
	}

	/**
	 * The pricing term in force for a product, customer and date.
	 *
	 * @throws BusinessRuleException when the contract supplies nothing for that
	 * product on that date
	 */
	public PricingTerm pricingTermInForce(Contract contract, List<PricingTerm> pricingTerms, UUID productId,
			UUID customerId, LocalDate asOfDate) {
		Contract inForce = this.resolver.requireContractInForce(contract, asOfDate);
		PricingTerm term = this.selector
				.selectInForce(pricingTerms, productId, customerId, asOfDate)
				// Currency eligibility is filtered after selection and then re-tested,
				// so a term denominated elsewhere is skipped and the next candidate is
				// considered rather than the whole lookup failing.
				.filter(candidate -> candidate.currency().equals(inForce.currency()))
				.orElseThrow(() -> new BusinessRuleException("no pricing term in force for contract "
						+ inForce.contractNumber() + " in " + inForce.currency().value() + " on " + asOfDate));
		return term;
	}

	/**
	 * The discount term in force for a product, customer and date, or empty.
	 * Absence is legitimate: most invoices carry no discount, and that is
	 * reported as "no discount applied" rather than as an error or a silent zero.
	 *
	 * <p>A {@link com.fintech.cfo.contract.enums.DiscountType#PERCENTAGE} term has
	 * no currency of its own and is always eligible. A fixed-amount term is only
	 * eligible in the contract's currency; a fixed discount denominated
	 * elsewhere would need an FX rate this module does not have, so it is
	 * skipped and the next candidate is considered rather than misapplied.
	 */
	public Optional<DiscountTerm> discountTermInForce(Contract contract, List<DiscountTerm> discountTerms,
			UUID productId, UUID customerId, LocalDate asOfDate) {
		Contract inForce = this.resolver.requireContractInForce(contract, asOfDate);
		return this.selector.candidatesInForce(discountTerms, productId, customerId, asOfDate)
				.stream()
				// A null currency is a PERCENTAGE term, which is always applicable; a
				// non-null one must equal the contract's. Filtering the candidates
				// rather than only the winner means a foreign-currency discount does
				// not mask a valid one ranked below it.
				.filter(candidate -> candidate.currency() == null || candidate.currency().equals(inForce.currency()))
				.findFirst();
	}

	/**
	 * The clause of a given type in force on a date, or empty.
	 */
	public Optional<ContractTerm> contractTermInForce(Contract contract, List<ContractTerm> contractTerms,
			ContractTermType termType, LocalDate asOfDate) {
		this.resolver.requireContractInForce(contract, asOfDate);
		Objects.requireNonNull(termType, "termType must not be null");
		// No contractId predicate here: the caller has already narrowed the list, and
		// contractTermInForce is the typed convenience over a supplied collection
		// rather than a lookup across a tenant's clauses.
		return this.resolver.findInForce(contractTerms, asOfDate, term -> term.termType() == termType);
	}

	/**
	 * Resolves the price to charge: the term's own price, or a caller-supplied
	 * candidate for a {@link PricingType#TIERED} row, always clamped into the
	 * term's {@code price_minimum}/{@code price_maximum} bounds.
	 *
	 * <p>A null {@code candidateUnitPrice} means "no candidate was offered", which
	 * is the ordinary case for a row that publishes its own price, and resolves
	 * through {@link PriceResolutionService#resolve}. Passing a null through to
	 * {@link PriceResolutionService#clampTo} instead would fail on a
	 * {@code Objects.requireNonNull} for a perfectly valid request - the very shape
	 * this method's own javadoc describes as supported, and the same shape
	 * {@link EffectiveTermsService#resolvePrice} already branches on.
	 */
	public ResolvedPrice resolveUnitPrice(Contract contract, List<PricingTerm> pricingTerms, UUID productId,
			UUID customerId, LocalDate asOfDate, @Nullable Money candidateUnitPrice) {
		PricingTerm term = pricingTermInForce(contract, pricingTerms, productId, customerId, asOfDate);
		if (candidateUnitPrice == null) {
			return this.pricing.resolve(term, contract.currency(), asOfDate);
		}
		return this.pricing.clampTo(term, candidateUnitPrice, contract.currency(), asOfDate);
	}

	/**
	 * Applies the discount in force to a gross amount.
	 */
	public DiscountEvaluation evaluateDiscount(Contract contract, List<DiscountTerm> discountTerms, UUID productId,
			UUID customerId, LocalDate asOfDate, Money grossAmount) {
		Objects.requireNonNull(grossAmount, "grossAmount must not be null");
		DiscountTerm term = discountTermInForce(contract, discountTerms, productId, customerId, asOfDate)
				.orElse(null);
		return this.discount.evaluate(grossAmount, term, asOfDate);
	}

	/**
	 * Full reproducibility record for a query: every term in force, the winners,
	 * and a SHA-256 checksum over the whole input set.
	 */
	public EffectiveTerms resolveEffectiveTerms(EffectiveTermsQuery query) {
		return this.effectiveTerms.resolve(query);
	}

	/**
	 * Whether a contract may supply terms on a date, without raising.
	 */
	public boolean canSupplyTermsOn(Contract contract, LocalDate asOfDate) {
		return contract != null && asOfDate != null && contract.canSupplyTermsOn(asOfDate);
	}

	/**
	 * Exposes the resolver so the orchestrator reuses exactly these as-of
	 * semantics instead of re-implementing them.
	 */
	public EffectiveTermResolver resolver() {
		return this.resolver;
	}

}

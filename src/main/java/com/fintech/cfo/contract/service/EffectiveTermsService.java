package com.fintech.cfo.contract.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import com.fintech.cfo.contract.dto.EffectiveTerms;
import com.fintech.cfo.contract.dto.EffectiveTermsQuery;
import com.fintech.cfo.contract.dto.ResolvedPrice;
import com.fintech.cfo.contract.model.CommercialRule;
import com.fintech.cfo.contract.model.Contract;
import com.fintech.cfo.contract.model.ContractTerm;
import com.fintech.cfo.contract.model.DiscountTerm;
import com.fintech.cfo.contract.model.PricingTerm;
import com.fintech.cfo.contract.model.VersionedTerm;
import com.fintech.cfo.shared.domain.Money;
import com.fintech.cfo.shared.exception.BusinessRuleException;

/**
 * Answers the module's one question, end to end: for this contract, this product,
 * this customer and this date, which terms were in force?
 *
 * <p>The full set is returned, not only the winners, because the losers are the
 * evidence. A calculation that stored "price 12.40" cannot later show whether
 * 12.40 was the negotiated rate, the published default, or the ceiling clamping
 * something else; a calculation that stored the whole in-force set can.
 *
 * <p><strong>The checksum covers every candidate, not just the winner.</strong> Two
 * runs over the same question must produce the same checksum, and they can only do
 * that if the checksum covers the same data. Hashing the resolved answer alone
 * would let a superseding row that happens to be ignored silently change nothing
 * about the recorded evidence - so the checksum would keep matching while the data
 * underneath it moved, which is the exact failure an input checksum exists to
 * catch. Candidates are sorted by the resolver's precedence order before hashing,
 * so the checksum does not depend on the order rows arrived in.
 *
 * <p><strong>Failure is loud and specific.</strong> A contract that cannot supply
 * terms on the date, and a missing price, are both hard errors, because both would
 * otherwise be resolved by a default nobody agreed to. A missing discount is not an
 * error: most invoices carry none.
 *
 * <p>Stateless. Every term collection is supplied by the caller already narrowed to
 * one contract; this module has no repository and queries nothing.
 */
public final class EffectiveTermsService {

	private final EffectiveTermResolver resolver;

	private final TermSelector selector;

	private final PriceResolutionService priceResolutionService;

	public EffectiveTermsService(EffectiveTermResolver resolver, TermSelector selector,
			PriceResolutionService priceResolutionService) {
		this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
		this.selector = Objects.requireNonNull(selector, "selector must not be null");
		this.priceResolutionService = Objects.requireNonNull(priceResolutionService, "priceResolutionService must not be null");
	}

	/**
	 * Resolves the whole term set in force for the question.
	 *
	 * @throws BusinessRuleException if the contract cannot supply terms on the date,
	 * or if no price line applies
	 */
	public EffectiveTerms resolve(EffectiveTermsQuery query) {
		Objects.requireNonNull(query, "query must not be null");
		Contract contract = query.contract();
		LocalDate asOfDate = query.asOfDate();
		this.resolver.requireContractInForce(contract, asOfDate);

		List<ContractTerm> contractTerms = this.resolver
				.inForceOn(query.contractTerms(), asOfDate, term -> sameContract(term, contract));
		PricingTerm pricingTerm = requirePricingTerm(query, asOfDate);
		DiscountTerm discountTerm = this.selector
				.selectInForce(query.discountTerms(), query.productId(), query.customerId(), asOfDate).orElse(null);
		// Organisation-wide rules (null contract_id) and this contract's rules are
		// collected together and none is ranked above another: every applicable rule
		// has to be checked, and dropping the broader one would reduce coverage
		// silently.
		List<CommercialRule> commercialRules = this.resolver
				.inForceOn(query.commercialRules(), asOfDate, rule -> ruleApplies(rule, contract));

		return new EffectiveTerms(contract, asOfDate, contractTerms, pricingTerm, discountTerm, commercialRules,
				checksum(query, contractTerms, pricingTerm, discountTerm, commercialRules));
	}

	/**
	 * Resolves and prices in one step.
	 *
	 * @param offeredPrice price to clamp into the resolved band, or null to use the
	 * price the term itself publishes
	 */
	public ResolvedPrice resolvePrice(EffectiveTermsQuery query, @Nullable Money offeredPrice) {
		Objects.requireNonNull(query, "query must not be null");
		EffectiveTerms effective = resolve(query);
		PricingTerm term = effective.pricingTerm();
		if (offeredPrice == null) {
			return this.priceResolutionService.resolve(term, query.contract().currency(), query.asOfDate());
		}
		return this.priceResolutionService.clampTo(term, offeredPrice, query.contract().currency(), query.asOfDate());
	}

	/**
	 * The single price line in force, which resolution cannot proceed without.
	 *
	 * <p>Fatal when absent. Unlike a discount, there is no defensible default for a
	 * price: zero understates a revenue position and a remembered last price
	 * silently applies terms from a date nobody asked about.
	 */
	private PricingTerm requirePricingTerm(EffectiveTermsQuery query, LocalDate asOfDate) {
		PricingTerm term = this.selector
				.selectInForce(query.pricingTerms(), query.productId(), query.customerId(), asOfDate)
				.orElseThrow(() -> new BusinessRuleException("no pricing term in force on " + asOfDate + " for contract "
						+ query.contract().contractNumber()));
		return term;
	}

	/**
	 * A SHA-256 over the canonical form of every candidate that was considered.
	 *
	 * <p>Order-independent by construction: each collection is sorted into the
	 * resolver's precedence order first, so two runs over the same rows in a
	 * different order hash alike, while any change to a row's content, version or
	 * window changes the hash.
	 */
	private static String checksum(EffectiveTermsQuery query, List<ContractTerm> contractTerms, PricingTerm pricingTerm,
			DiscountTerm discountTerm, List<CommercialRule> commercialRules) {
		StringBuilder builder = new StringBuilder(512);
		// The question is hashed first, so two runs of the same terms on a different
		// date, product or customer cannot share a digest even if the term set
		// happens to be identical.
		builder.append(query.queryKey()).append('\n');
		// Every candidate, not just the winners. A superseding row that was
		// considered and then ignored changes this digest; hashing the resolved
		// answer alone would let it change nothing about the recorded evidence.
		appendAll(builder, "contractTerms", contractTerms);
		// The *full* supplied collections for the three term kinds, not the resolved
		// ones, and before scope filtering: a row scoped to another product is still
		// part of what the resolution was made against.
		appendAll(builder, "pricingTerms", query.pricingTerms());
		appendAll(builder, "discountTerms", query.discountTerms());
		appendAll(builder, "commercialRules", query.commercialRules());
		return InputChecksum.of(query.queryKey(), new InputChecksum.Group("effectiveTerms", List.of(builder.toString())));
	}

	private static void appendAll(StringBuilder builder, String label, List<? extends VersionedTerm> terms) {
		// Sorted by the resolver's own precedence comparator before hashing, so the
		// digest depends on the commercial position rather than on the order the
		// repository returned rows in. The label keeps the four collections from
		// being able to hash alike if one is empty and another is not.
		terms.stream()
				.sorted(EffectiveTermResolver.precedence())
				.forEach(term -> builder.append(label).append('=').append(term.canonical()).append('\n'));
	}

	/**
	 * A contract term only counts when it belongs to this contract. Rows for another
	 * contract are not "nearly applicable", and a term row whose
	 * {@code contract_id} disagrees with the contract being resolved is a defect
	 * worth refusing rather than ignoring.
	 */
	private static boolean sameContract(ContractTerm term, Contract contract) {
		return term.contractId().equals(contract.id());
	}

	/**
	 * A rule applies when it is this contract's or, having no {@code contract_id},
	 * the organisation's. V5 allows both on the same date and the narrower one is
	 * not preferred here: every applicable rule must be checked.
	 */
	private static boolean ruleApplies(CommercialRule rule, Contract contract) {
		return rule.contractId() == null || rule.contractId().equals(contract.id());
	}

}
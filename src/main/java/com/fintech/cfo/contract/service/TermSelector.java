package com.fintech.cfo.contract.service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.fintech.cfo.contract.model.ScopedTerm;

/**
 * Chooses between the rows that compete for the same contract, date, product and
 * customer.
 *
 * <p>V5 lets a {@code pricing_terms} or {@code discount_terms} row be scoped to a
 * product, to a customer, to both, or to neither, and all four shapes can be valid
 * on the same date. Two filters therefore have to run before the version
 * tie-break can even start:
 *
 * <ol>
 * <li><strong>Eligibility.</strong> A row scoped to another product or another
 * customer is about someone else's deal and is dropped outright; it never
 * competes. A row with a null scope column is a contract-wide default and stays
 * eligible, so a published default is reached when no bespoke rate exists.</li>
 * <li><strong>Specificity.</strong> Among the survivors the most specific row
 * wins, <em>before</em> version is considered. A negotiated rate for this customer
 * is intended to override the contract's published default, and would lose to it if
 * version were compared first - which is exactly the mistake that makes an
 * agreed bespoke price silently revert to list.</li>
 * </ol>
 *
 * <p>Specificity ranks, highest first: product and customer, product only, customer
 * only, contract-wide default. Within a rank the resolver's tie-break decides.
 *
 * <p>Stateless. Window containment is delegated to {@link EffectiveTermResolver} so
 * the inclusive-boundary rule exists in exactly one place.
 */
public final class TermSelector {

	private final EffectiveTermResolver resolver;

	public TermSelector(EffectiveTermResolver resolver) {
		this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
	}

	/**
	 * All rows that could apply, most specific first, ties broken by version.
	 */
	public <T extends ScopedTerm> List<T> candidatesInForce(List<T> terms, @Nullable UUID productId,
			@Nullable UUID customerId, LocalDate asOfDate) {
		Objects.requireNonNull(terms, "terms must not be null");
		Objects.requireNonNull(asOfDate, "asOfDate must not be null");
		return this.resolver.inForceOn(terms, asOfDate,
				term -> isEligibleForProduct(term, productId) && isEligibleForCustomer(term, customerId))
				.stream()
				// Specificity first, version second. The order matters more than either
				// criterion: a negotiated customer rate with term_version 1 must still
				// beat a published default at term_version 3, or an agreed bespoke
				// price reverts to list the moment the default is amended.
				.sorted(bySpecificityThenVersion(productId, customerId))
				.toList();
	}

	/**
	 * The single applicable row, or empty. Empty means "this contract says nothing
	 * about that product on that date", which callers must handle explicitly rather
	 * than by defaulting.
	 */
	public <T extends ScopedTerm> Optional<T> selectInForce(List<T> terms, @Nullable UUID productId,
			@Nullable UUID customerId, LocalDate asOfDate) {
		return candidatesInForce(terms, productId, customerId, asOfDate).stream().findFirst();
	}

	private <T extends ScopedTerm> Comparator<T> bySpecificityThenVersion(@Nullable UUID productId,
			@Nullable UUID customerId) {
		// Negated so that "most specific" sorts first under a comparator that
		// otherwise orders ascending; the delegation to the resolver's comparator
		// keeps the version tie-break identical to the one used for unscoped terms.
		return Comparator.comparingInt((T term) -> -specificity(term, productId, customerId))
				.thenComparing(EffectiveTermResolver.precedence());
	}

	/**
	 * How precisely a row targets the request: 3 product and customer, 2 product
	 * only, 1 customer only, 0 the contract-wide default.
	 *
	 * <p>Product and customer both match scores higher than either alone, so a row
	 * negotiated for one customer on one product beats a row that happens to be
	 * version-later at either broader scope. A row whose scope columns do not match
	 * the request scores 0 here, but it has already been dropped as ineligible -
	 * it never reaches the sort at all.
	 */
	private static int specificity(ScopedTerm term, @Nullable UUID productId, @Nullable UUID customerId) {
		boolean productSpecific = term.productId() != null && term.productId().equals(productId);
		boolean customerSpecific = term.customerId() != null && term.customerId().equals(customerId);
		if (productSpecific && customerSpecific) {
			return 3;
		}
		if (productSpecific) {
			return 2;
		}
		if (customerSpecific) {
			return 1;
		}
		return 0;
	}

	/**
	 * Eligibility on the product axis. A null scope column means "any product", so
	 * it stays eligible and can act as the contract-wide default; a non-null column
	 * naming a different product is about someone else's deal and is dropped rather
	 * than ranked. {@code equals} on UUID is used rather than {@code ==} so the
	 * comparison is by value and does not depend on how the ids were deserialised.
	 */
	private static boolean isEligibleForProduct(ScopedTerm term, @Nullable UUID productId) {
		return term.productId() == null || term.productId().equals(productId);
	}

	/**
	 * Eligibility on the customer axis, identical in shape to the product check.
	 *
	 * <p>Note that a request with a null {@code customerId} - a contract-wide
	 * question - makes every customer-scoped row ineligible, which is correct: a
	 * rate negotiated with one customer is not a published price for the tenant.
	 */
	private static boolean isEligibleForCustomer(ScopedTerm term, @Nullable UUID customerId) {
		return term.customerId() == null || term.customerId().equals(customerId);
	}

}

package com.fintech.cfo.contract.model;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * A term that may be scoped to a product and/or a customer.
 *
 * <p>V5 gives {@code pricing_terms} and {@code discount_terms} both an optional
 * {@code product_id} and {@code customer_id}. A null in either column means "any",
 * so a contract can publish one default price and then override it for particular
 * products or customers. That is a scoping rule, not a schema detail, so it is
 * modelled once here rather than in each term type.
 */
public interface ScopedTerm extends VersionedTerm {

	@Nullable
	UUID productId();

	@Nullable
	UUID customerId();

}

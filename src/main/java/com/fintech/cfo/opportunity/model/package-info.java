/**
 * The Economic Opportunity Record and everything that hangs off it.
 *
 * <p>{@link com.fintech.cfo.opportunity.model.EconomicOpportunity} is the central
 * product object and is a {@code final class} rather than a record because it owns
 * invariants a record's canonical constructor cannot express: the affected count must
 * match the number of contributions, every contribution must be in the record's own
 * currency, and a record that cannot yet carry a monetary claim must not hold one. All
 * three protect the same property - that a figure a user reads can be reconstructed
 * from the rows underneath it.
 *
 * <p>The remaining types are records. {@link com.fintech.cfo.opportunity.model.OpportunityImpact}
 * is the affected-transaction row, and its
 * {@link com.fintech.cfo.opportunity.model.OpportunityImpact#from} factory is the only
 * place a caller-supplied contribution is turned into a stored row, so the identity
 * and tenancy columns can never be supplied by a caller.
 *
 * <p>{@link com.fintech.cfo.opportunity.model.EvidenceReference} stores a pointer and
 * a checksum, never document content, which is what makes a reported amount traceable
 * back to a source row without this module ever holding a file.
 *
 * <p>All money is {@code BigDecimal} via {@code com.fintech.cfo.shared.domain.Money}.
 * No {@code double} or {@code float} appears anywhere in this package.
 */
@NullMarked
package com.fintech.cfo.opportunity.model;

import org.jspecify.annotations.NullMarked;
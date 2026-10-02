/**
 * Closed value sets for the Economic Opportunity Record.
 *
 * <p>{@link com.fintech.cfo.opportunity.enums.OpportunityStatus},
 * {@link com.fintech.cfo.opportunity.enums.OpportunityType},
 * {@link com.fintech.cfo.opportunity.enums.ValidationStatus},
 * {@link com.fintech.cfo.opportunity.enums.ReviewDecision} and
 * {@link com.fintech.cfo.opportunity.enums.FindingType} are sealed interfaces of
 * records, so every {@code switch} over them is exhaustiveness-checked and a new
 * variant cannot be added without deciding how it behaves everywhere it is
 * consumed. The lifecycle guard in
 * {@link com.fintech.cfo.opportunity.enums.OpportunityStatus#legalSuccessors} is
 * written as one such {@code switch}, which is why adding a lifecycle state forces
 * the transition rules to be restated rather than silently defaulting to "allowed".
 *
 * <p>Each sealed set implements {@link com.fintech.cfo.opportunity.enums.CodedEnum}:
 * the variant's {@code code()} is the stable value written to and read from the V8
 * {@code VARCHAR} columns, so the persisted string and the modelled value cannot
 * drift apart. {@link com.fintech.cfo.opportunity.enums.CodedEnum#fromCode}
 * resolution fails loudly rather than guessing, because a stored status this module
 * cannot resolve means the schema and the code have diverged.
 *
 * <p>{@code OpportunityConfidence}, {@code OpportunityPriority} and
 * {@code FindingSeverity} remain enums: they name a stored value with no
 * per-variant behaviour. Their ordering is expressed by explicit methods
 * ({@link com.fintech.cfo.opportunity.enums.OpportunityConfidence#isLessConfidentThan},
 * {@link com.fintech.cfo.opportunity.enums.OpportunityPriority#isAtLeast},
 * {@link com.fintech.cfo.opportunity.enums.FindingSeverity#isAtLeast}) rather than
 * by {@link Enum#ordinal()}, so a reordering of the declarations cannot silently
 * change which rows win.
 */
@NullMarked
package com.fintech.cfo.opportunity.enums;

import org.jspecify.annotations.NullMarked;
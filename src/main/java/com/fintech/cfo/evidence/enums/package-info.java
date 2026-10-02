/**
 * Closed sets and coded subject types of the evidence and lineage module.
 *
 * <p>{@link com.fintech.cfo.evidence.enums.EvidenceType} and
 * {@link com.fintech.cfo.evidence.enums.LineageRelationType} are enums because each
 * variant is a bare {@code VARCHAR} value in V7 with no behaviour beyond a column
 * width and a couple of yes/no predicates; both publish a stable {@code code()} and
 * resolve a stored value back without reflection, so an unknown code fails loudly
 * instead of being guessed at.
 *
 * <p>{@link com.fintech.cfo.evidence.enums.SourceType} is deliberately a final class
 * rather than an enum. V7 stores {@code lineage_nodes.node_type},
 * {@code evidence_references.from_type}/{@code to_type} and
 * {@code evidence_snapshots.subject_type} as free {@code VARCHAR(64)} strings with no
 * lookup table, and subjects outside this module legitimately appear in them. It
 * therefore fixes the five hops of the traceability chain as named constants and
 * accepts any other well-formed code, so the module owns the vocabulary it must
 * enforce without refusing to describe the outside world.
 *
 * <p><b>What these types can and cannot do on their own.</b> Every member here is
 * pure, deterministic and free of ambient state, which is what makes the whole graph
 * vocabulary testable without a container, a database or a clock. What none of them
 * can do is check that a referenced row exists. {@link
 * com.fintech.cfo.evidence.enums.SourceType} says a code is well formed; it cannot say
 * the investigation it names is real, and the {@link
 * com.fintech.cfo.evidence.enums.EvidenceType} predicates say what an evidence row must
 * carry; they cannot say the file it must carry is in the caller's tenant. Those checks
 * belong to the persistence and traversal passes, and the fact that the enums are
 * designed to be the single source of truth for them is the reason they are here and not
 * spread across the owning modules.
 *
 * <p>Two {@code @link} targets in this package point at types that do not exist yet -
 * a {@code LineageStep} that will shape the chain, and
 * {@code LineageService#requireTraceableToSource}. Both are deliberate forward
 * references to the contract the traversal pass must satisfy, and both are recorded as
 * such in the module's explain document rather than removed, because deleting the
 * statement of an invariant is not the way to make it true.
 */
@NullMarked
package com.fintech.cfo.evidence.enums;

import org.jspecify.annotations.NullMarked;

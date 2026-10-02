/**
 * The immutable shape of a proof, and of the graph that connects a claim to the row
 * it was computed from.
 *
 * <p>Every type here mirrors a V7 table column for column - {@code evidences},
 * {@code evidence_references}, {@code evidence_snapshots}, {@code lineage_nodes},
 * {@code lineage_edges} - with no {@code @Entity}, no lazy proxies and no framework
 * types, so adding JPA later is mechanical rather than a redesign. The column widths
 * and {@code NOT NULL} declarations of the migration are enforced in compact
 * constructors, because an invalid evidence row discovered at insert time is an
 * invalid evidence row discovered too late.
 *
 * <p>Two invariants are enforced here rather than documented and hoped for:
 *
 * <ul>
 * <li>An {@link com.fintech.cfo.evidence.model.Evidence} row whose
 * {@link com.fintech.cfo.evidence.enums.EvidenceType} demands a source must actually
 * carry the file and row. An unverifiable claim stored as evidence is worse than a
 * missing claim, because a reviewer will trust it.</li>
 * <li>A {@link com.fintech.cfo.evidence.model.LineageNode} at source level must carry
 * a {@link com.fintech.cfo.shared.domain.SourceReference}. That is the type the
 * financial records use, so one chain shape serves both and no second spelling of
 * "where did this come from" can drift out of step with the first.</li>
 * </ul>
 *
 * <p>No type in this package holds the bytes of a source document. Artifacts are held
 * as a {@link com.fintech.cfo.evidence.model.EvidenceArtifact} - a storage key, a
 * content type, a length and a checksum - because a copy of the file inside a business
 * row would be both undeletable and unverifiable at the same time.
 *
 * <p><b>Which of those invariants are enforced today, and which are stated.</b> The
 * first bullet is live: it is checked in {@link
 * com.fintech.cfo.evidence.model.Evidence}'s compact constructor, driven by
 * {@link com.fintech.cfo.evidence.enums.EvidenceType}'s two requirement predicates. The
 * second bullet is stated but not yet enforced, because {@link
 * com.fintech.cfo.evidence.model.LineageNode}, {@link
 * com.fintech.cfo.evidence.model.LineageEdge} and {@link
 * com.fintech.cfo.evidence.model.EvidenceSnapshot} are still placeholders; the graph
 * model that has to enforce it is designed here but does not exist. Saying so is the
 * point: an invariant nobody can check should be labelled as a contract rather than
 * presented as a guarantee.
 *
 * <p><b>Snapshots are the deliberate exception to the no-bytes rule.</b> V7 declares
 * {@code evidence_snapshots.content TEXT NOT NULL}, so a snapshot stores a rendering
 * inline. That is consistent with the rule rather than an exception to it: a snapshot
 * holds a <em>derived</em> view - the state of a subject at the moment a claim was made
 * - and never the source document it was derived from. The original bytes stay
 * addressable through the {@link com.fintech.cfo.evidence.model.SourceLocation} chain, so
 * nothing becomes unverifiable by being frozen.
 */
@NullMarked
package com.fintech.cfo.evidence.model;

import org.jspecify.annotations.NullMarked;

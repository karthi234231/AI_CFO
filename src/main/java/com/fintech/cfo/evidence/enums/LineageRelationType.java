package com.fintech.cfo.evidence.enums;

import java.util.List;

import com.fintech.cfo.shared.exception.ValidationException;

/**
 * The meaning of one {@code lineage_edges} row, persisted as
 * {@code lineage_edges.relation_type VARCHAR(48)} in V7.
 *
 * <p>An edge is read as {@code from <relation> to}. Each variant declares the
 * {@link SourceType} it is allowed to point at, which turns the traceability chain
 * from a convention into something the compiler and the graph walk can both check:
 * {@link com.fintech.cfo.evidence.model.LineageStep} refuses a
 * {@code DERIVED_FROM} edge that points at a canonical record, so a chain cannot be
 * recorded with its middle hops transposed.
 *
 * <p>{@link Direction} says which way along the chain the edge points. Only
 * {@link Direction#TOWARD_SOURCE} edges are followed when reconstructing where a
 * figure came from; {@link Direction#TOWARD_EVIDENCE} edges are followed when
 * assembling what can be shown to a reviewer. Keeping the two apart is why a trace
 * cannot accidentally walk a cycle and loop forever.
 */
public enum LineageRelationType implements CodedEnum {

	/** A calculation figure is attributed to the transactions that produced it. */
	DERIVED_FROM(SourceType.AFFECTED_TRANSACTION, Direction.TOWARD_SOURCE),

	/** An affected transaction was matched against the canonical record for the same fact. */
	RECONCILED_WITH(SourceType.CANONICAL_RECORD, Direction.TOWARD_SOURCE),

	/** A canonical record was normalized out of exactly one source row. */
	SOURCED_FROM(SourceType.SOURCE_ROW, Direction.TOWARD_SOURCE),

	/** A source row belongs to exactly one source file. */
	BELONGS_TO_FILE(SourceType.SOURCE_FILE, Direction.TOWARD_SOURCE),

	/** A later canonical record replaces an earlier one after re-normalization. */
	SUPERSEDES(SourceType.CANONICAL_RECORD, Direction.TOWARD_SOURCE),

	/** A claim is substantiated by a piece of evidence. */
	SUPPORTED_BY_EVIDENCE(SourceType.EVIDENCE, Direction.TOWARD_EVIDENCE),

	/** A claim is substantiated by a frozen snapshot of another subject. */
	SUPPORTED_BY_SNAPSHOT(SourceType.EVIDENCE_SNAPSHOT, Direction.TOWARD_EVIDENCE),

	/** An opportunity was quantified from one calculation result. */
	QUANTIFIED_BY(SourceType.CALCULATION_RESULT, Direction.TOWARD_CALCULATION),

	/** An opportunity or result was produced by one calculation run. */
	PRODUCED_BY(SourceType.CALCULATION, Direction.TOWARD_CALCULATION),

	/** A derived figure was computed from one specific calculation. */
	COMPUTED_FROM(SourceType.CALCULATION, Direction.TOWARD_CALCULATION);

	/** Width of {@code lineage_edges.relation_type} in V7. */
	public static final int MAX_CODE_LENGTH = 48;

	private final SourceType targetType;

	private final Direction direction;

LineageRelationType(SourceType targetType, Direction direction) {
		// A relation variant declares both the type it points at and the direction it
		// points. Either missing makes the variant unusable: an edge with no target
		// type cannot be checked by accepts(), and an edge with no direction cannot be
		// followed by the lineage walk, which is the only consumer of this enum.
		if (targetType == null || direction == null) {
			throw new IllegalStateException("a lineage relation must declare both its target type and its direction");
		}
		// Asserted at construction so a variant whose name is too long for the V7
		// column is refused at class-load time, before any row is ever written.
		if (name().length() > MAX_CODE_LENGTH) {
			throw new IllegalStateException(name() + " exceeds the V7 column width of " + MAX_CODE_LENGTH);
		}
		this.targetType = targetType;
this.direction = direction;
	}

	/**
	 * Every variant, in a fixed order that does not depend on declaration order or
	 * locale, so anything that renders the set renders it identically everywhere.
	 *
	 * @return an immutable list of all ten relations
	 */
	public static List<LineageRelationType> all() {
		return List.of(values());
	}

	/**
	 * Resolves a stored {@code relation_type} value.
	 *
	 * @param code value read from {@code lineage_edges.relation_type}
	 * @return the matching relation, never {@code null}
	 * @throws ValidationException if the value is blank or unknown
	 */
	public static LineageRelationType fromCode(String code) {
		String normalized = CodedEnum.normalise(code, "LineageRelationType");
		// Linear scan rather than a lazily built lookup map: the set has ten members,
		// so the map would cost more in class-initialisation complexity than it saves.
		for (LineageRelationType candidate : values()) {
			if (candidate.code().equals(normalized)) {
				candidate.requireColumnWidth(MAX_CODE_LENGTH);
				return candidate;
			}
		}
		// An unknown relation is refused rather than defaulted. The relation is what
		// says which hop an edge is, so resolving it to the wrong variant would put a
		// figure on a chain that never ran.
throw new ValidationException("unknown lineage relation type code: " + code);
	}

	/** Value written to and read from the column. */
	@Override
	public String code() {
		return this.name();
	}

	/**
	 * The subject type this edge is allowed to point at.
	 *
	 * <p>Checked by {@link #accepts(SourceType)} rather than trusted from the caller.
	 * Declaring it on the constant is what lets the shape of the chain be checked in
	 * one place rather than repeated in every writer of an edge.
	 *
	 * @return the {@link SourceType} the {@code to} side must have
	 */
	public SourceType targetType() {
		return this.targetType;
	}

	/**
	 * Which way along the chain this edge points.
	 *
	 * @return the traversal direction this edge participates in
	 */
	public Direction direction() {
		return this.direction;
	}

	/**
	 * Whether following this edge is part of reconstructing where a figure came from.
	 *
	 * <p>The single predicate a trace filters on. Because it is a property of the
	 * relation and not of the edge row, a graph that mixes evidence-directed edges
	 * into the source chain cannot accidentally send a trace sideways - and cannot
	 * revisit a node it has already proven, which is what bounds the walk.
	 *
	 * @return true only for the four relations of the required chain
	 */
	public boolean isTowardSource() {
		return this.direction == Direction.TOWARD_SOURCE;
	}

	/**
	 * Whether an edge of this relation may legally point at the given type.
	 *
	 * <p>Used by the chain builder rather than trusting the caller to know the shape of
	 * the chain. It cannot verify that the chain is <em>complete</em>, only that each
	 * individual edge is shaped correctly.
	 *
	 * @param type type on the {@code to} side of a proposed edge
	 * @return true when this relation declares exactly that target type
	 */
	public boolean accepts(SourceType type) {
		// Null-refused rather than treated as a mismatch, so a caller that forgot to
		// read a nullable column is told so instead of getting a quiet false.
		return type != null && this.targetType.equals(type);
	}

	/**
	 * The four hops of the required chain, in the order they must be recorded.
	 *
	 * <p>Exposed so the graph walk and the tests cannot disagree with each other about
	 * what "traceable" means.
	 *
	 * <p>The order is the chain read from the claim downwards: a calculation figure is
	 * attributed to transactions, each transaction is matched to a canonical record,
	 * the canonical record came out of a source row, and the row belongs to a file.
	 * Every edge in this list is {@link Direction#TOWARD_SOURCE}, which is the property
	 * that makes the traversal terminate on a source-level node.
	 *
	 * @return the required hops, head to tail
	 */
	public static List<LineageRelationType> traceabilityChain() {
		return List.of(DERIVED_FROM, RECONCILED_WITH, SOURCED_FROM, BELONGS_TO_FILE);
	}

	/**
	 * Which way an edge points relative to the chain.
	 *
	 * <p>Three directions rather than two because calculation-directed edges run
	 * <em>upward</em> out of a claim and are not part of the source walk at all. They
	 * are kept in the same closed set so that every edge in the graph has an explicitly
	 * declared direction and none defaults to "toward source".
	 */
	public enum Direction {

		/**
		 * Following the edge gets closer to the uploaded file. The only direction a
		 * trace follows when reconstructing provenance.
		 */
		TOWARD_SOURCE,

		/**
		 * Following the edge gets closer to the claim being substantiated. Followed
		 * when assembling what can be shown to a reviewer, which is the reverse
		 * question and therefore the reverse direction.
		 */
		TOWARD_EVIDENCE,

		/**
		 * Following the edge gets closer to the calculation that produced the claim.
		 * A third direction because the calculation run sits at the head of the graph
		 * and is reached by walking outwards from a result, not inwards from a file.
		 */
		TOWARD_CALCULATION
	}

}

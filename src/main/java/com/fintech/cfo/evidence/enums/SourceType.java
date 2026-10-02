package com.fintech.cfo.evidence.enums;

import java.util.List;
import java.util.Locale;

import com.fintech.cfo.shared.exception.ValidationException;

/**
 * The kind of thing a piece of evidence or a lineage node is about. Persisted as the
 * {@code VARCHAR(64)} {@code node_type} of {@code lineage_nodes}, the {@code from_type}
 * / {@code to_type} of {@code evidence_references}, and the {@code subject_type} of
 * {@code evidence_snapshots} in V7.
 *
 * <h2>Why a final class and not an enum</h2>
 *
 * <p>V7 declares those columns as free text with no lookup table, and subjects owned
 * by other modules legitimately appear in them: an investigation must be able to say
 * "this snapshot is about an investigation". A closed enum would make that
 * impossible without editing this module every time another module grows a subject,
 * which is exactly the coupling the module boundary is supposed to prevent. So the
 * five hops this module must be able to prove are fixed as named constants, and
 * {@link #of(String)} accepts any other well-formed code.
 *
 * <p>The result still cannot be nonsense: a code is upper-cased, trimmed, bounded to
 * the V7 column width and rejected when empty. What the type cannot do is validate
 * that an outside subject exists - it has no way to know, and inventing a foreign key
 * check here would be a lie about what this module can guarantee.
 *
 * <p>Declared as a final class rather than a record because it owns behaviour
 * ({@link #bearsSourceCoordinates()}, {@link #isSourceLevel()}) over a single validated
 * string, and because interning the well-known values keeps identity comparison
 * cheap and predictable in the graph walks.
 */
public final class SourceType {

	/** Width of {@code lineage_nodes.node_type} and friends in V7. */
	public static final int MAX_CODE_LENGTH = 64;

	// --- hop 1: the calculation that produced the figure ---
	/** A calculation run, e.g. {@code calculation_runs.id}. */
	public static final SourceType CALCULATION = new SourceType("CALCULATION");

	/** One result row of a run, e.g. {@code calculation_results.id}. */
	public static final SourceType CALCULATION_RESULT = new SourceType("CALCULATION_RESULT");

	/** A calculated amount attributed to specific transactions. */
	public static final SourceType AFFECTED_TRANSACTION = new SourceType("AFFECTED_TRANSACTION");

	// --- hop 2: the canonical record that carries the amount ---
	/** A normalized canonical record, e.g. an invoice line. */
	public static final SourceType CANONICAL_RECORD = new SourceType("CANONICAL_RECORD");

	// --- hop 3: the source row ---
	/** The exact row of the uploaded file the canonical record was normalized from. */
	public static final SourceType SOURCE_ROW = new SourceType("SOURCE_ROW");

	// --- hop 4: the source file ---
	/** The uploaded file itself, addressed by storage key and checksum. */
	public static final SourceType SOURCE_FILE = new SourceType("SOURCE_FILE");

	// --- evidence about the chain itself ---
	/** An {@code evidences} row. */
	public static final SourceType EVIDENCE = new SourceType("EVIDENCE");

	/** An {@code evidence_snapshots} row. */
	public static final SourceType EVIDENCE_SNAPSHOT = new SourceType("EVIDENCE_SNAPSHOT");

	// --- subjects owned by other modules ---
	/** An economic opportunity raised from a calculation. */
	public static final SourceType OPPORTUNITY = new SourceType("OPPORTUNITY");

	/** A finance investigation opened against an opportunity. */
	public static final SourceType INVESTIGATION = new SourceType("INVESTIGATION");

	private final String code;

	private SourceType(String code) {
		// A constant declared in this class must fit the V7 column it is persisted into,
		// because the constructor runs at class-load time and there is no later chance
		// to refuse it. An over-long vocabulary word would otherwise fail only at the
		// first write, by which point the constant is already part of the public API.
		if (code.isBlank() || code.length() > MAX_CODE_LENGTH) {
			throw new IllegalStateException("source type '" + code + "' cannot be stored in the V7 column");
		}
		this.code = code;
	}

	/**
	 * Accepts any code that the V7 {@code VARCHAR(64)} column could hold, and returns
	 * the interned instance when the code is one of the well-known types.
	 *
	 * <p>Interning is not an optimisation here, it is a correctness requirement: the
	 * lineage walk compares subject types for equality thousands of times per trace
	 * and must not depend on how many times {@code of} was called.
	 *
	 * @param code value read from the {@code VARCHAR(64)} column, in any case
	 * @return the interned instance when the code is well known, otherwise a new
	 *         validated instance carrying the normalised code
	 * @throws ValidationException if the code is blank or too long for the column
	 */
	public static SourceType of(String code) {
		if (code == null || code.isBlank()) {
			throw new ValidationException("source type must not be blank");
		}
		// Normalise first so a value typed in any case compares against the interned
		// constants, then look the code up among the well-known values before deciding
		// whether it is wide enough for the column. Checking the width only for the
		// "not well known" branch means a well-known code is returned as the interned
		// instance regardless of how it was cased.
		String normalized = code.trim().toUpperCase(Locale.ROOT);
		for (SourceType candidate : wellKnown()) {
			if (candidate.code.equals(normalized)) {
				return candidate;
			}
		}
		if (normalized.length() > MAX_CODE_LENGTH) {
			throw new ValidationException(
					"source type '" + normalized + "' exceeds the V7 column width of " + MAX_CODE_LENGTH);
		}
		return new SourceType(normalized);
	}

	/**
	 * The types this module fixes as vocabulary, in chain order from calculation down
	 * to source file, then the evidence types, then the foreign subjects.
	 *
	 * <p>A method rather than a constant because {@link LineageRelationType}'s enum
	 * constants hold these values; a static field would be read while
	 * {@code SourceType}'s own class initialisation is still running.
	 */
	public static List<SourceType> wellKnown() {
		// A fresh list each call rather than a shared static: the caller is free to
		// mutate it and this method must not be the owner of that decision.
		return List.of(CALCULATION, CALCULATION_RESULT, AFFECTED_TRANSACTION, CANONICAL_RECORD, SOURCE_ROW, SOURCE_FILE,
				EVIDENCE, EVIDENCE_SNAPSHOT, OPPORTUNITY, INVESTIGATION);
	}

	/** Value written to and read from the column. */
	public String code() {
		return this.code;
	}

	/**
	 * Whether this type is one of the types named above rather than an outside one.
	 *
	 * <p>Used to tell a subject this module can reason about from one it merely
	 * records. Equality against the interned constants is used deliberately, because
	 * a value that arrived as free text is only "well known" once it has been through
	 * {@link #of(String)} and matched back.
	 *
	 * @return true for the ten named constants
	 */
	public boolean isWellKnown() {
		for (SourceType candidate : wellKnown()) {
			if (candidate.code.equals(this.code)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether a node of this type must carry a {@link com.fintech.cfo.shared.domain.SourceReference}.
	 *
	 * <p>True exactly for the hops that physically touch uploaded data. A calculation
	 * or an opportunity carries no source coordinates of its own and must earn its
	 * traceability by pointing at something that does.
	 *
	 * <p>The obligation belongs to the <em>node type</em> rather than to whichever
	 * module owns the row, because this is the only place that knows the full set of
	 * hops: the enforcement point (an unimplemented {@code LineageNode}) will call it,
	 * and an owning module cannot be trusted to remember for its own types.
	 *
	 * @return true for canonical records, source rows and source files
	 */
	public boolean bearsSourceCoordinates() {
		// Identical to isSourceLevel() today, but they answer different questions and
		// are kept apart deliberately: one states an obligation on construction, the
		// other classifies a position in the walk. Merging them would make a future
		// type that is source-level without bearing coordinates impossible to express.
		return this.belongsTo(SourceType.CANONICAL_RECORD, SourceType.SOURCE_ROW, SourceType.SOURCE_FILE);
	}

	/**
	 * Whether this type is part of the tail of the chain - the hops a figure must
	 * reach before it is provable against the original data.
	 *
	 * <p>A classification for the traversal, not a construction rule: a walk stops
	 * when it reaches a source-level node, whereas
	 * {@link #bearsSourceCoordinates()} is what makes such a node well formed.
	 *
	 * @return true for canonical records, source rows and source files
	 */
	public boolean isSourceLevel() {
		return this.belongsTo(SourceType.CANONICAL_RECORD, SourceType.SOURCE_ROW, SourceType.SOURCE_FILE);
	}

	/**
	 * Whether this type sits at the head of the chain, where a monetary claim is made.
	 *
	 * <p>The starting points a trace is asked about. Everything between the claim and
	 * the source is intermediate and contributes no claim of its own, which is why an
	 * intermediate node can never be the answer to "where did this number come from".
	 *
	 * @return true for opportunities, calculation runs and calculation results
	 */
	public boolean isClaimLevel() {
		return this.belongsTo(SourceType.OPPORTUNITY, SourceType.CALCULATION, SourceType.CALCULATION_RESULT);
	}

	/**
	 * Membership by code rather than by identity.
	 *
	 * <p>{@code SourceType.of} hands back the interned constant for a well-known code,
	 * but a hand-built instance from a row read straight out of the database has the
	 * same code and a different identity. Comparing {@code ==} there would silently
	 * report a source row as not being source-level.
	 *
	 * @param candidates types to test against
	 * @return true if this type's code equals any candidate's
	 */
	private boolean belongsTo(SourceType... candidates) {
		for (SourceType candidate : candidates) {
			if (this.code.equals(candidate.code)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof SourceType that && this.code.equals(that.code);
	}

	@Override
	public int hashCode() {
		return this.code.hashCode();
	}

	@Override
	public String toString() {
		return this.code;
	}

}

package com.fintech.cfo.opportunity.enums;

import java.util.List;
import java.util.Set;

import com.fintech.cfo.shared.exception.ValidationException;

/**
 * Lifecycle of an Economic Opportunity Record
 * ({@code opportunities.status VARCHAR(32)}).
 *
 * <p>The controlled arc the product exists to support:
 * {@code detected -> evidenced -> quantified -> validated -> recommended ->
 * approved -> acted -> measured -> attributed -> realized}, with {@code rejected}
 * reachable from every pre-commitment state as the way a human says "this is not a
 * real opportunity".
 *
 * <h2>Why the transition rule lives in the type</h2>
 *
 * <p>{@link #legalSuccessors()} is the single authority on which move is legal. It
 * is written as an exhaustive {@code switch} over the sealed hierarchy, so adding a
 * state is a compile error until somebody states what may follow it. The alternative
 * - a guard clause chain in a service, or a boolean per transition - is the shape
 * that produces records which reached {@code ACTED} without ever being
 * {@code APPROVED}, because nobody remembered to add the new edge.
 *
 * <h2>Backward edges are deliberate, and they are narrow</h2>
 *
 * <p>Three edges move backwards: {@code quantified -> evidenced} (new evidence
 * needed), {@code validated -> quantified} (a challenge forces re-quantification)
 * and {@code recommended -> validated} (the recommendation is withdrawn before any
 * commitment is made). They exist because a validation that cannot be reopened is
 * not a validation, it is a verdict. They are kept few and confined to pre-commit
 * states so that once work has started the record cannot be quietly rewound, and
 * {@link OpportunityLifecycleService} additionally requires a written reason on every
 * backward move.
 */
public sealed interface OpportunityStatus extends CodedEnum permits OpportunityStatus.Detected,
		OpportunityStatus.Evidenced, OpportunityStatus.Quantified, OpportunityStatus.Validated,
		OpportunityStatus.Recommended, OpportunityStatus.Approved, OpportunityStatus.Rejected, OpportunityStatus.Acted,
		OpportunityStatus.Measured, OpportunityStatus.Attributed, OpportunityStatus.Realized {

	/** Width of {@code opportunities.status} in V8. */
	int MAX_CODE_LENGTH = 32;

	/**
	 * Every variant, in the order the lifecycle is walked, which is the order a
	 * progress report should read them in.
	 *
	 * <p>A method rather than a constant: a static field holding nested
	 * {@code INSTANCE} references cannot initialise, because the nested classes are
	 * themselves subclasses of the interface being initialised.
	 */
	static List<OpportunityStatus> all() {
		return List.of(Detected.INSTANCE, Evidenced.INSTANCE, Quantified.INSTANCE, Validated.INSTANCE,
				Recommended.INSTANCE, Approved.INSTANCE, Rejected.INSTANCE, Acted.INSTANCE, Measured.INSTANCE,
				Attributed.INSTANCE, Realized.INSTANCE);
	}

	/**
	 * Resolves a stored {@code opportunities.status} value.
	 *
	 * @throws ValidationException if the value is blank or unknown
	 */
	static OpportunityStatus fromCode(String code) {
		String normalized = CodedEnum.normalise(code, "OpportunityStatus");
		for (OpportunityStatus candidate : all()) {
			if (candidate.code().equals(normalized)) {
				candidate.requireColumnWidth(MAX_CODE_LENGTH);
				return candidate;
			}
		}
		throw new ValidationException("unknown opportunity status code: " + code);
	}

	/**
	 * The states this one may legally move to.
	 *
	 * <p>The graph, and nothing else. Preconditions on individual moves - "a
	 * quantified record needs a calculation reference" - are enforced by
	 * {@code OpportunityLifecycleService} and are reported as business-rule failures,
	 * because they are about the record's contents rather than about the shape of the
	 * lifecycle.
	 *
	 * <h2>Reading the table</h2>
	 *
	 * <p>The forward spine is one state per step, with one deliberate shortcut
	 * ({@code detected -> quantified}) for a detector that produced a figure in a
	 * single pass. {@code rejected} appears on every state before a commitment is
	 * made and on no state after it. The three backward edges are the only ways a
	 * record can be re-opened, and each one answers a specific human act: new
	 * evidence, a challenge, a withdrawn recommendation.
	 *
	 * @return the states reachable in one step; empty for a terminal state
	 */
	default Set<OpportunityStatus> legalSuccessors() {
		return switch (this) {
			// The shortcut as well as the spine: a single-pass detection holds
			// evidence and a figure at the same moment, and forcing two writes to
			// record that would be ceremony, not accuracy.
			case Detected detected -> Set.of(Evidenced.INSTANCE, Quantified.INSTANCE, Rejected.INSTANCE);
			case Evidenced evidenced -> Set.of(Quantified.INSTANCE, Rejected.INSTANCE);
			// Backward edge: newly attached evidence can invalidate the figure.
			case Quantified quantified -> Set.of(Evidenced.INSTANCE, Validated.INSTANCE, Rejected.INSTANCE);
			// Backward edge: a challenge re-opens quantification, not validation.
			case Validated validated -> Set.of(Recommended.INSTANCE, Quantified.INSTANCE, Rejected.INSTANCE);
			// Backward edge: a recommendation is withdrawn before any commitment.
			case Recommended recommended -> Set.of(Approved.INSTANCE, Validated.INSTANCE, Rejected.INSTANCE);
			// No edge to Rejected: money has been committed, so the only honest
			// remaining moves are forward.
			case Approved approved -> Set.of(Acted.INSTANCE);
			// Terminal in both directions: see isTerminal().
			case Rejected rejected -> Set.of();
			case Acted acted -> Set.of(Measured.INSTANCE);
			case Measured measured -> Set.of(Attributed.INSTANCE);
			case Attributed attributed -> Set.of(Realized.INSTANCE);
			case Realized realized -> Set.of();
		};
	}

	/**
	 * Whether the record may move from this state to {@code target} in one step.
	 *
	 * <p>The predicate a transition guard reads. It is deliberately a membership test
	 * on {@link #legalSuccessors()} rather than a second, independently maintained
	 * table, so the guard and the documented graph cannot drift apart.
	 *
	 * @param target the state the caller wants to move to
	 * @return true if the move is in the lifecycle graph
	 */
	default boolean canTransitionTo(OpportunityStatus target) {
		return legalSuccessors().contains(target);
	}

	/**
	 * Whether the record is finished, successfully or otherwise.
	 *
	 * <p>Both terminal states refuse every outgoing edge. A rejected opportunity must
	 * not be quietly reopened, because the rejection is the finding: someone decided
	 * this money is not recoverable, and a later report that shows it as live again
	 * without a new record would misstate that decision.
	 *
	 * @return true for {@code rejected} and {@code realized}, the two states the
	 *         lifecycle never leaves
	 */
	default boolean isTerminal() {
		return switch (this) {
			case Detected detected -> false;
			case Evidenced evidenced -> false;
			case Quantified quantified -> false;
			case Validated validated -> false;
			case Recommended recommended -> false;
			case Approved approved -> false;
			case Rejected rejected -> true;
			case Acted acted -> false;
			case Measured measured -> false;
			case Attributed attributed -> false;
			case Realized realized -> true;
		};
	}

	/**
	 * Whether a monetary claim may legitimately exist in this state.
	 *
	 * <p>False only for {@link Detected} and {@link Evidenced}, before any figure has
	 * been produced. {@code EconomicOpportunity} refuses to hold a non-zero impact
	 * without contributions and a calculation reference in these states, so an
	 * unquantified finding cannot appear in a report carrying an amount.
	 *
	 * <p>{@link Rejected} counts as capable of a claim because it is reachable both
	 * from {@code detected} (nothing quantified) and from {@code validated}
	 * (something was). The invariant only constrains the false cases, so no
	 * inconsistency can arise.
	 *
	 * @return true from {@code quantified} onwards, and for {@code rejected}, which
	 *         may be reached either side of quantification
	 */
	default boolean carriesMonetaryClaim() {
		return switch (this) {
			case Detected detected -> false;
			case Evidenced evidenced -> false;
			case Quantified quantified -> true;
			case Validated validated -> true;
			case Recommended recommended -> true;
			case Approved approved -> true;
			case Rejected rejected -> true;
			case Acted acted -> true;
			case Measured measured -> true;
			case Attributed attributed -> true;
			case Realized realized -> true;
		};
	}

	/** A record exists: it has been detected but has not yet been evidenced. */
	record Detected() implements OpportunityStatus {

		public static final Detected INSTANCE = new Detected();

		@Override
		public String code() {
			return "DETECTED";
		}

	}

	/** Supporting evidence is attached; there is still no agreed figure. */
	record Evidenced() implements OpportunityStatus {

		public static final Evidenced INSTANCE = new Evidenced();

		@Override
		public String code() {
			return "EVIDENCED";
		}

	}

	/** A deterministic calculation has produced the impact and its breakdown. */
	record Quantified() implements OpportunityStatus {

		public static final Quantified INSTANCE = new Quantified();

		@Override
		public String code() {
			return "QUANTIFIED";
		}

	}

	/** A finance reviewer has independently confirmed the figure and its basis. */
	record Validated() implements OpportunityStatus {

		public static final Validated INSTANCE = new Validated();

		@Override
		public String code() {
			return "VALIDATED";
		}

	}

	/** An action has been proposed, with a named next step and an owner. */
	record Recommended() implements OpportunityStatus {

		public static final Recommended INSTANCE = new Recommended();

		@Override
		public String code() {
			return "RECOMMENDED";
		}

	}

	/** The proposed action has been authorised. Nothing has been done yet. */
	record Approved() implements OpportunityStatus {

		public static final Approved INSTANCE = new Approved();

		@Override
		public String code() {
			return "APPROVED";
		}

	}

	/**
	 * A human decided this is not a real opportunity. Terminal.
	 *
	 * <p>A first-class lifecycle state rather than a deleted record, so the rejection
	 * stays countable. "How much did we look at and throw away, and why" is the
	 * question that keeps a detection engine honest, and it can only be answered if
	 * the rejections are still there to be counted.
	 */
	record Rejected() implements OpportunityStatus {

		public static final Rejected INSTANCE = new Rejected();

		@Override
		public String code() {
			return "REJECTED";
		}

	}

	/** The action has been taken. */
	record Acted() implements OpportunityStatus {

		public static final Acted INSTANCE = new Acted();

		@Override
		public String code() {
			return "ACTED";
		}

	}

	/** The effect of the action has been observed in the ledger. */
	record Measured() implements OpportunityStatus {

		public static final Measured INSTANCE = new Measured();

		@Override
		public String code() {
			return "MEASURED";
		}

	}

	/** The measured effect has been attributed back to this opportunity. */
	record Attributed() implements OpportunityStatus {

		public static final Attributed INSTANCE = new Attributed();

		@Override
		public String code() {
			return "ATTRIBUTED";
		}

	}

	/**
	 * The money has actually been recovered. Terminal, and the only state that may be
	 * counted as realised value.
	 */
	record Realized() implements OpportunityStatus {

		public static final Realized INSTANCE = new Realized();

		@Override
		public String code() {
			return "REALIZED";
		}

	}

}
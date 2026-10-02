package com.fintech.cfo.contract.service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

import com.fintech.cfo.contract.model.Contract;
import com.fintech.cfo.contract.model.EffectiveWindow;
import com.fintech.cfo.contract.model.VersionedTerm;
import com.fintech.cfo.shared.exception.BusinessRuleException;

/**
 * Answers the one question a historical calculation depends on: which version of
 * a term was in force on this date?
 *
 * <p>Stateless, so it is safe to share and trivial to test. The as-of date is
 * always supplied by the caller and nothing here reads a clock, which is what
 * makes a calculation from eighteen months ago re-derive identically.
 *
 * <h2>Tie-break rule</h2>
 *
 * <p>Several rows can legitimately cover the same date. The resolver picks a
 * single winner using a <strong>total order derived only from stored column
 * values</strong>, so the answer never depends on the order rows happened to
 * arrive in from the database:
 *
 * <ol>
 * <li><strong>Highest {@code term_version} wins.</strong> This is the business
 * rule: a later amendment supersedes the term it amends.</li>
 * <li><strong>Latest {@code effective_from} wins.</strong> At equal version the
 * more recently started window is the correcting one; this is what a back-dated
 * restatement looks like.</li>
 * <li><strong>Earliest {@code effective_to} wins, open-ended last.</strong> At
 * equal version and start, the narrower window is the more specific statement. A
 * null end is ordered as if it were later than every bounded end, so that this
 * step stays a total order instead of bouncing on null.</li>
 * <li><strong>Lowest row {@code id} wins.</strong> The last resort. Two rows
 * sharing a version, a start and an end are data corruption rather than a
 * modelling case. The resolver still returns a stable, reproducible answer
 * instead of throwing, because a duplicated clause should reach a human as a
 * discrepancy and not as an outage. Callers that want the whole picture use
 * {@link #inForceOn} and see both rows.</li>
 * </ol>
 *
 * <p>{@code version}, the optimistic lock column, plays no part. It is a row write
 * counter, so ordering on it would let an unrelated concurrent UPDATE change which
 * terms a historical calculation used.
 */
public final class EffectiveTermResolver {

	/**
	 * Field-derived precedence order, with null end dates sorted last.
	 *
	 * <p>Each step is one clause of the tie-break documented on this class. The
	 * order is part of the module's contract with stored calculations, so it is
	 * expressed once here and reused by {@link TermSelector} and by the input
	 * checksum rather than re-derived at each call site.
	 */
	// 1. Highest term_version: a later amendment supersedes the term it amends.
	//    `version` is deliberately absent - it is a row write counter, and ordering
	//    on it would let an unrelated UPDATE change a historical calculation.
	private static final Comparator<VersionedTerm> PRECEDENCE = Comparator
			.comparingInt(VersionedTerm::termVersion).reversed()
			// 2. Latest effective_from: at equal version the more recently started
			//    window is the correcting one, which is what a back-dated restatement
			//    looks like.
			.thenComparing(term -> term.effectiveWindow().effectiveFrom(), Comparator.reverseOrder())
			// 3. Earliest effective_to, with an open-ended window ordered as if it
			//    were later than every bounded end. Both the reversal and the
			//    nullsLast matter: without them a single null would make this
			//    comparator non-total and two runs could order the same rows
			//    differently.
			.thenComparing(term -> term.effectiveWindow().effectiveTo(),
					Comparator.nullsLast(Comparator.reverseOrder()))
			// 4. Lowest id. A total order is required for the checksum to be stable;
			//    this step is a corruption tie-break, not a modelling rule.
			.thenComparing(VersionedTerm::id);

	/**
	 * The precedence order, exposed so that callers merging candidates from several
	 * sources apply exactly the same rule.
	 */
	@SuppressWarnings("unchecked")
	public static <T extends VersionedTerm> Comparator<T> precedence() {
		return (Comparator<T>) PRECEDENCE;
	}

	/**
	 * Every term whose window covers {@code asOfDate}, in precedence order.
	 *
	 * <p>Returned sorted rather than just filtered, so a caller can see the losing
	 * candidates of a tie-break and not only the winner.
	 */
	public <T extends VersionedTerm> List<T> inForceOn(List<T> terms, LocalDate asOfDate) {
		return inForceOn(terms, asOfDate, term -> true);
	}

	/**
	 * Every term that covers {@code asOfDate} <em>and</em> satisfies
	 * {@code candidate}, in precedence order. Null rows are skipped and the
	 * predicate is only ever invoked with a non-null term.
	 */
	public <T extends VersionedTerm> List<T> inForceOn(List<T> terms, LocalDate asOfDate, Predicate<T> candidate) {
		Objects.requireNonNull(terms, "terms must not be null");
		Objects.requireNonNull(asOfDate, "asOfDate must not be null");
		Objects.requireNonNull(candidate, "candidate must not be null");
		return terms.stream()
				// Nulls are skipped before the predicate runs, so a caller-supplied
				// predicate is never handed a null and cannot NPE on a sparse list.
				.filter(Objects::nonNull)
				// Inclusive on both ends; the single definition of "in force" lives in
				// EffectiveWindow so the rule cannot differ between entry points.
				.filter(term -> term.effectiveWindow().contains(asOfDate))
				.filter(candidate)
				// Sorted, not just filtered: the losing candidates of a tie-break are
				// evidence, and the input checksum hashes this exact ordering.
				.sorted(PRECEDENCE)
				.toList();
	}

	/**
	 * The single term in force, or empty when the set does not cover the date.
	 * An empty result is a legitimate answer and is never replaced by a default.
	 */
	public <T extends VersionedTerm> Optional<T> findInForce(List<T> terms, LocalDate asOfDate) {
		return findInForce(terms, asOfDate, term -> true);
	}

	public <T extends VersionedTerm> Optional<T> findInForce(List<T> terms, LocalDate asOfDate,
			Predicate<T> candidate) {
		return inForceOn(terms, asOfDate, candidate).stream().findFirst();
	}

	/**
	 * The single term in force, or a hard failure.
	 *
	 * <p>Used wherever continuing would mean inventing a commercial term. A missing
	 * price is an audit finding, not something to paper over with zero or a
	 * remembered default.
	 *
	 * @param subject identification of what was being looked up, used only in the
	 * failure message
	 * @throws BusinessRuleException when no term covers the date
	 */
	public <T extends VersionedTerm> T requireInForce(List<T> terms, LocalDate asOfDate, String subject) {
		return findInForce(terms, asOfDate).orElseThrow(
				() -> new BusinessRuleException("no " + subject + " is in force on " + asOfDate));
	}

	/**
	 * Whether a contract may supply terms on a date.
	 *
	 * <p>Two independent conditions, both required: the effective window covers the
	 * date, and the status permits terms to be used. Pricing a suspended or draft
	 * contract is wrong even when the date sits inside its window.
	 *
	 * @throws BusinessRuleException naming the specific reason, so an operator can
	 * see whether the contract was out of date or out of standing
	 */
	public Contract requireContractInForce(Contract contract, LocalDate asOfDate) {
		Objects.requireNonNull(contract, "contract must not be null");
		Objects.requireNonNull(asOfDate, "asOfDate must not be null");
		if (!contract.effectiveWindow().contains(asOfDate)) {
			// The window is echoed in canonical form so an operator can see the dates
			// that excluded the request without re-reading the row.
			throw new BusinessRuleException("contract " + contract.contractNumber() + " is not effective on " + asOfDate
					+ " (window " + contract.effectiveWindow().canonical() + ")");
		}
		// Separate from the window check, and separately reported: "out of date" and
		// "out of standing" are different problems with different fixes, and a
		// suspended contract inside its own window is the case most likely to be
		// mistaken for a valid one.
		if (!contract.status().suppliesTerms()) {
			throw new BusinessRuleException("contract " + contract.contractNumber() + " is in status "
					+ contract.status().code() + ", which does not supply commercial terms");
		}
		return contract;
	}

	/**
	 * Whether a window covers a date, tolerating the open-ended case. For callers
	 * holding a raw window rather than a term.
	 */
	public boolean isInForce(EffectiveWindow window, LocalDate asOfDate) {
		Objects.requireNonNull(window, "window must not be null");
		Objects.requireNonNull(asOfDate, "asOfDate must not be null");
		return window.contains(asOfDate);
	}

}

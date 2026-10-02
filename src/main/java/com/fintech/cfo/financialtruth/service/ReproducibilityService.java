package com.fintech.cfo.financialtruth.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fintech.cfo.financialtruth.calculator.FinancialTruthEngine;
import com.fintech.cfo.financialtruth.model.CalculationInput;
import com.fintech.cfo.financialtruth.model.CalculationResult;
import com.fintech.cfo.financialtruth.model.CalculationRun;
import com.fintech.cfo.shared.exception.BusinessRuleException;
import com.fintech.cfo.shared.exception.ValidationException;

/**
 * Proves that a calculation can be reproduced.
 *
 * <p>This is the service that turns "we believe it is reproducible" into "we have
 * checked". It re-runs a stored snapshot through the engine and compares three things
 * independently:
 *
 * <ol>
 * <li>the {@code input_checksum} - proves the same inputs were read;</li>
 * <li>the {@code rule_version} - proves the same rule set was used;</li>
 * <li>the {@link CalculationRun#deterministicFingerprint()} - proves every amount,
 * term version and timestamp came out identical.</li>
 * </ol>
 *
 * <p>All three must match. Matching only the checksum would prove nothing about the
 * arithmetic; matching only the fingerprint would hide an input change that happened
 * to cancel itself out.
 *
 * <p>The replay reuses the original run id and the engine's own injected clock, so
 * the two runs are comparable down to the evaluated-at instant. Note that a
 * {@code Clock} is not a constructor argument here: the clock that matters belongs to
 * the {@link FinancialTruthEngine} doing the replay, and passing a second one in
 * would invite a caller to prove reproducibility under conditions the original run
 * never had.
 */
public final class ReproducibilityService {

	private final FinancialTruthEngine engine;
	private final CalculationRunService runService;

	public ReproducibilityService(FinancialTruthEngine engine, CalculationRunService runService) {
		this.engine = engine;
		this.runService = runService;
	}

	/**
	 * Re-runs the supplied snapshot and compares the outcome with the original run.
	 *
	 * @return the verdict, never an exception, so a caller can report a mismatch as
	 *         data rather than as a crash
	 */
	public Verdict verify(CalculationRun original, CalculationInput reloadedInput) {
		if (original == null) {
			throw new ValidationException("original run must not be null");
		}
		if (reloadedInput == null) {
			throw new ValidationException("reloadedInput must not be null");
		}
		// Replayed under the ORIGINAL run id. The replay is a re-execution of that run,
		// not a new run, so a fresh id would prevent the two from being compared.
		CalculationRun replay = this.engine.calculate(reloadedInput, original.runId());

		// Every difference is collected rather than short-circuiting on the first. A
		// single mismatch report is far less useful to whoever has to diagnose it than the
		// full set, and the run is already finished by this point.
		List<String> differences = new ArrayList<>();
		if (!original.inputChecksum().equals(reloadedInput.checksum())) {
			// Checked first because it is the cheapest and most explanatory: if the inputs
			// read from storage differ, every downstream difference is a consequence.
			differences.add("reloaded input checksum " + reloadedInput.checksum() + " differs from the original "
					+ original.inputChecksum());
		}
		if (!original.inputChecksum().equals(replay.inputChecksum())) {
			differences.add("replayed input checksum " + replay.inputChecksum());
		}
		if (!original.ruleVersion().equals(replay.ruleVersion())) {
			differences.add("rule version " + original.ruleVersion() + " vs " + replay.ruleVersion());
		}
		if (original.results().size() != replay.results().size()) {
			// Count checked separately, since positional comparison below would otherwise
			// silently skip the rows only one side has.
			differences.add("result count " + original.results().size() + " vs " + replay.results().size());
		}
		differences.addAll(compareResults(original.results(), replay.results()));
		if (!original.deterministicFingerprint().equals(replay.deterministicFingerprint())) {
			differences.add("run fingerprint " + original.deterministicFingerprint() + " vs "
					+ replay.deterministicFingerprint());
		}
		return new Verdict(original.runId(), original.inputChecksum(), replay.inputChecksum(),
				original.deterministicFingerprint(), replay.deterministicFingerprint(), List.copyOf(differences),
				replay);
	}

	/**
	 * Same as {@link #verify} but raises when the run did not reproduce.
	 *
	 * @throws BusinessRuleException naming the differences found
	 */
	public void requireReproducible(CalculationRun original, CalculationInput reloadedInput) {
		Verdict verdict = verify(original, reloadedInput);
		if (!verdict.reproduced()) {
			// Every difference named in one message. A failure that reported only the
			// first mismatch would send a reader looking for a second run of the diagnosis.
			throw new BusinessRuleException("calculation run " + original.runId() + " did not reproduce: "
					+ String.join("; ", verdict.differences()));
		}
		// Second, lifecycle-level check. verify compares the arithmetic; this compares the
		// run record a caller is about to close against the replay, which is what stops an
		// unreproducible run being persisted as authoritative.
		this.runService.requireReproducible(original, verdict.replayedRun());
	}

	private static List<String> compareResults(List<CalculationResult> original, List<CalculationResult> replay) {
		List<String> differences = new ArrayList<>();
		// Positional, over the shorter length. The size mismatch is reported separately,
		// so nothing beyond the shorter list is silently dropped here.
		int shared = Math.min(original.size(), replay.size());
		for (int index = 0; index < shared; index++) {
			// Compared on the canonical form rather than on object equality: it includes
			// the rule version, the evaluated terms and the instant, so any drift in the
			// lineage shows up rather than only in the amounts.
			String left = original.get(index).canonicalForm();
			String right = replay.get(index).canonicalForm();
			if (!left.equals(right)) {
				differences.add("result[" + index + "] " + left + " vs " + right);
			}
		}
		return differences;
	}

	/**
	 * Outcome of a reproduction attempt.
	 *
	 * @param reproduced true only when the checksum, rule version, every result row and
	 *                   the run fingerprint all matched
	 * @param differences empty when reproduced; otherwise one entry per difference
	 * @param replayedRun the run produced by the replay, so a caller can inspect what
	 *                    actually differed rather than only being told that something did
	 */
	public record Verdict(
			UUID runId,
			String originalChecksum,
			String replayedChecksum,
			String originalFingerprint,
			String replayedFingerprint,
			List<String> differences,
			CalculationRun replayedRun) {

		public boolean reproduced() {
			return this.differences.isEmpty();
		}
	}

}
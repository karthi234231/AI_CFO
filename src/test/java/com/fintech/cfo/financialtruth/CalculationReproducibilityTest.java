package com.fintech.cfo.financialtruth;

import static com.fintech.cfo.financialtruth.TruthEngineFixtures.AS_OF;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.FIXED_CLOCK;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.INR;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.ORGANIZATION;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.RUN_ID;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.SKU_DISCOUNTED;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.SKU_DOLLAR;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.SKU_FRACTIONAL;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.SKU_HUGE;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.SKU_MATCHED;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.SKU_OVERCHARGED;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.SKU_SUB_PAISA;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.SKU_UNDERCHARGED;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.TERM_END;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.TERM_START;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.TRIGGERED_BY;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.USD;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.actualCalculator;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.engine;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.expectedCalculator;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.impactAggregator;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.line;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.lineWithDiscount;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.pricing;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.pricingOnlyEngine;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.source;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.standardInput;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.tenPercentDiscount;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.varianceCalculator;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.fintech.cfo.financialtruth.calculator.ActualAmountCalculator;
import com.fintech.cfo.financialtruth.calculator.ExpectedAmountCalculator;
import com.fintech.cfo.financialtruth.calculator.FinancialTruthEngine;
import com.fintech.cfo.financialtruth.calculator.VarianceCalculator;
import com.fintech.cfo.financialtruth.enums.CalculationStatus;
import com.fintech.cfo.financialtruth.enums.CalculationType;
import com.fintech.cfo.financialtruth.model.CalculationInput;
import com.fintech.cfo.financialtruth.model.CalculationRun;
import com.fintech.cfo.financialtruth.model.DiscountTerm;
import com.fintech.cfo.financialtruth.model.InvoiceLineInput;
import com.fintech.cfo.financialtruth.model.PricingTerm;
import com.fintech.cfo.financialtruth.rules.DiscountVarianceRule;
import com.fintech.cfo.financialtruth.rules.PricingVarianceRule;
import com.fintech.cfo.financialtruth.service.CalculationRunService;
import com.fintech.cfo.financialtruth.service.ReproducibilityService;
import com.fintech.cfo.financialtruth.service.ReproducibilityService.Verdict;
import com.fintech.cfo.shared.domain.CurrencyCode;
import com.fintech.cfo.shared.domain.Money;
import com.fintech.cfo.shared.exception.BusinessRuleException;
import com.fintech.cfo.shared.exception.ValidationException;

/**
 * Proves the claim in the module's name: the same snapshot produces the same answer,
 * and any difference is reported rather than hidden.
 *
 * <p>Each test builds its inputs twice from literals. Building the second copy
 * differently - reversed term order, extra trailing zeros, a different scale - is the
 * point: a checksum that only survives identical object construction would prove
 * nothing about a re-read from the database.
 *
 * <p><b>Why this must never regress.</b> A finance team that disputes a variance
 * months later needs one question to have a defensible answer: "were these the
 * same inputs, and the same rules?" Today the platform answers it with two
 * mechanisms that must both hold.
 *
 * <ul>
 * <li>The <b>input checksum</b> digests the canonical form of the snapshot. It must
 * ignore things that carry no economic meaning - the order rows were loaded in,
 * and the decimal scale a {@code NUMERIC(20,4)} round-trip leaves behind - because
 * a checksum that reports spurious drift will train auditors to ignore it.</li>
 * <li>The <b>deterministic fingerprint</b> digests the produced results. It must
 * move whenever anything that could change a figure moves: the evaluation instant
 * from the injected clock, and the rule set version.</li>
 * </ul>
 *
 * <p>So the two must be independent. When they agree, the answer was re-derived.
 * When the checksum differs, the inputs changed and there is nothing to compare.
 * When the checksum matches but the fingerprint does not, the inputs were the same
 * but the conditions were not - and the run cannot be called reproducible. The
 * suite asserts each of those three outcomes separately, because collapsing them
 * would make a genuine tampering indistinguishable from a clock change.
 *
 * <p>Diff reporting is part of the contract, not a convenience: a verifier that
 * only says "it changed" leaves an auditor with nothing to act on, so the tests
 * pin that a drifted line is named and that every difference is listed rather than
 * only the first.
 */
class CalculationReproducibilityTest {

	private static ReproducibilityService verifier() {
		return verifier(engine(), engine());
	}

	private static ReproducibilityService verifier(FinancialTruthEngine originalEngine,
			FinancialTruthEngine replayEngine) {
		CalculationRunService runService = new CalculationRunService(originalEngine, FIXED_CLOCK);
		return new ReproducibilityService(replayEngine, runService);
	}

	/** The snapshot under test: a three-line invoice with a discount entitlement. */
	private static CalculationInput snapshot() {
		return standardInput(List.of(
				line(1, SKU_OVERCHARGED, "10", "1000.00", INR),
				line(2, SKU_MATCHED, "4", "1000.00", INR),
				lineWithDiscount(3, SKU_DISCOUNTED, "10", "1000.00", "50.00", INR)));
	}

	@Nested
	@DisplayName("running the same snapshot again")
	class RepeatRuns {

		@Test
		void producesTheIdenticalFingerprint() {
			// Same snapshot, same engine, twice: the fingerprint, the checksum and the
			// completion instant must all agree, including the timestamp, because the
			// clock is injected and fixed rather than read from the system.
			CalculationInput input = snapshot();

			CalculationRun first = engine().calculate(input, RUN_ID);
			CalculationRun second = engine().calculate(input, RUN_ID);

			assertThat(second.deterministicFingerprint()).isEqualTo(first.deterministicFingerprint());
			assertThat(second.inputChecksum()).isEqualTo(first.inputChecksum());
			assertThat(second.results()).hasSameSizeAs(first.results());
			assertThat(second.completedAt()).isEqualTo(first.completedAt());
		}

		@Test
		void producesTheIdenticalFingerprintFromAFreshlyBuiltEngine() {
			CalculationInput input = snapshot();

			// Nothing is cached and nothing is static: two independent engines agree.
			assertThat(freshEngine().calculate(input, RUN_ID).deterministicFingerprint())
				.isEqualTo(freshEngine().calculate(input, RUN_ID).deterministicFingerprint());
		}

		@Test
		void readsItsTimestampFromTheInjectedClockAndNotTheSystemClock() {
			// Determinism starts here. A single call to Instant.now() anywhere in the
			// calculation path would make every fingerprint unreproducible while still
			// looking correct in a live run.
			Clock later = Clock.fixed(Instant.parse("2024-06-01T12:00:00Z"), ZoneOffset.UTC);

			CalculationRun run = engine().calculate(snapshot(), RUN_ID);

			assertThat(run.startedAt()).isEqualTo(Instant.parse("2024-03-16T09:30:00Z"));
			assertThat(run.startedAt()).isNotEqualTo(later.instant());
			assertThat(run.results()).allSatisfy(result -> assertThat(result.calculatedAt()).isEqualTo(run.startedAt()));
		}
	}

	private static FinancialTruthEngine freshEngine() {
		ExpectedAmountCalculator expected = expectedCalculator();
		ActualAmountCalculator actual = actualCalculator();
		VarianceCalculator variance = varianceCalculator();
		return new FinancialTruthEngine(expected, actual, variance, impactAggregator(),
				List.of(new PricingVarianceRule(expected, actual, variance),
						new DiscountVarianceRule(expected, actual, variance)),
				FIXED_CLOCK);
	}

	@Nested
	@DisplayName("re-reading a snapshot from storage")
	class ReloadedSnapshots {

		@Test
		void reproducesWhenTheSnapshotIsRebuiltFieldByField() {
			CalculationInput original = snapshot();
			CalculationRun run = engine().calculate(original, RUN_ID);

			Verdict verdict = verifier().verify(run, rebuiltSnapshot());

			assertThat(verdict.reproduced()).isTrue();
			assertThat(verdict.differences()).isEmpty();
			assertThat(verdict.replayedFingerprint()).isEqualTo(verdict.originalFingerprint());
			assertThat(verdict.replayedChecksum()).isEqualTo(verdict.originalChecksum());
		}

		@Test
		void reproducesWhenTheValuesComeBackAtADifferentDecimalScale() {
			CalculationInput original = snapshot();
			CalculationRun run = engine().calculate(original, RUN_ID);

			Verdict verdict = verifier().verify(run, rescaledSnapshot());

			assertThat(verdict.reproduced()).isTrue();
			assertThat(verdict.replayedChecksum()).isEqualTo(original.checksum());
		}

		@Test
		void reproducesWhenTheTermsWereLoadedInTheOppositeOrder() {
			CalculationInput original = snapshot();
			CalculationRun run = engine().calculate(original, RUN_ID);
			List<PricingTerm> reversedPricing = new ArrayList<>(original.pricingTerms());
			Collections.reverse(reversedPricing);
			List<DiscountTerm> reversedDiscounts = new ArrayList<>(original.discountTerms());
			Collections.reverse(reversedDiscounts);

			CalculationInput reordered = new CalculationInput(ORGANIZATION, original.calculationType(),
					original.invoiceNumber(), original.invoiceDate(), original.asOfDate(), original.lines(),
					reversedPricing, reversedDiscounts, TRIGGERED_BY);

			assertThat(reordered.checksum()).isEqualTo(original.checksum());
			assertThat(verifier().verify(run, reordered).reproduced()).isTrue();
		}

		@Test
		void reportsAChangedAmountRatherThanClaimingItReproduced() {
			// The failure mode this suite exists to prevent is a verifier that returns
			// "reproduced" because the run completed without error. Completion is not
			// reproduction.
			CalculationInput original = snapshot();
			CalculationRun run = engine().calculate(original, RUN_ID);
			CalculationInput tampered = rebuiltSnapshot("1050.00");

			Verdict verdict = verifier().verify(run, tampered);

			assertThat(verdict.reproduced()).isFalse();
			assertThat(verdict.differences()).isNotEmpty();
			assertThat(verdict.replayedChecksum()).isNotEqualTo(original.checksum());
			assertThat(String.join(" ", verdict.differences())).contains("checksum");
		}

		@Test
		void detectsAContractPriceAmendedInPlaceUnderTheSameTermId() {
			CalculationInput original = snapshot();
			CalculationRun run = engine().calculate(original, RUN_ID);
			CalculationInput amended = amendedContractPrice("1100.00");

			// Same term id, same version, same window - only the contracted price moved.
			// Hashing term identity alone would have called these the same input.
			assertThat(amended.checksum()).isNotEqualTo(original.checksum());
			Verdict verdict = verifier().verify(run, amended);
			assertThat(verdict.reproduced()).isFalse();
			assertThat(String.join(" ", verdict.differences())).contains("checksum");
		}

		@Test
		void namesTheLineThatDriftedSoTheDifferenceCanBeInvestigated() {
			CalculationInput original = snapshot();
			CalculationRun run = engine().calculate(original, RUN_ID);
			List<InvoiceLineInput> tamperedLines = new ArrayList<>(original.lines());
			tamperedLines.set(0, line(1, SKU_OVERCHARGED, "10", "1050.00", INR));
			CalculationInput tampered = new CalculationInput(ORGANIZATION, original.calculationType(),
					original.invoiceNumber(), original.invoiceDate(), original.asOfDate(), tamperedLines,
					original.pricingTerms(), original.discountTerms(), TRIGGERED_BY);

			Verdict verdict = verifier().verify(run, tampered);

			// The replay still produced a finding for every line; only line 1 moved, from
			// 10 x (1000 - 920) to 10 x (1050 - 920). A verifier that only said "it
			// changed" would leave an auditor with nothing to act on.
			assertThat(verdict.replayedRun().combinedResults()).hasSize(3);
			assertThat(verdict.replayedRun().combinedResults()).filteredOn(result -> result.lineNumber() == 1)
				.singleElement().satisfies(result -> {
					assertThat(result.varianceAmount().amount()).isEqualByComparingTo("1300.0000");
					assertThat(result.varianceAmount().currency()).isEqualTo(INR);
				});
			assertThat(run.combinedResults()).filteredOn(result -> result.lineNumber() == 1).singleElement()
				.satisfies(result -> assertThat(result.varianceAmount().amount()).isEqualByComparingTo("800.0000"));
			assertThat(verdict.reproduced()).isFalse();
			assertThat(String.join(" ", verdict.differences())).contains("line=1");
		}
	}

	@Nested
	@DisplayName("conditions the replay must reproduce too")
	class Conditions {

		@Test
		void refusesToReproduceWhenTheClockMoved() {
			CalculationInput input = snapshot();
			CalculationRun run = engine().calculate(input, RUN_ID);
			FinancialTruthEngine laterEngine = freshEngineWith(Clock.fixed(Instant.parse("2024-06-01T12:00:00Z"),
					ZoneOffset.UTC));

			Verdict verdict = verifier(engine(), laterEngine).verify(run, input);

			// The input checksum still matches - only the evaluation instant moved.
			assertThat(verdict.replayedChecksum()).isEqualTo(run.inputChecksum());
			assertThat(verdict.replayedFingerprint()).isNotEqualTo(verdict.originalFingerprint());
			assertThat(verdict.reproduced()).isFalse();
			assertThat(String.join(" ", verdict.differences())).contains("fingerprint");
		}

		@Test
		void refusesToReproduceUnderADifferentRuleSet() {
			CalculationInput input = snapshot();
			CalculationRun run = engine().calculate(input, RUN_ID);
			FinancialTruthEngine fewerRules = pricingOnlyEngine();

			Verdict verdict = verifier(engine(), fewerRules).verify(run, input);

			assertThat(verdict.replayedChecksum()).isEqualTo(run.inputChecksum());
			assertThat(verdict.replayedFingerprint()).isNotEqualTo(verdict.originalFingerprint());
			assertThat(String.join(" ", verdict.differences())).contains("rule version");
			assertThat(verdict.replayedRun().results()).hasSizeLessThan(run.results().size());
		}

		@Test
		void reportsEveryDifferenceItFoundRatherThanTheFirst() {
			CalculationInput original = snapshot();
			CalculationRun run = engine().calculate(original, RUN_ID);
			FinancialTruthEngine laterEngine = freshEngineWith(
					Clock.fixed(Instant.parse("2024-06-01T12:00:00Z"), ZoneOffset.UTC));
			CalculationInput tampered = rebuiltSnapshot("1050.00");

			Verdict verdict = verifier(engine(), laterEngine).verify(run, tampered);

			assertThat(verdict.differences()).hasSizeGreaterThan(1);
		}

		@Test
		void raisesRatherThanReturnsWhenAFaithfulReplayIsRequired() {
			assertThatCode(() -> verifier().requireReproducible(engine().calculate(snapshot(), RUN_ID), snapshot()))
				.doesNotThrowAnyException();
		}

		@Test
		void namesTheDifferencesWhenAnUnfaithfulReplayIsRequired() {
			CalculationRun run = engine().calculate(snapshot(), RUN_ID);

			assertThatThrownBy(() -> verifier().requireReproducible(run, rebuiltSnapshot("1050.00")))
				.isInstanceOf(BusinessRuleException.class)
				.hasMessageContaining("did not reproduce");
		}
	}

	@Nested
	@DisplayName("run lifecycle")
	class Lifecycle {

		private final CalculationRunService runService = new CalculationRunService(engine(), FIXED_CLOCK);

		@Test
		void recordsTheChecksumAndRuleSetBeforeAnyFigureExists() {
			// A run is identified by what it was asked to compute, not by what it
			// produced. Capturing the checksum and the rule set up front is what lets a
			// failed run still be replayed and diagnosed.
			CalculationInput input = snapshot();

			CalculationRun opened = runService.start(input, RUN_ID);

			assertThat(opened.status()).isEqualTo(CalculationStatus.Running.INSTANCE);
			assertThat(opened.inputChecksum()).isEqualTo(input.checksum());
			assertThat(opened.ruleVersion()).isEqualTo("DISCOUNT_VARIANCE@1.0.0+PRICING_VARIANCE@1.0.0");
			assertThat(opened.results()).isEmpty();
			assertThat(opened.impacts()).isEmpty();
			assertThat(opened.completedAt()).isNull();
			assertThat(opened.periodStart()).isEqualTo(AS_OF);
		}

		@Test
		void refusesToOpenARunWithoutAnIdToReplayItBy() {
			CalculationInput input = snapshot();

			assertThatThrownBy(() -> runService.start(input, null)).isInstanceOf(ValidationException.class)
				.hasMessageContaining("runId");
		}

		@Test
		void closesAnOpenRunWithTheResultsProducedFromItsOwnSnapshot() {
			CalculationInput input = snapshot();
			CalculationRun opened = runService.start(input, RUN_ID);

			CalculationRun closed = runService.complete(opened, engine().calculate(input, RUN_ID));

			assertThat(closed.status()).isEqualTo(CalculationStatus.Completed.INSTANCE);
			assertThat(closed.inputChecksum()).isEqualTo(opened.inputChecksum());
			assertThat(closed.completedAt()).isNotNull();
		}

		@Test
		void refusesToCloseARunWithResultsFromADifferentSnapshot() {
			// Closing a run with another run's results would attach figures to an input
			// checksum that never produced them, which is precisely the defect the whole
			// reproducibility mechanism exists to make impossible.
			CalculationRun opened = runService.start(snapshot(), RUN_ID);
			CalculationInput other = rebuiltSnapshot("1050.00");

			assertThatThrownBy(() -> runService.complete(opened, engine().calculate(other, RUN_ID)))
				.isInstanceOf(ValidationException.class)
				.hasMessageContaining("input checksum");
		}

		@Test
		void refusesToCloseARunThatAlreadyEnded() {
			CalculationInput input = snapshot();
			CalculationRun closed = runService.complete(runService.start(input, RUN_ID),
					engine().calculate(input, RUN_ID));

			assertThatThrownBy(() -> runService.complete(closed, closed)).isInstanceOf(ValidationException.class)
				.hasMessageContaining("expected run status RUNNING");
		}

		@Test
		void refusesToCloseARunWithAFailedEvaluation() {
			CalculationInput input = snapshot();
			CalculationRun opened = runService.start(input, RUN_ID);
			CalculationRun failed = runService.fail(opened, new BusinessRuleException("no contract pricing term"));

			assertThatThrownBy(() -> runService.complete(opened, failed)).isInstanceOf(ValidationException.class)
				.hasMessageContaining("only a completed evaluation");
		}

		@Test
		void recordsWhyAFailedRunFailedAndKeepsWhatItWouldHaveNeededToReplay() {
			CalculationInput input = snapshot();
			CalculationRun opened = runService.start(input, RUN_ID);

			CalculationRun failed = runService.fail(opened, new BusinessRuleException("no contract pricing term"));

			assertThat(failed.status()).isEqualTo(CalculationStatus.Failed.INSTANCE);
			assertThat(failed.failureReason()).isEqualTo("no contract pricing term");
			assertThat(failed.inputChecksum()).isEqualTo(opened.inputChecksum());
			assertThat(failed.ruleVersion()).isEqualTo(opened.ruleVersion());
			assertThat(failed.startedAt()).isEqualTo(opened.startedAt());
			assertThat(failed.results()).isEmpty();
		}

		@Test
		void refusesToFailARunWithoutAReason() {
			CalculationRun opened = runService.start(snapshot(), RUN_ID);

			assertThatThrownBy(() -> runService.fail(opened, null)).isInstanceOf(ValidationException.class)
				.hasMessageContaining("must record why");
		}

		@Test
		void truncatesAFailureReasonToWhatTheColumnCanHold() {
			// An exception message can be arbitrarily long. Truncating to fit the column
			// keeps the diagnostic; letting it through would fail the INSERT and lose the
			// failure record entirely.
			CalculationRun opened = runService.start(snapshot(), RUN_ID);
			String tooLong = "x".repeat(3000);

			CalculationRun failed = runService.fail(opened, new BusinessRuleException(tooLong));

			assertThat(failed.failureReason()).hasSize(2000);
		}

		@Test
		void recordsAPlaceholderWhenAFailureCarriedNoMessage() {
			CalculationRun opened = runService.start(snapshot(), RUN_ID);

			CalculationRun failed = runService.fail(opened, new IllegalStateException());

			assertThat(failed.failureReason()).isEqualTo("unspecified failure");
		}

		@Test
		void rejectsARunServiceWithoutAClock() {
			assertThatThrownBy(() -> new CalculationRunService(engine(), null)).isInstanceOf(ValidationException.class)
				.hasMessageContaining("clock must not be null");
		}

		@Test
		void refusesToAcceptAReplayWhoseRuleSetDiffers() {
			CalculationInput input = snapshot();
			CalculationRun original = engine().calculate(input, RUN_ID);
			CalculationRun replay = pricingOnlyEngine().calculate(input, RUN_ID);

			assertThatThrownBy(() -> runService.requireReproducible(original, replay))
				.isInstanceOf(BusinessRuleException.class)
				.hasMessageContaining("rule version changed");
		}

		@Test
		void refusesToAcceptAReplayWhoseInputsDiffer() {
			CalculationRun original = engine().calculate(snapshot(), RUN_ID);
			CalculationRun replay = engine().calculate(rebuiltSnapshot("1050.00"), RUN_ID);

			assertThatThrownBy(() -> runService.requireReproducible(original, replay))
				.isInstanceOf(BusinessRuleException.class)
				.hasMessageContaining("input checksum changed");
		}

		@Test
		void acceptsAFaithfulReplay() {
			CalculationInput input = snapshot();
			CalculationRun original = engine().calculate(input, RUN_ID);

			assertThatCode(() -> runService.requireReproducible(original, engine().calculate(input, RUN_ID)))
				.doesNotThrowAnyException();
		}
	}

	@Nested
	@DisplayName("verifier arguments")
	class VerifierArguments {

		@Test
		void refusesAVerificationWithNothingToCompare() {
			ReproducibilityService service = verifier();

			assertThatThrownBy(() -> service.verify(null, snapshot())).isInstanceOf(ValidationException.class);
			assertThatThrownBy(() -> service.verify(engine().calculate(snapshot(), RUN_ID), null))
				.isInstanceOf(ValidationException.class);
		}

		@Test
		void reportsTheRunItWasAskedAbout() {
			CalculationRun run = engine().calculate(snapshot(), RUN_ID);

			Verdict verdict = verifier().verify(run, snapshot());

			assertThat(verdict.runId()).isEqualTo(RUN_ID);
			assertThat(verdict.replayedRun().runId()).isEqualTo(RUN_ID);
		}
	}

	@Test
	@DisplayName("the run id is the caller's, never generated")
	void doesNotGenerateItsOwnRunId() {
		UUID other = UUID.fromString("9f9f9f9f-4444-4444-8444-444444444444");
		CalculationRun run = engine().calculate(snapshot(), other);

		assertThat(run.runId()).isEqualTo(other);
	}

	// ------------------------------------------------------------------- fixtures

	/**
	 * The three invoice lines of {@link #snapshot()}, rebuilt from literals rather than
	 * copied.
	 *
	 * <p>A rebuild that omitted a line would not be a rebuild at all - the checksum
	 * would move for a reason that has nothing to do with how the values were
	 * constructed, and every assertion below it would be testing the wrong thing.
	 */
	private static List<InvoiceLineInput> rebuiltLines(String overchargedUnitPrice) {
		return List.of(
				new InvoiceLineInput(1, SKU_OVERCHARGED, new BigDecimal("10"), Money.of(overchargedUnitPrice, INR),
						Money.zero(INR), Money.zero(INR), source(1)),
				new InvoiceLineInput(2, SKU_MATCHED, new BigDecimal("4"), Money.of("1000.00", INR), Money.zero(INR),
						Money.zero(INR), source(2)),
				new InvoiceLineInput(3, SKU_DISCOUNTED, new BigDecimal("10"), Money.of("1000.00", INR),
						Money.of("50.00", INR), Money.zero(INR), source(3)));
	}

	/** The same snapshot, rebuilt from literals rather than copied. */
	private static CalculationInput rebuiltSnapshot() {
		return rebuiltSnapshot("1000.00");
	}

	private static CalculationInput rebuiltSnapshot(String overchargedUnitPrice) {
		// The contract is rebuilt in full, including every term the original snapshot
		// carries even where no line uses it. Terms that happen not to be exercised are
		// still part of the input: the checksum digests the whole snapshot, so dropping
		// one is a real input change and must read as one.
		return new CalculationInput(ORGANIZATION, CalculationType.COMBINED_VARIANCE,
				"INV-2024-0001", AS_OF, AS_OF, rebuiltLines(overchargedUnitPrice),
				List.of(pricing(SKU_OVERCHARGED, "920.00", INR), pricing(SKU_MATCHED, "1000.00", INR),
						pricing(SKU_UNDERCHARGED, "1000.00", INR), pricing(SKU_DISCOUNTED, "1000.00", INR),
						pricing(SKU_FRACTIONAL, "33.333333", INR), pricing(SKU_SUB_PAISA, "0.000050", INR),
						pricing(SKU_HUGE, "987654321.987654", INR), pricing(SKU_DOLLAR, "500.00", USD)),
				List.of(tenPercentDiscount(SKU_DISCOUNTED)), TRIGGERED_BY);
	}

	/**
	 * The same values as a {@code NUMERIC(20,6)} / {@code NUMERIC(20,4)} round-trip would
	 * return them: padded with trailing zeros. A checksum that changes here would report
	 * a spurious input change and make every replay look like a drift.
	 *
	 * <p>Every term of the contract is rescaled, not just the ones the three lines touch,
	 * because the whole snapshot is digested - and the unit prices deliberately carry more
	 * decimal places than the fixture's originals, which is the whole point of the round
	 * trip being simulated.
	 */
	private static CalculationInput rescaledSnapshot() {
		List<InvoiceLineInput> lines = List.of(
				new InvoiceLineInput(1, SKU_OVERCHARGED, new BigDecimal("10.000000"), Money.of("1000.000000", INR),
						Money.of("0.0000", INR), Money.of("0.0000", INR), source(1)),
				new InvoiceLineInput(2, SKU_MATCHED, new BigDecimal("4.000000"), Money.of("1000.000000", INR),
						Money.of("0.0000", INR), Money.of("0.0000", INR), source(2)),
				new InvoiceLineInput(3, SKU_DISCOUNTED, new BigDecimal("10.000000"), Money.of("1000.000000", INR),
						Money.of("50.0000", INR), Money.of("0.0000", INR), source(3)));
		return new CalculationInput(ORGANIZATION, CalculationType.COMBINED_VARIANCE,
				"INV-2024-0001", AS_OF, AS_OF, lines,
				List.of(rescaled(SKU_OVERCHARGED, "920.000000", INR), rescaled(SKU_MATCHED, "1000.000000", INR),
						rescaled(SKU_UNDERCHARGED, "1000.000000", INR), rescaled(SKU_DISCOUNTED, "1000.000000", INR),
						rescaled(SKU_FRACTIONAL, "33.333333", INR), rescaled(SKU_SUB_PAISA, "0.000050", INR),
						rescaled(SKU_HUGE, "987654321.987654", INR), rescaled(SKU_DOLLAR, "500.000000", USD)),
				List.of(DiscountTerm.percentage(SKU_DISCOUNTED, "DT-" + SKU_DISCOUNTED + "-10PCT", "10.00", TERM_START,
						TERM_END, 1)),
				TRIGGERED_BY);
	}

	/** A contract term as the database hands it back, padded to its storage scale. */
	private static PricingTerm rescaled(String productKey, String unitPrice, CurrencyCode currency) {
		return PricingTerm.fixedUnitPrice(productKey, "PT-" + productKey, new BigDecimal(unitPrice), currency,
				TERM_START, TERM_END, 1);
	}

	/**
	 * The same snapshot with SKU-MATCHED's contract price amended in place.
	 *
	 * <p>The rest of the contract is rebuilt in full, so the <em>only</em> difference from
	 * the original is that one price. Rebuilding a partial contract here would let the
	 * checksum differ for the wrong reason and make the test pass without ever proving
	 * that an in-place amendment is detected.
	 */
	private static CalculationInput amendedContractPrice(String skuMatchedPrice) {
		return new CalculationInput(ORGANIZATION,
				CalculationType.COMBINED_VARIANCE, "INV-2024-0001", AS_OF, AS_OF,
				rebuiltLines("1000.00"),
				List.of(pricing(SKU_OVERCHARGED, "920.00", INR), pricing(SKU_MATCHED, skuMatchedPrice, INR),
						pricing(SKU_UNDERCHARGED, "1000.00", INR), pricing(SKU_DISCOUNTED, "1000.00", INR),
						pricing(SKU_FRACTIONAL, "33.333333", INR), pricing(SKU_SUB_PAISA, "0.000050", INR),
						pricing(SKU_HUGE, "987654321.987654", INR), pricing(SKU_DOLLAR, "500.00", USD)),
				List.of(tenPercentDiscount(SKU_DISCOUNTED)), TRIGGERED_BY);
	}

	private static FinancialTruthEngine freshEngineWith(Clock clock) {
		ExpectedAmountCalculator expected = expectedCalculator();
		ActualAmountCalculator actual = actualCalculator();
		VarianceCalculator variance = varianceCalculator();
		return new FinancialTruthEngine(expected, actual, variance, impactAggregator(),
				List.of(new PricingVarianceRule(expected, actual, variance),
						new DiscountVarianceRule(expected, actual, variance)),
				clock);
	}

}
package com.fintech.cfo.financialtruth;

import static com.fintech.cfo.financialtruth.TruthEngineFixtures.AS_OF;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.INR;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.MARCH_END;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.MARCH_START;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.RUN_ID;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.SKU_DISCOUNTED;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.SKU_DOLLAR;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.SKU_MATCHED;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.SKU_OVERCHARGED;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.SKU_UNDERCHARGED;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.SKU_UNTERMED;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.USD;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.actualCalculator;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.engine;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.expectedCalculator;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.impactAggregator;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.input;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.line;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.lineWithDiscount;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.lineWithTax;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.netResult;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.pricing;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.pricingOnlyEngine;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.ruleResult;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.standardInput;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.tenPercentDiscount;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.varianceCalculator;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.fintech.cfo.financialtruth.calculator.ActualAmountCalculator;
import com.fintech.cfo.financialtruth.calculator.ExpectedAmountCalculator;
import com.fintech.cfo.financialtruth.calculator.FinancialTruthEngine;
import com.fintech.cfo.financialtruth.calculator.ImpactAggregator;
import com.fintech.cfo.financialtruth.calculator.VarianceCalculator;
import com.fintech.cfo.financialtruth.enums.CalculationConfidence;
import com.fintech.cfo.financialtruth.enums.CalculationStatus;
import com.fintech.cfo.financialtruth.enums.CalculationType;
import com.fintech.cfo.financialtruth.enums.DiscountType;
import com.fintech.cfo.financialtruth.enums.ImpactDirection;
import com.fintech.cfo.financialtruth.enums.RuleStatus;
import com.fintech.cfo.financialtruth.enums.VarianceType;
import com.fintech.cfo.financialtruth.model.CalculationInput;
import com.fintech.cfo.financialtruth.model.CalculationResult;
import com.fintech.cfo.financialtruth.model.CalculationRun;
import com.fintech.cfo.financialtruth.model.DiscountTerm;
import com.fintech.cfo.financialtruth.model.FinancialImpact;
import com.fintech.cfo.financialtruth.rules.DiscountVarianceRule;
import com.fintech.cfo.financialtruth.rules.PricingVarianceRule;
import com.fintech.cfo.financialtruth.service.CalculationService;
import com.fintech.cfo.shared.domain.CurrencyCode;
import com.fintech.cfo.shared.exception.BusinessRuleException;
import com.fintech.cfo.shared.exception.ValidationException;

/**
 * End-to-end tests for {@link FinancialTruthEngine} and its collaborators.
 *
 * <p>Every case runs against a fixed clock and a hand-built snapshot, so nothing here
 * depends on a container, a database or the wall clock.
 *
 * <p><b>Why this suite must never regress.</b> This is the seam where the two
 * trusted modules meet: {@code contract} decides which price applies and
 * {@code financialtruth} decides what that means. The engine is also the only
 * place that turns a set of per-rule measurements into a number a finance team
 * will act on. Three classes of failure here are unacceptable:
 *
 * <ul>
 * <li><b>Silent arithmetic drift.</b> An expected or actual amount that is off by
 * a paisa still renders as a plausible figure. Every amount in this suite is
 * asserted against a literal, never recomputed from the code under test.</li>
 * <li><b>Losing or double-counting money.</b> The engine emits one authoritative
 * combined row per line plus one component row per rule. Only the combined rows
 * may be aggregated; the tests assert that component rows carry no impact, so a
 * caller summing everything cannot count the same variance twice.</li>
 * <li><b>Overstating completeness.</b> When a line cannot be evaluated the engine
 * must disclose it, exclude it from the total and lower its confidence. A total
 * that silently drops a line is indistinguishable from a clean invoice, which is
 * the most damaging way this engine can be wrong.</li>
 * </ul>
 *
 * <p>Currency separation is asserted per case rather than in one place, because
 * the failure mode it prevents - adding rupees to dollars - is exactly the kind
 * of error that produces a plausible total.
 */
class FinancialTruthEngineTest {

	@Nested
	@DisplayName("the worked example from the architecture document")
	class AcceptanceExample {

		@Test
		void reconcilesAnOverchargeEndToEnd() {
			CalculationInput snapshot = standardInput(
					List.of(line(1, SKU_OVERCHARGED, "10000", "1000.00", INR)));

			CalculationRun run = engine().calculate(snapshot, RUN_ID);
			CalculationResult result = netResult(run, 1);

			assertThat(result.expectedAmount().amount()).isEqualByComparingTo("9200000.0000");
			assertThat(result.actualAmount().amount()).isEqualByComparingTo("10000000.0000");
			assertThat(result.varianceAmount().amount()).isEqualByComparingTo("800000.0000");
			assertThat(result.varianceAmount().currency().value()).isEqualTo("INR");
			assertThat(result.variance().direction()).isEqualTo(ImpactDirection.CUSTOMER_OVERPAY);
			assertThat(result.variance().type()).isEqualTo(VarianceType.Combined.INSTANCE);
			assertThat(result.expectedAmount().amount().toPlainString()).isEqualTo("9200000.0000");
		}

		@Test
		void recordsTheRuleVersionTheCalculationRunAndItsResultAgreeOn() {
			CalculationRun run = engine().calculate(
					standardInput(List.of(line(1, SKU_OVERCHARGED, "10000", "1000.00", INR))), RUN_ID);

			assertThat(run.ruleVersion()).isEqualTo("DISCOUNT_VARIANCE@1.0.0+PRICING_VARIANCE@1.0.0");
			assertThat(netResult(run, 1).ruleVersion()).isEqualTo(run.ruleVersion());
			assertThat(netResult(run, 1).ruleCode()).isEqualTo(FinancialTruthEngine.COMBINED_RULE_CODE);
			assertThat(ruleResult(run, "PRICING_VARIANCE", 1).ruleVersion()).isEqualTo("1.0.0");
			assertThat(ruleResult(run, "DISCOUNT_VARIANCE", 1).ruleVersion()).isEqualTo("1.0.0");
		}

		@Test
		void recordsTheInputChecksumTheRunWasProducedFrom() {
			CalculationInput snapshot = standardInput(
					List.of(line(1, SKU_OVERCHARGED, "10000", "1000.00", INR)));

			CalculationRun run = engine().calculate(snapshot, RUN_ID);

			// The checksum is what makes a re-run months later provable, so it is
			// persisted with the run and must be the snapshot's own digest, 64 hex
			// characters wide to fill the CHAR(64) column.
			assertThat(run.inputChecksum()).isEqualTo(snapshot.checksum()).hasSize(64);
			assertThat(run.status()).isEqualTo(CalculationStatus.Completed.INSTANCE);
			assertThat(run.runId()).isEqualTo(RUN_ID);
			assertThat(run.periodStart()).isEqualTo(AS_OF);
			assertThat(run.periodEnd()).isEqualTo(AS_OF);
		}

		@Test
		void reconcilesTheNetVarianceAgainstItsOwnComponents() {
			CalculationRun run = engine().calculate(
					standardInput(List.of(line(1, SKU_OVERCHARGED, "10000", "1000.00", INR))), RUN_ID);

			CalculationResult pricing = ruleResult(run, "PRICING_VARIANCE", 1);
			CalculationResult discount = ruleResult(run, "DISCOUNT_VARIANCE", 1);

			// net = pricing - discount. The discount rule made no monetary claim here, so
			// the net must be exactly the pricing component, to the paisa.
			assertThat(discount.status()).isEqualTo(RuleStatus.NotApplicable.INSTANCE);
			assertThat(netResult(run, 1).varianceAmount().amount())
				.isEqualByComparingTo(pricing.varianceAmount().amount());
		}
	}

	@Nested
	@DisplayName("single-line scenarios")
	class SingleLineScenarios {

		@Test
		void reportsZeroVarianceWhenTheInvoiceMatchesTheContract() {
			CalculationRun run = engine().calculate(
					standardInput(List.of(line(1, SKU_MATCHED, "3", "1000.00", INR))), RUN_ID);

			assertThat(netResult(run, 1).varianceAmount().isZero()).isTrue();
			assertThat(netResult(run, 1).variance().direction()).isEqualTo(ImpactDirection.NEUTRAL);
			assertThat(run.impacts()).singleElement().satisfies(impact -> {
				assertThat(impact.totalImpact().isZero()).isTrue();
				assertThat(impact.direction()).isEqualTo(ImpactDirection.NEUTRAL);
				assertThat(impact.confidence()).isEqualTo(CalculationConfidence.HIGH);
				assertThat(impact.evaluatedLineCount()).isEqualTo(1);
				assertThat(impact.unevaluatedLineCount()).isZero();
			});
		}

		@Test
		void reportsANegativeVarianceWhenTheCustomerWasUndercharged() {
			CalculationRun run = engine().calculate(
					standardInput(List.of(line(1, SKU_UNDERCHARGED, "500", "900.00", INR))), RUN_ID);

			assertThat(netResult(run, 1).varianceAmount().amount()).isEqualByComparingTo("-50000.0000");
			assertThat(run.impacts()).singleElement()
				.satisfies(impact -> assertThat(impact.direction()).isEqualTo(ImpactDirection.CUSTOMER_UNDERPAY));
		}

		@Test
		void treatsTaxAsPassThroughSoItInflatesThePayableButNotTheVariance() {
			// Tax is statutory, not contractual: it is expected on both sides, so it
			// raises the payable and cancels out of the variance. If it ever started
			// moving the variance, every tax-exclusive figure would be wrong.
			CalculationInput snapshot = standardInput(
					List.of(lineWithTax(1, SKU_MATCHED, "10", "1000.00", "180.00", INR)));

			CalculationRun run = engine().calculate(snapshot, RUN_ID);

			assertThat(netResult(run, 1).expectedAmount().amount()).isEqualByComparingTo("10180.0000");
			assertThat(netResult(run, 1).actualAmount().amount()).isEqualByComparingTo("10180.0000");
			assertThat(netResult(run, 1).varianceAmount().isZero()).isTrue();
		}

		@Test
		void computesTheSameVarianceWhateverTheTaxWhenThePriceIsWrong() {
			CalculationInput snapshot = standardInput(
					List.of(lineWithTax(1, SKU_OVERCHARGED, "10", "1000.00", "180.00", INR)));

			CalculationRun run = engine().calculate(snapshot, RUN_ID);

			assertThat(netResult(run, 1).varianceAmount().amount()).isEqualByComparingTo("800.0000");
		}
	}

	@Nested
	@DisplayName("discounts end to end")
	class DiscountsEndToEnd {

		@Test
		void reportsTheCustomerAsOverchargedWhenTheEntitlementWasNotHonoured() {
			// A discount entitlement that exists in the contract but was not granted on
			// the invoice is a real overcharge: the customer paid more than the deal
			// says they should have. This is the leakage case Phase 0 exists to find.
			CalculationInput snapshot = standardInput(
					List.of(lineWithDiscount(1, SKU_DISCOUNTED, "10", "1000.00", "50.00", INR)));

			CalculationRun run = engine().calculate(snapshot, RUN_ID);

			// Expected net 9 000, actual net 9 950: the customer was overcharged by the
			// discount they did not receive.
			assertThat(netResult(run, 1).expectedAmount().amount()).isEqualByComparingTo("9000.0000");
			assertThat(netResult(run, 1).actualAmount().amount()).isEqualByComparingTo("9950.0000");
			assertThat(netResult(run, 1).varianceAmount().amount()).isEqualByComparingTo("950.0000");
			assertThat(netResult(run, 1).variance().direction()).isEqualTo(ImpactDirection.CUSTOMER_OVERPAY);
		}

		@Test
		void reportsZeroVarianceWhenTheEntitlementWasHonouredExactly() {
			CalculationInput snapshot = standardInput(
					List.of(lineWithDiscount(1, SKU_DISCOUNTED, "10", "1000.00", "1000.00", INR)));

			CalculationRun run = engine().calculate(snapshot, RUN_ID);

			assertThat(netResult(run, 1).varianceAmount().isZero()).isTrue();
			assertThat(ruleResult(run, "DISCOUNT_VARIANCE", 1).varianceAmount().isZero()).isTrue();
		}

		@Test
		void decomposesTheNetVarianceIntoItsPricingAndDiscountComponents() {
			CalculationInput snapshot = standardInput(
					List.of(lineWithDiscount(1, SKU_DISCOUNTED, "10", "1200.00", "50.00", INR)));

			CalculationRun run = engine().calculate(snapshot, RUN_ID);

			// 2 000 over on price, 950 over on the discount not given: the net overcharge
			// is 2 000 - (-950) = 2 950.
			assertThat(ruleResult(run, "PRICING_VARIANCE", 1).varianceAmount().amount())
				.isEqualByComparingTo("2000.0000");
			assertThat(ruleResult(run, "DISCOUNT_VARIANCE", 1).varianceAmount().amount())
				.isEqualByComparingTo("-950.0000");
			assertThat(netResult(run, 1).varianceAmount().amount()).isEqualByComparingTo("2950.0000");
		}
	}

	@Nested
	@DisplayName("multi-line invoices")
	class MultiLineInvoices {

		@Test
		void sumsAlreadyRoundedLineVariancesIntoOneTotal() {
			// Each line is rounded once at NUMERIC(20,4) and the already-rounded values
			// are summed. Re-rounding the total instead would make it depend on the
			// order the lines arrived in, which no later audit could reproduce.
			CalculationInput snapshot = standardInput(List.of(
					line(1, SKU_OVERCHARGED, "10000", "1000.00", INR),
					line(2, SKU_MATCHED, "3", "1000.00", INR),
					line(3, SKU_UNDERCHARGED, "500", "900.00", INR)));

			CalculationRun run = engine().calculate(snapshot, RUN_ID);

			assertThat(netResult(run, 1).varianceAmount().amount()).isEqualByComparingTo("800000.0000");
			assertThat(netResult(run, 2).varianceAmount().isZero()).isTrue();
			assertThat(netResult(run, 3).varianceAmount().amount()).isEqualByComparingTo("-50000.0000");
			assertThat(run.impacts()).singleElement().satisfies(impact -> {
				assertThat(impact.totalImpact().amount()).isEqualByComparingTo("750000.0000");
				assertThat(impact.evaluatedLineCount()).isEqualTo(3);
				assertThat(impact.unevaluatedLineCount()).isZero();
				assertThat(impact.direction()).isEqualTo(ImpactDirection.CUSTOMER_OVERPAY);
			});
		}

		@Test
		void producesTheSameTotalWhateverOrderTheLinesWerePresentedIn() {
			// The order an ERP happened to export lines in is not part of the economic
			// fact, so it must not be able to change the answer.
			CalculationInput forwards = standardInput(List.of(
					line(1, SKU_OVERCHARGED, "10000", "1000.00", INR),
					line(2, SKU_UNDERCHARGED, "500", "900.00", INR)));
			CalculationInput backwards = standardInput(List.of(
					line(2, SKU_UNDERCHARGED, "500", "900.00", INR),
					line(1, SKU_OVERCHARGED, "10000", "1000.00", INR)));

			CalculationRun forwardsRun = engine().calculate(forwards, RUN_ID);
			CalculationRun backwardsRun = engine().calculate(backwards, RUN_ID);

			assertThat(forwardsRun.impacts().get(0).totalImpact().amount()).isEqualByComparingTo("750000.0000");
			assertThat(backwardsRun.impacts().get(0).totalImpact().amount())
				.isEqualByComparingTo(forwardsRun.impacts().get(0).totalImpact().amount());
		}

		@Test
		void disclosesLinesItCouldNotEvaluateAndDropsItsOwnConfidence() {
			CalculationInput snapshot = standardInput(List.of(
					line(1, SKU_OVERCHARGED, "10", "1000.00", INR),
					TruthEngineFixtures.lineWithoutQuantity(2, SKU_MATCHED, "1000.00", INR)));

			CalculationRun run = engine().calculate(snapshot, RUN_ID);

			assertThat(run.status()).isEqualTo(CalculationStatus.Completed.INSTANCE);
			// Only the pricing rule can fail on a missing quantity: SKU-MATCHED carries no
			// discount term, so the discount rule correctly reports NOT_APPLICABLE rather
			// than INCOMPLETE_INPUTS. Two different gaps, two different findings.
			assertThat(run.results()).filteredOn(result -> result.lineNumber() == 2
					&& result.status() == RuleStatus.IncompleteInputs.INSTANCE).hasSize(1);
			assertThat(run.results()).filteredOn(result -> result.lineNumber() == 2
					&& result.status() == RuleStatus.NotApplicable.INSTANCE).hasSize(1);
			// allSatisfy, not noneSatisfy: the disclosure required here is that *every*
			// result for an unevaluable line reports nothing at all. AssertJ's noneSatisfy
			// means the opposite - it fails as soon as any element meets the requirements -
			// so it would reject exactly the disclosure this test exists to demand.
			assertThat(run.results()).allSatisfy(result -> {
				if (result.lineNumber() == 2) {
					assertThat(result.expectedAmount()).isNull();
					assertThat(result.varianceAmount()).isNull();
				}
			});
			assertThat(run.impacts()).singleElement().satisfies(impact -> {
				assertThat(impact.evaluatedLineCount()).isEqualTo(1);
				assertThat(impact.unevaluatedLineCount()).isEqualTo(1);
				assertThat(impact.confidence()).isEqualTo(CalculationConfidence.MEDIUM);
				assertThat(impact.totalImpact().amount()).isEqualByComparingTo("800.0000");
			});
		}
	}

	@Nested
	@DisplayName("multiple currencies")
	class MultipleCurrencies {

		@Test
		void reportsOneImpactPerCurrencyRatherThanAddingThemTogether() {
			CalculationInput snapshot = standardInput(List.of(
					line(1, SKU_OVERCHARGED, "10", "1000.00", INR),
					line(2, SKU_DOLLAR, "1000", "600.00", USD)));

			CalculationRun run = engine().calculate(snapshot, RUN_ID);

			assertThat(run.impacts()).hasSize(2);
			assertThat(run.impacts().get(0).totalImpact().currency().value()).isEqualTo("INR");
			assertThat(run.impacts().get(0).totalImpact().amount()).isEqualByComparingTo("800.0000");
			assertThat(run.impacts().get(1).totalImpact().currency().value()).isEqualTo("USD");
			assertThat(run.impacts().get(1).totalImpact().amount()).isEqualByComparingTo("100000.0000");
		}

		@Test
		void measuresEachTotalsCoverageAgainstItsOwnCurrencyOnly() {
			// Two currencies, two lines, both evaluable. The INR total must not claim
			// that the USD line was excluded from it: that line was never a candidate,
			// so charging the INR total for it would both misstate its coverage and
			// downgrade a fully evaluated invoice for no reason.
			CalculationInput snapshot = standardInput(List.of(
					line(1, SKU_OVERCHARGED, "10", "1000.00", INR),
					line(2, SKU_DOLLAR, "1000", "600.00", USD)));

			CalculationRun run = engine().calculate(snapshot, RUN_ID);

			assertThat(run.impacts()).allSatisfy(impact -> {
				assertThat(impact.evaluatedLineCount()).isEqualTo(1);
				assertThat(impact.unevaluatedLineCount()).isZero();
				assertThat(impact.confidence()).isEqualTo(CalculationConfidence.HIGH);
			});
			assertThat(run.impacts().get(0).rationale()).contains("in INR");
			assertThat(run.impacts().get(1).rationale()).contains("in USD");
		}

		@Test
		void refusesToReportASingleTotalAcrossCurrencies() {
			CalculationInput snapshot = standardInput(List.of(
					line(1, SKU_OVERCHARGED, "10", "1000.00", INR),
					line(2, SKU_DOLLAR, "1000", "600.00", USD)));

			CalculationRun run = engine().calculate(snapshot, RUN_ID);
			CalculationService service = new CalculationService(engine());

			assertThatThrownBy(() -> service.netVarianceIn(run, INR))
				.isInstanceOf(ValidationException.class)
				.hasMessageContaining("2 currencies");
		}

		@Test
		void raisesWhenAProductHasNoContractPriceInAnyCurrency() {
			CalculationInput snapshot = standardInput(List.of(line(1, SKU_UNTERMED, "10", "1000.00", INR)));

			assertThatThrownBy(() -> engine().calculate(snapshot, RUN_ID))
				.isInstanceOf(BusinessRuleException.class)
				.hasMessageContaining("no contract pricing term");
		}
	}

	@Nested
	@DisplayName("term effective dates")
	class BoundaryDates {

		private CalculationInput marchSnapshot(LocalDate asOf) {
			return input(List.of(line(1, SKU_MATCHED, "10", "1200.00", INR)), asOf,
					List.of(pricing(SKU_MATCHED, "1000.00", INR, MARCH_START, MARCH_END, 1)), List.of());
		}

		@Test
		void reconcilesWhenTheInvoiceFallsOnTheFirstDayTheTermIsInForce() {
			CalculationRun run = engine().calculate(marchSnapshot(MARCH_START), RUN_ID);

			assertThat(netResult(run, 1).varianceAmount().amount()).isEqualByComparingTo("2000.0000");
		}

		@Test
		void reconcilesWhenTheInvoiceFallsOnTheLastDayTheTermIsInForce() {
			CalculationRun run = engine().calculate(marchSnapshot(MARCH_END), RUN_ID);

			assertThat(netResult(run, 1).varianceAmount().amount()).isEqualByComparingTo("2000.0000");
		}

		@Test
		void raisesOnTheDayBeforeTheTermComesIntoForce() {
			LocalDate dayBefore = MARCH_START.minusDays(1);

			assertThatThrownBy(() -> engine().calculate(marchSnapshot(dayBefore), RUN_ID))
				.isInstanceOf(BusinessRuleException.class)
				.hasMessageContaining("no contract pricing term");
		}

		@Test
		void raisesOnTheDayAfterTheTermExpires() {
			LocalDate dayAfter = MARCH_END.plusDays(1);

			assertThatThrownBy(() -> engine().calculate(marchSnapshot(dayAfter), RUN_ID))
				.isInstanceOf(BusinessRuleException.class)
				.hasMessageContaining("no contract pricing term");
		}
	}

	@Nested
	@DisplayName("engine configuration")
	class Configuration {

		@Test
		void ordersTheRuleSetFingerprintRegardlessOfInjectionOrder() {
			// The rule-set fingerprint is stored on every run, so it must depend only on
			// which rules and versions are registered - never on the order the container
			// happened to inject them.
			ExpectedAmountCalculator expected = expectedCalculator();
			ActualAmountCalculator actual = actualCalculator();
			VarianceCalculator variance = varianceCalculator();
			ImpactAggregator impact = impactAggregator();
			Clock clock = TruthEngineFixtures.FIXED_CLOCK;

			FinancialTruthEngine pricingFirst = new FinancialTruthEngine(expected, actual, variance, impact,
					List.of(new PricingVarianceRule(expected, actual, variance),
							new DiscountVarianceRule(expected, actual, variance)),
					clock);
			FinancialTruthEngine discountFirst = new FinancialTruthEngine(expected, actual, variance, impact,
					List.of(new DiscountVarianceRule(expected, actual, variance),
							new PricingVarianceRule(expected, actual, variance)),
					clock);

			assertThat(pricingFirst.ruleSetVersion()).isEqualTo(discountFirst.ruleSetVersion());
			assertThat(pricingFirst.ruleSetVersion()).isEqualTo("DISCOUNT_VARIANCE@1.0.0+PRICING_VARIANCE@1.0.0");
		}

		@Test
		void rejectsTwoRulesSharingACode() {
			ExpectedAmountCalculator expected = expectedCalculator();
			ActualAmountCalculator actual = actualCalculator();
			VarianceCalculator variance = varianceCalculator();

			assertThatThrownBy(() -> new FinancialTruthEngine(expected, actual, variance, impactAggregator(),
					List.of(new PricingVarianceRule(expected, actual, variance),
							new PricingVarianceRule(expected, actual, variance)),
					TruthEngineFixtures.FIXED_CLOCK))
				.isInstanceOf(ValidationException.class)
				.hasMessageContaining("duplicate rule code");
		}

		@Test
		void rejectsAnEmptyRuleSet() {
			assertThatThrownBy(() -> new FinancialTruthEngine(expectedCalculator(), actualCalculator(),
					varianceCalculator(), impactAggregator(), List.of(), TruthEngineFixtures.FIXED_CLOCK))
				.isInstanceOf(ValidationException.class)
				.hasMessageContaining("at least one FinancialRule");
		}

		@Test
		void refusesToGenerateItsOwnRunId() {
			// The run id is the key an auditor replays by, so it is the caller's to
			// supply. If the engine minted its own, a replay could never address the
			// original run.
			CalculationInput snapshot = standardInput(List.of(line(1, SKU_MATCHED, "1", "1000.00", INR)));

			assertThatThrownBy(() -> engine().calculate(snapshot, null))
				.isInstanceOf(ValidationException.class)
				.hasMessageContaining("runId");
		}

		@Test
		void worksWithASingleRegisteredRule() {
			CalculationRun run = pricingOnlyEngine().calculate(
					standardInput(List.of(lineWithDiscount(1, SKU_DISCOUNTED, "10", "1000.00", "50.00", INR))),
					RUN_ID);

			assertThat(run.ruleVersion()).isEqualTo("PRICING_VARIANCE@1.0.0");
			// With no discount rule registered the entitlement is unknown, so the engine
			// treats it as zero rather than guessing, and reports the difference. The
			// invoice granted 50.00 against a contract gross of 10000.00, so the customer
			// paid 50.00 LESS than the contract implies: the variance is negative, and its
			// sign is the whole point of the assertion.
			assertThat(netResult(run, 1).varianceAmount().amount()).isEqualByComparingTo("-50.0000");
		}

		@Test
		void disclosesAnUnauthorisedDiscountInsteadOfClaimingItCanDecomposeTheNet() {
			// A goodwill discount on the invoice with no contract entitlement behind it.
			// The net variance genuinely cannot be split into measured components, because
			// no DiscountVariance row exists. The engine must still answer with the total,
			// say that it is not decomposed, and refuse to claim HIGH confidence - and it
			// must not throw, because throwing would lose a real 500.00 finding.
			CalculationRun run = engine().calculate(
					standardInput(List.of(lineWithDiscount(1, SKU_MATCHED, "10", "1000.00", "500.00", INR))),
					RUN_ID);

			CalculationResult net = netResult(run, 1);
			assertThat(net.expectedAmount().amount()).isEqualByComparingTo("10000.0000");
			assertThat(net.actualAmount().amount()).isEqualByComparingTo("9500.0000");
			assertThat(net.varianceAmount().amount()).isEqualByComparingTo("-500.0000");
			assertThat(net.impact().confidence()).isEqualTo(CalculationConfidence.MEDIUM);
			assertThat(net.impact().rationale()).contains("cannot be split into its components")
				.contains("500.0000 INR");

			assertThat(run.results()).filteredOn(result -> result.lineNumber() == 1
					&& result.ruleCode().equals("DISCOUNT_VARIANCE")).singleElement()
				.satisfies(result -> assertThat(result.status()).isEqualTo(RuleStatus.NotApplicable.INSTANCE));
			assertThat(run.impacts()).singleElement().satisfies(impact -> {
				assertThat(impact.totalImpact().amount()).isEqualByComparingTo("-500.0000");
				assertThat(impact.confidence()).isEqualTo(CalculationConfidence.MEDIUM);
			});
		}
	}

	@Nested
	@DisplayName("lineage")
	class Lineage {

		@Test
		void everyResultCarriesTheSourceRowItCameFrom() {
			CalculationRun run = engine().calculate(
					standardInput(List.of(line(1, SKU_OVERCHARGED, "10", "1000.00", INR))), RUN_ID);

			assertThat(netResult(run, 1).source()).isNotNull();
			assertThat(netResult(run, 1).source().sourceRecordId()).isEqualTo("LINE-1");
			assertThat(netResult(run, 1).source().sourceRowNumber()).isEqualTo(1L);
		}

		@Test
		void everyResultCarriesTheTermsItEvaluatedAndAnExplanation() {
			CalculationRun run = engine().calculate(
					standardInput(List.of(lineWithDiscount(1, SKU_DISCOUNTED, "10", "1000.00", "50.00", INR))),
					RUN_ID);

			CalculationResult combined = netResult(run, 1);
			assertThat(combined.evaluatedTerms()).extracting(term -> term.termId())
				.contains("PT-" + SKU_DISCOUNTED, "DT-" + SKU_DISCOUNTED + "-10PCT");
			assertThat(combined.explanation()).contains("Expected net").contains("actual net");
		}

		@Test
		void componentRowsCarryNoImpactSoNothingCanBeDoubleCounted() {
			CalculationRun run = engine().calculate(
					standardInput(List.of(line(1, SKU_OVERCHARGED, "10", "1000.00", INR))), RUN_ID);

			assertThat(ruleResult(run, "PRICING_VARIANCE", 1).impact()).isNull();
			assertThat(ruleResult(run, "DISCOUNT_VARIANCE", 1).impact()).isNull();
			assertThat(netResult(run, 1).impact()).isNotNull();
		}
	}

	@Nested
	@DisplayName("impact aggregation")
	class ImpactAggregation {

		@Test
		void refusesToAggregateComponentRows() {
			// A caller reaching for the decomposing rows is a programming error, and is
			// rejected rather than quietly returned as a total.
			CalculationRun run = engine().calculate(
					standardInput(List.of(line(1, SKU_OVERCHARGED, "10", "1000.00", INR))), RUN_ID);
			List<CalculationResult> componentRows = run.results().stream()
					.filter(result -> result.calculationType() == CalculationType.PRICING_VARIANCE)
					.toList();

			assertThatThrownBy(() -> impactAggregator().aggregate(componentRows, Map.of(INR, 1)))
				.isInstanceOf(ValidationException.class)
				.hasMessageContaining("count the same money twice");
		}

		@Test
		void reportsNoImpactAtAllWhenNothingCouldBeEvaluated() {
			CalculationInput snapshot = standardInput(List.of(
					TruthEngineFixtures.lineWithoutQuantity(1, SKU_MATCHED, "1000.00", INR)));

			CalculationRun run = engine().calculate(snapshot, RUN_ID);

			assertThat(run.impacts()).isEmpty();
			CalculationService service = new CalculationService(engine());
			assertThatThrownBy(() -> service.netVarianceIn(run, CurrencyCode.inr()))
				.isInstanceOf(ValidationException.class)
				.hasMessageContaining("no evaluated variance");
		}

		@Test
		void totalImpactAlwaysCarriesItsCurrency() {
			// A total without a currency is not an amount. Asserting it here rather than
			// in one place catches the case where a new currency path returns a bare
			// BigDecimal.
			CalculationRun run = engine().calculate(
					standardInput(List.of(line(1, SKU_DOLLAR, "100", "600.00", USD))), RUN_ID);

			assertThat(run.impacts()).singleElement()
					.satisfies(impact -> assertThat(impact.totalImpact().currency().value()).isEqualTo("USD"));
			assertThat(netResult(run, 1).varianceAmount().currency().value()).isEqualTo("USD");
		}

		@Test
		void aggregatesOnlyCombinedRowsEvenWhenGivenTheWholeRun() {
			CalculationRun run = engine().calculate(
					standardInput(List.of(line(1, SKU_OVERCHARGED, "10", "1000.00", INR))), RUN_ID);

			// Passing the whole run is the natural thing for a caller to do, and the
			// aggregator must not quietly mix component rows into the total.
			assertThatThrownBy(() -> impactAggregator().aggregate(run.results(), Map.of(INR, 1)))
				.isInstanceOf(ValidationException.class);
		}
	}

	@Test
	@DisplayName("a snapshot must carry an explicit as-of date")
	void refusesAnAsOfDateBeforeTheInvoiceDate() {
		assertThatThrownBy(() -> TruthEngineFixtures.inputWithInvoiceDate(
				List.of(line(1, SKU_MATCHED, "1", "1000.00", INR)), AS_OF, AS_OF.minusDays(1), List.of(), List.of()))
			.isInstanceOf(ValidationException.class)
			.hasMessageContaining("must not be before invoiceDate");
	}

	@Test
	@DisplayName("a discount term with no value does not become a zero entitlement")
	void anUnreadableDiscountIsNotAnEntitlementOfZero() {
		DiscountTerm hollow = new DiscountTerm(SKU_DISCOUNTED, "DT-HOLLOW", DiscountType.PERCENTAGE, null, null, null,
				TruthEngineFixtures.TERM_START, TruthEngineFixtures.TERM_END, 1);
		CalculationInput snapshot = input(List.of(lineWithDiscount(1, SKU_DISCOUNTED, "10", "1000.00", "50.00", INR)),
				AS_OF, List.of(pricing(SKU_DISCOUNTED, "1000.00", INR)), List.of(hollow));

		CalculationRun run = engine().calculate(snapshot, RUN_ID);

		assertThat(ruleResult(run, "DISCOUNT_VARIANCE", 1).status()).isEqualTo(RuleStatus.IncompleteInputs.INSTANCE);
		assertThat(run.results()).filteredOn(result -> result.lineNumber() == 1
				&& result.calculationType() == CalculationType.COMBINED_VARIANCE).isEmpty();
		assertThat(run.impacts()).isEmpty();
	}

	@Test
	@DisplayName("the default contract grants ten percent off SKU-DISCOUNTED")
	void documentsTheFixedDataset() {
		CalculationInput snapshot = standardInput(List.of(lineWithDiscount(1, SKU_DISCOUNTED, "10", "1000.00", "0.00", INR)));

		assertThat(snapshot.effectiveDiscountTerms(SKU_DISCOUNTED, AS_OF)).singleElement()
			.satisfies(term -> assertThat(term.discountValue()).isEqualByComparingTo("10"));
		assertThat(snapshot.effectivePricingTerm(SKU_DISCOUNTED, AS_OF).unitPrice()).isEqualByComparingTo("1000.00");
	}

}
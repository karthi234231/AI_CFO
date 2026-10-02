package com.fintech.cfo.financialtruth;

import static com.fintech.cfo.financialtruth.TruthEngineFixtures.AS_OF;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.INR;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.MARCH_END;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.MARCH_START;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.SKU_FRACTIONAL;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.SKU_HUGE;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.SKU_MATCHED;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.SKU_OVERCHARGED;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.SKU_SUB_PAISA;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.SKU_UNDERCHARGED;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.SKU_UNTERMED;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.USD;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.actualCalculator;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.expectedCalculator;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.input;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.line;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.lineWithoutQuantity;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.pricing;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.varianceCalculator;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.fintech.cfo.financialtruth.enums.CalculationType;
import com.fintech.cfo.financialtruth.enums.ImpactDirection;
import com.fintech.cfo.financialtruth.enums.PricingType;
import com.fintech.cfo.financialtruth.enums.RuleStatus;
import com.fintech.cfo.financialtruth.enums.VarianceType;
import com.fintech.cfo.financialtruth.model.CalculationInput;
import com.fintech.cfo.financialtruth.model.InvoiceLineInput;
import com.fintech.cfo.financialtruth.model.PricingTerm;
import com.fintech.cfo.financialtruth.rules.PricingVarianceRule;
import com.fintech.cfo.financialtruth.rules.RuleContext;
import com.fintech.cfo.financialtruth.rules.RuleEvaluationResult;
import com.fintech.cfo.shared.exception.BusinessRuleException;

/**
 * Pure unit tests for {@link PricingVarianceRule}.
 *
 * <p>No Spring context, no database, no clock: a rule is constructed by hand and
 * handed a {@link RuleContext} built from literals.
 *
 * <p><b>Why this must never regress.</b> A rule is a unit of arithmetic that will
 * be replayed by auditors against figures already shown to customers, so it has
 * to fail in a specific, stated way rather than in whatever way is convenient.
 * The behaviours locked down here are the ones that distinguish a trustworthy
 * engine from one that manufactures findings:
 *
 * <ul>
 * <li><b>Refuse rather than default.</b> No pricing term in force, a contract
 * price outside the bounds the contract itself declares, or a term in a different
 * currency from the invoice all raise. Defaulting the expected amount to the
 * invoiced amount would report a perfectly clean invoice for a line that nobody
 * actually checked against anything.</li>
 * <li><b>Distinguish "not evaluated" from "zero".</b> A missing quantity, a zero
 * quantity, and a pricing type this module cannot evaluate all yield
 * {@link RuleStatus.IncompleteInputs} with no figures at all. Reporting a zero
 * variance instead would silently drop a real finding from the total.</li>
 * <li><b>Round exactly once.</b> At {@code NUMERIC(20,4)}, HALF_UP, at the
 * sub-paisa boundary - verified against figures a {@code double} cannot hold, so
 * the test also proves the double path would have been wrong.</li>
 * <li><b>Never cross a currency boundary.</b> The engine has no FX component, so
 * a silent conversion here would invent an unaudited rate that every downstream
 * figure would inherit.</li>
 * </ul>
 *
 * <p>Term selection is asserted independently of pricing arithmetic: which row is
 * in force on a given date is a separate decision from what that row says, and
 * conflating the two makes an overlap bug look like a rounding bug.
 */
class PricingVarianceRuleTest {

	private final PricingVarianceRule rule = new PricingVarianceRule(expectedCalculator(), actualCalculator(),
			varianceCalculator());

	private static RuleContext contextFor(InvoiceLineInput line, LocalDate asOf, PricingTerm term) {
		return RuleContext.builder()
				.line(line)
				.asOfDate(asOf)
				.currency(line.currency())
				.pricingTerm(term)
				.discountTerms(List.of())
				.build();
	}

	@Nested
	@DisplayName("normal pricing")
	class NormalPricing {

		@Test
		void reportsAnOverchargeWhenTheInvoicePriceExceedsTheContractPrice() {
			// The worked example from the architecture document.
			RuleEvaluationResult result = rule.evaluate(contextFor(
					line(1, SKU_OVERCHARGED, "10000", "1000.00", INR), AS_OF, pricing(SKU_OVERCHARGED, "920.00", INR)));

			assertThat(result.status()).isEqualTo(RuleStatus.Evaluated.INSTANCE);
			assertThat(result.expected().amount().amount()).isEqualByComparingTo("9200000.0000");
			assertThat(result.actual().amount().amount()).isEqualByComparingTo("10000000.0000");
			assertThat(result.variance().amount().amount()).isEqualByComparingTo("800000.0000");
			assertThat(result.variance().isOvercharge()).isTrue();
			assertThat(result.variance().direction()).isEqualTo(ImpactDirection.CUSTOMER_OVERPAY);
			assertThat(result.variance().type()).isEqualTo(VarianceType.Pricing.INSTANCE);
		}

		@Test
		void reportsZeroVarianceWhenTheInvoiceMatchesTheContract() {
			RuleEvaluationResult result = rule.evaluate(contextFor(line(1, SKU_MATCHED, "3", "1000.00", INR), AS_OF,
					pricing(SKU_MATCHED, "1000.00", INR)));

			assertThat(result.variance().isZero()).isTrue();
			assertThat(result.variance().direction()).isEqualTo(ImpactDirection.NEUTRAL);
			assertThat(result.variance().amount().amount()).isEqualByComparingTo("0.0000");
		}

		@Test
		void reportsANegativeVarianceWhenTheCustomerWasUndercharged() {
			RuleEvaluationResult result = rule.evaluate(contextFor(
					line(1, SKU_UNDERCHARGED, "500", "900.00", INR), AS_OF, pricing(SKU_UNDERCHARGED, "1000.00", INR)));

			assertThat(result.expected().amount().amount()).isEqualByComparingTo("500000.0000");
			assertThat(result.actual().amount().amount()).isEqualByComparingTo("450000.0000");
			assertThat(result.variance().amount().amount()).isEqualByComparingTo("-50000.0000");
			assertThat(result.variance().isUndercharge()).isTrue();
			assertThat(result.variance().direction()).isEqualTo(ImpactDirection.CUSTOMER_UNDERPAY);
		}

		@Test
		void recordsTheExactContractTermsItEvaluated() {
			RuleEvaluationResult result = rule.evaluate(contextFor(
					line(1, SKU_OVERCHARGED, "10", "1000.00", INR), AS_OF, pricing(SKU_OVERCHARGED, "920.00", INR)));

			assertThat(result.evaluatedTerms()).singleElement().satisfies(term -> {
				assertThat(term.termId()).isEqualTo("PT-" + SKU_OVERCHARGED);
				assertThat(term.termVersion()).isEqualTo(1);
				assertThat(term.termType()).isEqualTo("FIXED_UNIT_PRICE");
				assertThat(term.effectiveFrom()).isEqualTo(TruthEngineFixtures.TERM_START);
				assertThat(term.effectiveTo()).isEqualTo(TruthEngineFixtures.TERM_END);
			});
		}

		@Test
		void exposesAStableCodeAndVersion() {
			// The code and version are persisted on every result and form part of the
			// rule-set fingerprint. Changing either silently invalidates the provenance
			// of every historical figure, so they are pinned here.
			assertThat(rule.code()).isEqualTo("PRICING_VARIANCE");
			assertThat(rule.version()).isEqualTo("1.0.0");
			assertThat(rule.versionedCode()).isEqualTo("PRICING_VARIANCE@1.0.0");
			assertThat(rule.calculationType()).isEqualTo(CalculationType.PRICING_VARIANCE);
		}
	}

	@Nested
	@DisplayName("incomplete inputs")
	class IncompleteInputs {

		@Test
		void reportsIncompleteInputsRatherThanAVarianceWhenTheQuantityIsMissing() {
			RuleEvaluationResult result = rule.evaluate(contextFor(
					lineWithoutQuantity(1, SKU_OVERCHARGED, "1000.00", INR), AS_OF,
					pricing(SKU_OVERCHARGED, "920.00", INR)));

			assertThat(result.status()).isEqualTo(RuleStatus.IncompleteInputs.INSTANCE);
			assertThat(result.expected()).isNull();
			assertThat(result.actual()).isNull();
			assertThat(result.variance()).isNull();
			assertThat(result.explanation()).contains("quantity");
		}

		@Test
		void treatsAZeroQuantityAsIncompleteRatherThanAsAZeroVariance() {
			// Zero and absent are different facts. A zero quantity means "nothing was
			// billed", which is a legitimate clean result; a missing one means the export
			// lost data. Only the second should lower confidence.
			RuleEvaluationResult result = rule.evaluate(contextFor(
					line(1, SKU_OVERCHARGED, "0", "1000.00", INR), AS_OF, pricing(SKU_OVERCHARGED, "920.00", INR)));

			assertThat(result.status()).isEqualTo(RuleStatus.IncompleteInputs.INSTANCE);
			assertThat(result.variance()).isNull();
		}

		@Test
		void reportsIncompleteInputsForAPricingTypeThisModuleCannotEvaluate() {
			PricingTerm tiered = new PricingTerm(SKU_OVERCHARGED, "PT-TIERED", PricingType.TIERED,
					new BigDecimal("900.00"), null, null, INR, TruthEngineFixtures.TERM_START,
					TruthEngineFixtures.TERM_END, 1);

			RuleEvaluationResult result = rule.evaluate(contextFor(line(1, SKU_OVERCHARGED, "10", "1000.00", INR),
					AS_OF, tiered));

			assertThat(result.status()).isEqualTo(RuleStatus.IncompleteInputs.INSTANCE);
			assertThat(result.explanation()).contains("TIERED");
			assertThat(result.variance()).isNull();
		}

		@Test
		void neverReportsAVarianceForALineItCouldNotEvaluate() {
			// isEvaluated() is what the engine and the API use to decide whether a row
			// may be counted. It must be false whenever no figure was produced, and an
			// empty term list must accompany it so no evidence is claimed.
			RuleEvaluationResult result = rule.evaluate(contextFor(
					lineWithoutQuantity(7, SKU_OVERCHARGED, "1000.00", INR), AS_OF,
					pricing(SKU_OVERCHARGED, "920.00", INR)));

			assertThat(result.isEvaluated()).isFalse();
			assertThat(result.evaluatedTerms()).isEmpty();
		}
	}

	@Nested
	@DisplayName("missing and contradictory contract terms")
	class MissingTerms {

		@Test
		void raisesRatherThanDefaultingWhenNoPricingTermIsInForce() {
			RuleContext context = contextFor(line(1, SKU_UNTERMED, "100", "1000.00", INR), AS_OF, null);

			assertThatThrownBy(() -> rule.evaluate(context))
				.isInstanceOf(BusinessRuleException.class)
				.hasMessageContaining("no contract pricing term")
				.hasMessageContaining(SKU_UNTERMED);
		}

		@Test
		void raisesWhenTheContractPriceIsOutsideTheBoundsTheContractItselfDeclares() {
			PricingTerm contradictory = new PricingTerm(SKU_OVERCHARGED, "PT-BAD", PricingType.FIXED_UNIT_PRICE,
					new BigDecimal("100.00"), new BigDecimal("110.00"), null, INR, TruthEngineFixtures.TERM_START,
					TruthEngineFixtures.TERM_END, 1);

			assertThatThrownBy(() -> rule.evaluate(contextFor(line(1, SKU_OVERCHARGED, "10", "1000.00", INR), AS_OF,
					contradictory)))
				.isInstanceOf(BusinessRuleException.class)
				.hasMessageContaining("below its own minimum");
		}
	}

	@Nested
	@DisplayName("currency safety")
	class CurrencySafety {

		@Test
		void raisesWhenTheContractIsInADifferentCurrencyFromTheInvoice() {
			RuleContext context = contextFor(line(1, SKU_OVERCHARGED, "10", "1000.00", INR), AS_OF,
					pricing(SKU_OVERCHARGED, "920.00", USD));

			assertThatThrownBy(() -> rule.evaluate(context))
				.isInstanceOf(BusinessRuleException.class)
				.hasMessageContaining("never converts between currencies");
		}

		@Test
		void computesTheSameAmountsInAnotherCurrency() {
			RuleEvaluationResult result = rule.evaluate(contextFor(line(1, SKU_OVERCHARGED, "1000", "600.00", USD),
					AS_OF, pricing(SKU_OVERCHARGED, "500.00", USD)));

			assertThat(result.expected().amount().currency().value()).isEqualTo("USD");
			assertThat(result.expected().amount().amount()).isEqualByComparingTo("500000.0000");
			assertThat(result.variance().amount().amount()).isEqualByComparingTo("100000.0000");
		}
	}

	@Nested
	@DisplayName("rounding")
	class Rounding {

		@Test
		void roundsOnceToFourDecimalPlacesHalfUp() {
			// 33.333333 x 3 = 99.999999, which is 100.0000 at four decimal places.
			RuleEvaluationResult result = rule.evaluate(contextFor(
					line(1, SKU_FRACTIONAL, "3", "33.333334", INR), AS_OF, pricing(SKU_FRACTIONAL, "33.333333", INR)));

			assertThat(result.expected().amount().amount().toPlainString()).isEqualTo("100.0000");
			assertThat(result.actual().amount().amount().toPlainString()).isEqualTo("100.0000");
			assertThat(result.variance().amount().amount().toPlainString()).isEqualTo("0.0000");
		}

		@Test
		void roundsAHalfUpAtTheSubPaisaBoundaryRatherThanTruncating() {
			// 0.000050 rounds up to 0.0001; 0.000040 rounds down to 0.0000.
			RuleEvaluationResult result = rule.evaluate(contextFor(
					line(1, SKU_SUB_PAISA, "1", "0.000040", INR), AS_OF, pricing(SKU_SUB_PAISA, "0.000050", INR)));

			assertThat(result.expected().amount().amount().toPlainString()).isEqualTo("0.0001");
			assertThat(result.actual().amount().amount().toPlainString()).isEqualTo("0.0000");
			assertThat(result.variance().amount().amount().toPlainString()).isEqualTo("-0.0001");
		}

		@Test
		void isExactWellBeyondThePrecisionOfADouble() {
			// A double cannot hold either figure: 987654321988641.6543 needs a
			// denominator of ten thousand, which is not a power of two.
			RuleEvaluationResult result = rule.evaluate(contextFor(
					line(1, SKU_HUGE, "1000000.000001", "1000000000.987654", INR), AS_OF,
					pricing(SKU_HUGE, "987654321.987654", INR)));

			assertThat(result.expected().amount().amount().toPlainString()).isEqualTo("987654321988641.6543");
			assertThat(result.actual().amount().amount().toPlainString()).isEqualTo("1000000000988654.0000");
			assertThat(result.variance().amount().amount().toPlainString()).isEqualTo("12345679000012.3457");
			// The last assertion is the point of the case: it demonstrates that the
			// double-based implementation of this same arithmetic would produce a
			// different, and wrongly precise, figure.
			assertThat(new BigDecimal(987654321988641.6543d).toPlainString()).isNotEqualTo("987654321988641.6543");
		}
	}

	@Nested
	@DisplayName("term effective dates")
	class BoundaryDates {

		private PricingTerm marchTerm() {
			return pricing(SKU_MATCHED, "1000.00", INR, MARCH_START, MARCH_END, 1);
		}

		@Test
		void appliesOnTheFirstDayOfTheWindow() {
			assertThat(marchTerm().isEffectiveOn(MARCH_START)).isTrue();
		}

		@Test
		void appliesOnTheLastDayOfTheWindow() {
			assertThat(marchTerm().isEffectiveOn(MARCH_END)).isTrue();
		}

		@Test
		void doesNotApplyTheDayBeforeTheWindowOpens() {
			assertThat(marchTerm().isEffectiveOn(MARCH_START.minusDays(1))).isFalse();
		}

		@Test
		void doesNotApplyTheDayAfterTheWindowCloses() {
			assertThat(marchTerm().isEffectiveOn(MARCH_END.plusDays(1))).isFalse();
		}

		@Test
		void reconcilesOnTheInvoiceDateWhenTheTermIsInForceThatDay() {
			CalculationInput snapshot = input(List.of(line(1, SKU_MATCHED, "10", "1200.00", INR)), MARCH_START,
					List.of(marchTerm()), List.of());

			RuleEvaluationResult result = rule.evaluate(contextFor(line(1, SKU_MATCHED, "10", "1200.00", INR),
					MARCH_START, snapshot.effectivePricingTerm(SKU_MATCHED, MARCH_START)));

			assertThat(result.variance().amount().amount()).isEqualByComparingTo("2000.0000");
		}

		@Test
		void findsNoTermTheDayBeforeTheWindowOpens() {
			LocalDate dayBefore = MARCH_START.minusDays(1);
			CalculationInput snapshot = input(List.of(line(1, SKU_MATCHED, "10", "1200.00", INR)), dayBefore,
					List.of(marchTerm()), List.of());

			assertThat(snapshot.effectivePricingTerm(SKU_MATCHED, dayBefore)).isNull();
		}

		@Test
		void findsNoTermTheDayAfterTheWindowCloses() {
			LocalDate dayAfter = MARCH_END.plusDays(1);
			CalculationInput snapshot = input(List.of(line(1, SKU_MATCHED, "10", "1200.00", INR)), dayAfter,
					List.of(marchTerm()), List.of());

			assertThat(snapshot.effectivePricingTerm(SKU_MATCHED, dayAfter)).isNull();
		}
	}

	@Nested
	@DisplayName("applicability")
	class Applicability {

		@Test
		void appliesToEveryLineThatCarriesAQuantity() {
			assertThat(rule.appliesTo(contextFor(line(1, SKU_MATCHED, "1", "1000.00", INR), AS_OF, null))).isTrue();
		}

		@Test
		void picksTheHighestVersionWhenTwoTermsOverlapInTime() {
			PricingTerm older = pricing(SKU_MATCHED, "1000.00", INR, TruthEngineFixtures.TERM_START,
					TruthEngineFixtures.TERM_END, 1);
			PricingTerm newer = pricing(SKU_MATCHED, "900.00", INR, TruthEngineFixtures.TERM_START,
					TruthEngineFixtures.TERM_END, 2);
			CalculationInput snapshot = input(List.of(line(1, SKU_MATCHED, "10", "1000.00", INR)), AS_OF,
					List.of(older, newer), List.of());

			assertThat(snapshot.effectivePricingTerm(SKU_MATCHED, AS_OF).unitPrice())
				.isEqualByComparingTo("900.00");
		}
	}

}
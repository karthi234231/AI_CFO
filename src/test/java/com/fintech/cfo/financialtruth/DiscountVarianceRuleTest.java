package com.fintech.cfo.financialtruth;

import static com.fintech.cfo.financialtruth.TruthEngineFixtures.AS_OF;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.INR;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.MARCH_END;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.MARCH_START;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.SKU_DISCOUNTED;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.SKU_MATCHED;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.TERM_END;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.TERM_START;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.USD;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.actualCalculator;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.expectedCalculator;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.line;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.lineWithDiscount;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.pricing;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.tenPercentDiscount;
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
import com.fintech.cfo.financialtruth.enums.DiscountType;
import com.fintech.cfo.financialtruth.enums.PricingType;
import com.fintech.cfo.financialtruth.enums.RuleStatus;
import com.fintech.cfo.financialtruth.enums.VarianceType;
import com.fintech.cfo.financialtruth.model.CalculationInput;
import com.fintech.cfo.financialtruth.model.DiscountTerm;
import com.fintech.cfo.financialtruth.model.InvoiceLineInput;
import com.fintech.cfo.financialtruth.model.PricingTerm;
import com.fintech.cfo.financialtruth.rules.DiscountVarianceRule;
import com.fintech.cfo.financialtruth.rules.RuleContext;
import com.fintech.cfo.financialtruth.rules.RuleEvaluationResult;
import com.fintech.cfo.shared.domain.CurrencyCode;
import com.fintech.cfo.shared.exception.BusinessRuleException;

/**
 * Pure unit tests for {@link DiscountVarianceRule}.
 *
 * <p>Note the sign on this rule's component: a positive figure means the customer
 * received more discount than contracted, which works against the supplier. The
 * engine negates it when folding it into the net payable so that a positive net
 * variance is always money recoverable by the customer.
 *
 * <p><b>Why this must never regress.</b> Discounts are the most heavily
 * negotiated part of any contract and the easiest place for a number to go wrong
 * quietly, because an entitlement that is slightly over- or under-stated still
 * produces a plausible net. Three properties are therefore pinned:
 *
 * <ul>
 * <li><b>The entitlement is measured against the contract gross, not the invoiced
 * gross.</b> Using the invoiced figure would let an inflated invoice inflate its
 * own discount, which would cancel out exactly the overcharge being detected.</li>
 * <li><b>An entitlement can never exceed the gross it reduces.</b> A fixed term
 * larger than the line is capped, so the payable stays arithmetically sensible
 * rather than going negative or negative-and-then-some.</li>
 * <li><b>"No entitlement" and "unreadable entitlement" are different answers.</b>
 * A contract that grants no discount yields {@link RuleStatus.NotApplicable}; a
 * term carrying no usable value, or no contracted gross to discount, yields
 * {@link RuleStatus.IncompleteInputs}. Collapsing the second into the first
 * would turn an unreadable contract term into a clean invoice.</li>
 * </ul>
 *
 * <p>Stacked terms must also be order-independent and sum to a fixed total, since
 * the order a contract's clauses are stored in is not an economic fact.
 */
class DiscountVarianceRuleTest {

	private final DiscountVarianceRule rule = new DiscountVarianceRule(expectedCalculator(), actualCalculator(),
			varianceCalculator());

	private static RuleContext contextFor(InvoiceLineInput line, PricingTerm pricingTerm,
			List<DiscountTerm> discountTerms) {
		return RuleContext.builder()
				.line(line)
				.asOfDate(AS_OF)
				.currency(line.currency())
				.pricingTerm(pricingTerm)
				.discountTerms(discountTerms)
				.build();
	}

	/** Contract gross of 10 000.00 INR: ten units at 1 000.00. */
	private static InvoiceLineInput tenUnitsAtThousandWithDiscount(String discountAmount) {
		return lineWithDiscount(1, SKU_DISCOUNTED, "10", "1000.00", discountAmount, INR);
	}

	private static PricingTerm thousandInr() {
		return pricing(SKU_DISCOUNTED, "1000.00", INR);
	}

	@Nested
	@DisplayName("percentage discounts")
	class PercentageDiscounts {

		@Test
		void reportsTheFullEntitlementWhenTheInvoiceGrantsNoneOfIt() {
			RuleEvaluationResult result = rule.evaluate(contextFor(tenUnitsAtThousandWithDiscount("50.00"),
					thousandInr(), List.of(tenPercentDiscount(SKU_DISCOUNTED))));

			assertThat(result.status()).isEqualTo(RuleStatus.Evaluated.INSTANCE);
			assertThat(result.expected().amount().amount()).isEqualByComparingTo("1000.0000");
			assertThat(result.actual().amount().amount()).isEqualByComparingTo("50.0000");
			assertThat(result.variance().amount().amount()).isEqualByComparingTo("-950.0000");
			assertThat(result.variance().type()).isEqualTo(VarianceType.Discount.INSTANCE);
		}

		@Test
		void reportsZeroVarianceWhenTheEntitlementWasHonouredInFull() {
			RuleEvaluationResult result = rule.evaluate(contextFor(tenUnitsAtThousandWithDiscount("1000.00"),
					thousandInr(), List.of(tenPercentDiscount(SKU_DISCOUNTED))));

			assertThat(result.variance().isZero()).isTrue();
			assertThat(result.variance().amount().amount().toPlainString()).isEqualTo("0.0000");
		}

		@Test
		void reportsAPositiveComponentWhenTheInvoiceGrantsMoreDiscountThanEntitled() {
			RuleEvaluationResult result = rule.evaluate(contextFor(tenUnitsAtThousandWithDiscount("1500.00"),
					thousandInr(), List.of(tenPercentDiscount(SKU_DISCOUNTED))));

			assertThat(result.variance().amount().amount()).isEqualByComparingTo("500.0000");
			assertThat(result.variance().isOvercharge()).isTrue();
		}

		@Test
		void sumsStackedTermsFromTheSameBase() {
			DiscountTerm fivePercent = DiscountTerm.percentage(SKU_DISCOUNTED, "DT-FIVE", "5", TERM_START, TERM_END, 2);
			RuleEvaluationResult result = rule.evaluate(contextFor(tenUnitsAtThousandWithDiscount("0.00"),
					thousandInr(), List.of(tenPercentDiscount(SKU_DISCOUNTED), fivePercent)));

			// 10% of 10 000 plus 5% of 10 000. Each is rounded once, then summed.
			assertThat(result.expected().amount().amount()).isEqualByComparingTo("1500.0000");
		}

		@Test
		void producesTheSameTotalWhateverOrderStackedTermsArriveIn() {
			// The order a contract's discount clauses happen to be stored in is not an
			// economic fact, so it must not be able to change the entitlement.
			DiscountTerm fivePercent = DiscountTerm.percentage(SKU_DISCOUNTED, "DT-FIVE", "5", TERM_START, TERM_END, 2);
			RuleEvaluationResult forwards = rule.evaluate(contextFor(tenUnitsAtThousandWithDiscount("0.00"),
					thousandInr(), List.of(tenPercentDiscount(SKU_DISCOUNTED), fivePercent)));
			RuleEvaluationResult backwards = rule.evaluate(contextFor(tenUnitsAtThousandWithDiscount("0.00"),
					thousandInr(), List.of(fivePercent, tenPercentDiscount(SKU_DISCOUNTED))));

			assertThat(backwards.expected().amount().amount()).isEqualByComparingTo(forwards.expected().amount().amount());
		}

		@Test
		void recordsEveryTermItStacked() {
			DiscountTerm fivePercent = DiscountTerm.percentage(SKU_DISCOUNTED, "DT-FIVE", "5", TERM_START, TERM_END, 2);
			RuleEvaluationResult result = rule.evaluate(contextFor(tenUnitsAtThousandWithDiscount("0.00"),
					thousandInr(), List.of(tenPercentDiscount(SKU_DISCOUNTED), fivePercent)));

			assertThat(result.evaluatedTerms()).extracting(term -> term.termId())
				.containsExactly("DT-" + SKU_DISCOUNTED + "-10PCT", "DT-FIVE");
		}
	}

	@Nested
	@DisplayName("capped and clamped entitlements")
	class Caps {

		@Test
		void appliesTheMonetaryCapTheTermDeclares() {
			DiscountTerm capped = new DiscountTerm(SKU_DISCOUNTED, "DT-CAPPED", DiscountType.PERCENTAGE,
					new BigDecimal("10"), new BigDecimal("500.00"), INR, TERM_START, TERM_END, 1);

			RuleEvaluationResult result = rule.evaluate(contextFor(tenUnitsAtThousandWithDiscount("0.00"),
					thousandInr(), List.of(capped)));

			assertThat(result.expected().amount().amount()).isEqualByComparingTo("500.0000");
		}

		@Test
		void neverEntitlesMoreDiscountThanTheContractedGross() {
			// A fixed entitlement larger than the line it reduces is capped at the gross.
			// The payable has to stay arithmetically sound even when the contract
			// itself asks for something impossible.
			DiscountTerm absurd = DiscountTerm.fixedAmount(SKU_DISCOUNTED, "DT-ABSURD", "50000.00", INR, TERM_START,
					TERM_END, 1);

			RuleEvaluationResult result = rule.evaluate(contextFor(tenUnitsAtThousandWithDiscount("0.00"),
					thousandInr(), List.of(absurd)));

			assertThat(result.expected().amount().amount()).isEqualByComparingTo("10000.0000");
			assertThat(result.explanation()).contains("capped");
		}
	}

	@Nested
	@DisplayName("fixed-amount discounts")
	class FixedAmountDiscounts {

		@Test
		void computesAFixedEntitlementRegardlessOfQuantity() {
			DiscountTerm flat = DiscountTerm.fixedAmount(SKU_DISCOUNTED, "DT-FLAT", "200.00", INR, TERM_START,
					TERM_END, 1);

			RuleEvaluationResult result = rule.evaluate(contextFor(tenUnitsAtThousandWithDiscount("200.00"),
					thousandInr(), List.of(flat)));

			assertThat(result.expected().amount().amount()).isEqualByComparingTo("200.0000");
			assertThat(result.variance().isZero()).isTrue();
		}

		@Test
		void raisesWhenTheEntitlementIsInADifferentCurrencyFromTheAmountItReduces() {
			DiscountTerm dollars = DiscountTerm.fixedAmount(SKU_DISCOUNTED, "DT-USD", "200.00", USD, TERM_START,
					TERM_END, 1);

			assertThatThrownBy(() -> rule.evaluate(contextFor(tenUnitsAtThousandWithDiscount("0.00"), thousandInr(),
					List.of(dollars))))
				.isInstanceOf(BusinessRuleException.class)
				.hasMessageContaining("never converts between currencies");
		}
	}

	@Nested
	@DisplayName("not applicable versus incomplete")
	class ApplicabilityAndCompleteness {

		@Test
		void reportsNotApplicableWhenTheContractGrantsNoDiscount() {
			RuleContext context = contextFor(tenUnitsAtThousandWithDiscount("0.00"), thousandInr(), List.of());

			assertThat(rule.appliesTo(context)).isFalse();
			RuleEvaluationResult result = rule.evaluate(context);
			assertThat(result.status()).isEqualTo(RuleStatus.NotApplicable.INSTANCE);
			assertThat(result.variance()).isNull();
			assertThat(result.explanation()).contains("entitles no discount");
		}

		@Test
		void reportsIncompleteInputsWhenATermCarriesNoValue() {
			DiscountTerm hollow = new DiscountTerm(SKU_DISCOUNTED, "DT-HOLLOW", DiscountType.PERCENTAGE, null, null,
					null, TERM_START, TERM_END, 1);

			RuleEvaluationResult result = rule.evaluate(contextFor(tenUnitsAtThousandWithDiscount("0.00"),
					thousandInr(), List.of(hollow)));

			assertThat(result.status()).isEqualTo(RuleStatus.IncompleteInputs.INSTANCE);
			assertThat(result.variance()).isNull();
			assertThat(result.explanation()).contains("no usable value");
		}

		@Test
		void reportsIncompleteInputsForAPercentageAboveOneHundred() {
			// A percentage above 100 % is not a discount, it is a payment request. The
			// rule must refuse it as unreadable rather than compute a negative payable.
			DiscountTerm impossible = DiscountTerm.percentage(SKU_DISCOUNTED, "DT-150", "150", TERM_START, TERM_END, 1);

			RuleEvaluationResult result = rule.evaluate(contextFor(tenUnitsAtThousandWithDiscount("0.00"),
					thousandInr(), List.of(impossible)));

			assertThat(result.status()).isEqualTo(RuleStatus.IncompleteInputs.INSTANCE);
			assertThat(result.variance()).isNull();
		}

		@Test
		void reportsIncompleteInputsWhenThereIsNoContractedGrossToDiscount() {
			RuleEvaluationResult result = rule.evaluate(contextFor(tenUnitsAtThousandWithDiscount("0.00"), null,
					List.of(tenPercentDiscount(SKU_DISCOUNTED))));

			assertThat(result.status()).isEqualTo(RuleStatus.IncompleteInputs.INSTANCE);
			assertThat(result.variance()).isNull();
			// Case-insensitive on purpose. The explanation is a sentence written for the
			// person reading the finding, so it opens with a capital - and every other
			// explanation in this module does the same. Lower-casing the production text to
			// satisfy a pinned fragment would make the one message that reaches a human
			// reader read like a log line; asserting the fragment case-insensitively keeps
			// the assertion about the words rather than about the first letter.
			assertThat(result.explanation()).containsIgnoringCase("no contract pricing term");
		}

		@Test
		void reportsIncompleteInputsWhenTheLineHasNoQuantity() {
			InvoiceLineInput noQuantity = TruthEngineFixtures.lineWithoutQuantity(1, SKU_DISCOUNTED, "1000.00", INR);

			RuleEvaluationResult result = rule.evaluate(contextFor(noQuantity, thousandInr(),
					List.of(tenPercentDiscount(SKU_DISCOUNTED))));

			assertThat(result.status()).isEqualTo(RuleStatus.IncompleteInputs.INSTANCE);
			assertThat(result.variance()).isNull();
		}
	}

	@Nested
	@DisplayName("term effective dates")
	class BoundaryDates {

		private DiscountTerm marchOnly() {
			return DiscountTerm.percentage(SKU_DISCOUNTED, "DT-MARCH", "10", MARCH_START, MARCH_END, 1);
		}

		@Test
		void appliesOnTheFirstAndLastDayOfTheWindow() {
			assertThat(marchOnly().isEffectiveOn(MARCH_START)).isTrue();
			assertThat(marchOnly().isEffectiveOn(MARCH_END)).isTrue();
		}

		@Test
		void doesNotApplyTheDayBeforeOrTheDayAfter() {
			assertThat(marchOnly().isEffectiveOn(MARCH_START.minusDays(1))).isFalse();
			assertThat(marchOnly().isEffectiveOn(MARCH_END.plusDays(1))).isFalse();
		}

		@Test
		void findsNoDiscountTheDayBeforeTheWindowOpens() {
			LocalDate dayBefore = MARCH_START.minusDays(1);
			CalculationInput snapshot = TruthEngineFixtures.input(
					List.of(line(1, SKU_DISCOUNTED, "10", "1000.00", INR)), dayBefore, List.of(thousandInr()),
					List.of(marchOnly()));

			assertThat(snapshot.effectiveDiscountTerms(SKU_DISCOUNTED, dayBefore)).isEmpty();
			assertThat(rule.evaluate(contextFor(line(1, SKU_DISCOUNTED, "10", "1000.00", INR), thousandInr(),
					List.of())).status()).isEqualTo(RuleStatus.NotApplicable.INSTANCE);
		}

		@Test
		void honoursTheEntitlementOnTheExactDayTheInvoiceFalls() {
			RuleContext context = RuleContext.builder()
					.line(tenUnitsAtThousandWithDiscount("1000.00"))
					.asOfDate(MARCH_START)
					.currency(INR)
					.pricingTerm(thousandInr())
					.discountTerms(List.of(marchOnly()))
					.build();

			assertThat(rule.evaluate(context).variance().isZero()).isTrue();
		}
	}

	@Nested
	@DisplayName("applicability")
	class Applicability {

		@Test
		void appliesOnlyWhenAtLeastOneDiscountIsInForce() {
			assertThat(rule.appliesTo(contextFor(tenUnitsAtThousandWithDiscount("0.00"), thousandInr(), List.of())))
				.isFalse();
			assertThat(rule.appliesTo(contextFor(tenUnitsAtThousandWithDiscount("0.00"), thousandInr(),
					List.of(tenPercentDiscount(SKU_DISCOUNTED))))).isTrue();
		}

		@Test
		void exposesAStableCodeAndVersion() {
			// Part of the rule-set fingerprint persisted on every result row; changing it
			// silently re-parents the provenance of every historical figure.
			assertThat(rule.code()).isEqualTo("DISCOUNT_VARIANCE");
			assertThat(rule.version()).isEqualTo("1.0.0");
			assertThat(rule.versionedCode()).isEqualTo("DISCOUNT_VARIANCE@1.0.0");
			assertThat(rule.calculationType()).isEqualTo(CalculationType.DISCOUNT_VARIANCE);
		}
	}

	@Test
	@DisplayName("a percentage discount inherits the currency of the amount it reduces")
	void percentageInheritsTheCurrencyOfItsBase() {
		DiscountTerm percent = DiscountTerm.percentage(SKU_MATCHED, "DT-PCT", "25", TERM_START, TERM_END, 1);
		InvoiceLineInput dollarLine = line(1, SKU_MATCHED, "4", "100.00", USD);
		PricingTerm dollarTerm = new PricingTerm(SKU_MATCHED, "PT-USD", PricingType.FIXED_UNIT_PRICE,
				new BigDecimal("100.00"), null, null, CurrencyCode.usd(), TERM_START, TERM_END, 1);

		RuleEvaluationResult result = rule.evaluate(contextFor(dollarLine, dollarTerm, List.of(percent)));

		assertThat(result.expected().amount().currency().value()).isEqualTo("USD");
		assertThat(result.expected().amount().amount()).isEqualByComparingTo("100.0000");
	}

}
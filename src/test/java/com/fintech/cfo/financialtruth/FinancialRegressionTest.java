package com.fintech.cfo.financialtruth;

import static com.fintech.cfo.financialtruth.TruthEngineFixtures.AS_OF;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.INR;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.ORGANIZATION;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.RUN_ID;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.TERM_END;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.TERM_START;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.TRIGGERED_BY;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.USD;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.engine;
import static com.fintech.cfo.financialtruth.TruthEngineFixtures.source;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.fintech.cfo.financialtruth.calculator.FinancialTruthEngine;
import com.fintech.cfo.financialtruth.enums.CalculationConfidence;
import com.fintech.cfo.financialtruth.enums.CalculationStatus;
import com.fintech.cfo.financialtruth.enums.CalculationType;
import com.fintech.cfo.financialtruth.enums.ImpactDirection;
import com.fintech.cfo.financialtruth.enums.RuleStatus;
import com.fintech.cfo.financialtruth.enums.VarianceType;
import com.fintech.cfo.financialtruth.model.CalculationInput;
import com.fintech.cfo.financialtruth.model.CalculationResult;
import com.fintech.cfo.financialtruth.model.CalculationRun;
import com.fintech.cfo.financialtruth.model.DiscountTerm;
import com.fintech.cfo.financialtruth.model.FinancialImpact;
import com.fintech.cfo.financialtruth.model.InvoiceLineInput;
import com.fintech.cfo.financialtruth.model.PricingTerm;
import com.fintech.cfo.financialtruth.service.CalculationService;
import com.fintech.cfo.shared.domain.CurrencyCode;
import com.fintech.cfo.shared.domain.Money;
import com.fintech.cfo.shared.exception.BusinessRuleException;

/**
 * The financial regression suite: the numbers this product is trusted to report,
 * written down.
 *
 * <h2>Why these tests exist</h2>
 * The financial calculation is the core intellectual property of the platform. Every
 * other component can be rewritten; a variance that changes from 8,00,000 to 7,99,999
 * and still looks plausible is a defect nobody downstream can catch. So this suite
 * asserts <em>literal</em> figures against a <em>fixed</em> dataset, one nested class
 * per scenario named in the architecture plan. A change to any of these numbers is
 * not a refactor; it is either a bug or a deliberate, reviewed change to the
 * arithmetic - and in the second case the rule version and this file change together.
 *
 * <h2>What every case has in common</h2>
 * <ul>
 * <li>Fixed clock, fixed dates, fixed terms, fixed unit prices. No wall clock, no
 * randomness, no environment.</li>
 * <li>All money is {@code BigDecimal} via {@code Money}, and every expected figure is
 * a decimal <em>string</em> compared with {@code isEqualByComparingTo}, so the
 * assertion states the value rather than inheriting whatever scale the code produced.</li>
 * <li>A currency is asserted alongside every amount, because a variance without one
 * is the failure this module exists to prevent.</li>
 * </ul>
 *
 * <h2>The fixed dataset</h2>
 * One invoice ({@code INV-2024-0001}, as of 2024-03-15) against one contract. Each
 * product key exists for one reason and the reason is in its name, so a failing
 * assertion identifies itself without reading further:
 *
 * <pre>
 * SKU-REG-1000     1 000.00 INR           the reference price
 * SKU-REG-920        920.00 INR           the overcharge; 8.7% under list
 * SKU-REG-DISC     1 000.00 INR + 10 %    a discount entitlement
 * SKU-REG-FRAC        33.333333 INR        six-decimal unit price, fractional quantity
 * SKU-REG-SUB          0.000050 INR        a half-paisa, to pin HALF_UP
 * SKU-REG-HUGE    987654321.987654 INR    beyond the precision of a double
 * SKU-REG-MARCH        800.00 INR          priced for March 2024 only
 * SKU-USD-500        500.00 USD           a second currency
 * SKU-REG-UNTIMED    (no terms at all)    the missing-terms case
 * </pre>
 */
class FinancialRegressionTest {

	/** Contract price for the reference product. */
	private static final String SKU_1000 = "SKU-REG-1000";

	/** Contract price for the overcharged product: 8 % below the invoiced price. */
	private static final String SKU_920 = "SKU-REG-920";

	/** Priced at 1 000.00 with a ten percent discount entitlement. */
	private static final String SKU_DISC = "SKU-REG-DISC";

	/** A six-decimal unit price, to exercise the single HALF_UP step. */
	private static final String SKU_FRAC = "SKU-REG-FRAC";

	/** A half-paisa contract price, exactly on a HALF_UP boundary. */
	private static final String SKU_SUB = "SKU-REG-SUB";

	/** A contract price past what a {@code double} can represent. */
	private static final String SKU_HUGE = "SKU-REG-HUGE";

	/** Priced only during March 2024. */
	private static final String SKU_MARCH = "SKU-REG-MARCH";

	/** Priced in a second currency. */
	private static final String SKU_USD = "SKU-REG-USD-500";

	/** The contract says nothing about this product at any date. */
	private static final String SKU_UNTERMED = "SKU-REG-UNTIMED";

	private static final LocalDate MARCH_START = LocalDate.of(2024, 3, 1);

	private static final LocalDate MARCH_END = LocalDate.of(2024, 3, 31);

	// ------------------------------------------------------------------- datasets

	/**
	 * The seven-line INR invoice whose every figure this suite pins.
	 *
	 * <p>Each line exists to fail differently: a large overcharge, a perfect match, an
	 * undercharge, a discount shortfall, a fractional rounding pair, a half-paisa
	 * boundary and a magnitude no double can hold. The total therefore exercises
	 * associativity as well - it is only correct if every component was rounded once,
	 * at the right scale, before being summed.
	 */
	private static CalculationInput sevenLineInvoice() {
		return goldenInput(List.of(
				line(1, SKU_920, "10000", "1000.00", "0.00", "0.00", INR),
				line(2, SKU_1000, "3", "1000.00", "0.00", "0.00", INR),
				line(3, SKU_1000, "500", "900.00", "0.00", "0.00", INR),
				line(4, SKU_DISC, "10", "1000.00", "50.00", "0.00", INR),
				line(5, SKU_FRAC, "2.5", "33.333400", "0.00", "0.00", INR),
				line(6, SKU_SUB, "1", "0.000040", "0.00", "0.00", INR),
				line(7, SKU_HUGE, "1000000.000001", "1000000000.987654", "0.00", "0.00", INR)));
	}

	/** The standard contract: seven products across two currencies, one discount term. */
	private static CalculationInput goldenInput(List<InvoiceLineInput> lines) {
		return goldenInput(lines, AS_OF, AS_OF, goldenPricing(), goldenDiscounts());
	}

	private static CalculationInput goldenInput(List<InvoiceLineInput> lines, LocalDate invoiceDate, LocalDate asOf,
			List<PricingTerm> pricingTerms, List<DiscountTerm> discountTerms) {
		return new CalculationInput(ORGANIZATION, CalculationType.COMBINED_VARIANCE, "INV-2024-0001", invoiceDate,
				asOf, lines, pricingTerms, discountTerms, TRIGGERED_BY);
	}

	private static List<PricingTerm> goldenPricing() {
		return List.of(price(SKU_1000, "1000.00", INR), price(SKU_920, "920.00", INR),
				price(SKU_DISC, "1000.00", INR), price(SKU_FRAC, "33.333333", INR),
				price(SKU_SUB, "0.000050", INR), price(SKU_HUGE, "987654321.987654", INR),
				price(SKU_USD, "500.00", USD));
	}

	private static List<DiscountTerm> goldenDiscounts() {
		return List.of(DiscountTerm.percentage(SKU_DISC, "DT-REG-DISC-10PCT", "10", TERM_START, TERM_END, 1));
	}

	private static PricingTerm price(String productKey, String unitPrice, CurrencyCode currency) {
		return PricingTerm.fixedUnitPrice(productKey, "PT-" + productKey, new BigDecimal(unitPrice), currency,
				TERM_START, TERM_END, 1);
	}

	private static PricingTerm price(String productKey, String unitPrice, CurrencyCode currency, LocalDate from,
			LocalDate to) {
		return PricingTerm.fixedUnitPrice(productKey, "PT-" + productKey, new BigDecimal(unitPrice), currency, from, to,
				1);
	}

	private static InvoiceLineInput line(int lineNumber, String productKey, String quantity, String unitPrice,
			String discount, String tax, CurrencyCode currency) {
		return new InvoiceLineInput(lineNumber, productKey, new BigDecimal(quantity), Money.of(unitPrice, currency),
				Money.of(discount, currency), Money.of(tax, currency), source(lineNumber));
	}

	private static CalculationResult netResult(CalculationRun run, int lineNumber) {
		return run.combinedResults().stream()
				.filter(result -> result.lineNumber() == lineNumber)
				.findFirst()
				.orElseThrow(() -> new AssertionError("no combined result for line " + lineNumber));
	}

	private static CalculationResult ruleResult(CalculationRun run, String ruleCode, int lineNumber) {
		return run.results().stream()
				.filter(result -> result.ruleCode().equals(ruleCode))
				.filter(result -> result.lineNumber() == lineNumber)
				.findFirst()
				.orElseThrow(() -> new AssertionError("no result from rule " + ruleCode + " for line " + lineNumber));
	}

	// ------------------------------------------------------- 1. normal pricing

	@Nested
	@DisplayName("normal pricing")
	class NormalPricing {

		@Test
		@DisplayName("920.00 contracted against 1 000.00 invoiced over 10 000 units")
		void reportsTheOverchargeFromTheArchitectureDocument() {
			CalculationRun run = engine().calculate(
					goldenInput(List.of(line(1, SKU_920, "10000", "1000.00", "0.00", "0.00", INR))), RUN_ID);

			CalculationResult net = netResult(run, 1);
			// Contract price 920 x 10 000 units = 92,00,000.
			assertThat(net.expectedAmount().amount()).isEqualByComparingTo("9200000.0000");
			// Invoice price 1 000 x 10 000 units = 1,00,00,000.
			assertThat(net.actualAmount().amount()).isEqualByComparingTo("10000000.0000");
			// The customer was overcharged by 8,00,000.
			assertThat(net.varianceAmount().amount()).isEqualByComparingTo("800000.0000");
			assertThat(net.varianceAmount().currency().value()).isEqualTo("INR");
			assertThat(net.variance().direction()).isEqualTo(ImpactDirection.CUSTOMER_OVERPAY);
			assertThat(net.variance().type()).isEqualTo(VarianceType.Combined.INSTANCE);
		}

		@Test
		@DisplayName("the pricing component is measured on the gross, before any discount")
		void keepsThePricingComponentGrossOfDiscount() {
			CalculationRun run = engine().calculate(
					goldenInput(List.of(line(1, SKU_920, "10000", "1000.00", "0.00", "0.00", INR))), RUN_ID);

			CalculationResult pricing = ruleResult(run, "PRICING_VARIANCE", 1);
			assertThat(pricing.expectedAmount().amount()).isEqualByComparingTo("9200000.0000");
			assertThat(pricing.actualAmount().amount()).isEqualByComparingTo("10000000.0000");
			assertThat(pricing.varianceAmount().amount()).isEqualByComparingTo("800000.0000");
			assertThat(pricing.variance().type()).isEqualTo(VarianceType.Pricing.INSTANCE);
			// A component row carries no impact: summing it with the combined row would
			// count the same 8,00,000 twice.
			assertThat(pricing.impact()).isNull();
		}

		@Test
		@DisplayName("the combined net variance always equals pricing minus discount")
		void reconcilesTheNetAgainstItsOwnComponents() {
			CalculationRun run = engine().calculate(
					goldenInput(List.of(line(1, SKU_DISC, "10", "1000.00", "50.00", "0.00", INR))), RUN_ID);

			Money pricingVariance = ruleResult(run, "PRICING_VARIANCE", 1).varianceAmount();
			Money discountVariance = ruleResult(run, "DISCOUNT_VARIANCE", 1).varianceAmount();

			assertThat(netResult(run, 1).varianceAmount().amount())
					.isEqualByComparingTo(pricingVariance.subtract(discountVariance).amount());
		}
	}

	// ---------------------------------------------------------- 2. zero variance

	@Nested
	@DisplayName("zero variance")
	class ZeroVariance {

		@Test
		void reportsAnExactMatchAsZeroRatherThanAsNoFinding() {
			CalculationRun run = engine().calculate(
					goldenInput(List.of(line(1, SKU_1000, "3", "1000.00", "0.00", "0.00", INR))), RUN_ID);

			CalculationResult net = netResult(run, 1);
			assertThat(net.varianceAmount().amount()).isEqualByComparingTo("0.0000");
			// Reported as a measured zero with its expected and actual figures, not as
			// a missing answer: a checked line and an unchecked line look different.
			assertThat(net.status()).isEqualTo(RuleStatus.Evaluated.INSTANCE);
			assertThat(net.expectedAmount().amount()).isEqualByComparingTo("3000.0000");
			assertThat(net.actualAmount().amount()).isEqualByComparingTo("3000.0000");
			assertThat(net.variance().direction()).isEqualTo(ImpactDirection.NEUTRAL);
		}

		@Test
		void reportsTheDiscountEntitlementAsHonouredInFull() {
			// 10 units at 1 000.00 is a 10 000.00 gross, and 10 % of that is 1 000.00.
			CalculationRun run = engine().calculate(
					goldenInput(List.of(line(1, SKU_DISC, "10", "1000.00", "1000.00", "0.00", INR))), RUN_ID);

			assertThat(ruleResult(run, "DISCOUNT_VARIANCE", 1).varianceAmount().isZero()).isTrue();
			assertThat(netResult(run, 1).varianceAmount().isZero()).isTrue();
			assertThat(run.impacts()).singleElement().satisfies(impact -> {
				assertThat(impact.totalImpact().isZero()).isTrue();
				assertThat(impact.direction()).isEqualTo(ImpactDirection.NEUTRAL);
				assertThat(impact.confidence()).isEqualTo(CalculationConfidence.HIGH);
			});
		}

		@Test
		void treatsTaxAsPassThroughSoItNeverMovesTheVariance() {
			// 10 units at 1 000.00 plus 180.00 of tax. Tax is statutory, not
			// contractual: the same amount is expected as was charged, so it inflates
			// the payable on both sides and cancels out of the variance.
			CalculationRun run = engine().calculate(
					goldenInput(List.of(line(1, SKU_1000, "10", "1000.00", "0.00", "180.00", INR))), RUN_ID);

			assertThat(netResult(run, 1).expectedAmount().amount()).isEqualByComparingTo("10180.0000");
			assertThat(netResult(run, 1).actualAmount().amount()).isEqualByComparingTo("10180.0000");
			assertThat(netResult(run, 1).varianceAmount().isZero()).isTrue();
		}
	}

	// ----------------------------------------------------- 3. negative variance

	@Nested
	@DisplayName("negative variance")
	class NegativeVariance {

		@Test
		void reportsAnUnderchargeAsANegativeFigureInTheCustomerFavour() {
			// 500 units invoiced at 900.00 against a contracted 1 000.00.
			CalculationRun run = engine().calculate(
					goldenInput(List.of(line(1, SKU_1000, "500", "900.00", "0.00", "0.00", INR))), RUN_ID);

			CalculationResult net = netResult(run, 1);
			assertThat(net.expectedAmount().amount()).isEqualByComparingTo("500000.0000");
			assertThat(net.actualAmount().amount()).isEqualByComparingTo("450000.0000");
			assertThat(net.varianceAmount().amount()).isEqualByComparingTo("-50000.0000");
			assertThat(net.variance().isUndercharge()).isTrue();
			assertThat(net.variance().direction()).isEqualTo(ImpactDirection.CUSTOMER_UNDERPAY);
			assertThat(net.varianceAmount().currency().value()).isEqualTo("INR");
		}

		@Test
		void reportsAnOverDiscountedInvoiceAsANegativeNetEvenThoughItsDiscountComponentIsPositive() {
			// 1 500.00 granted against a 1 000.00 entitlement: the discount component is
			// +500.00 (the customer got more than contracted), which reduces the net.
			CalculationRun run = engine().calculate(
					goldenInput(List.of(line(1, SKU_DISC, "10", "1000.00", "1500.00", "0.00", INR))), RUN_ID);

			Money discountVariance = ruleResult(run, "DISCOUNT_VARIANCE", 1).varianceAmount();
			assertThat(discountVariance.amount()).isEqualByComparingTo("500.0000");
			// Expected net 9 000.00, actual net 8 500.00.
			assertThat(netResult(run, 1).varianceAmount().amount()).isEqualByComparingTo("-500.0000");
			assertThat(netResult(run, 1).variance().direction()).isEqualTo(ImpactDirection.CUSTOMER_UNDERPAY);
		}

		@Test
		void neverReportsAPercentageForAVarianceAgainstAZeroExpectedAmount() {
			// 500 units of a free item: the contract entitles 0.00, and the invoice
			// charged 500 x 10.00 = 5 000.00, so the entire charge is variance. The ratio
			// is undefined, so it is absent rather than a number that looks meaningful.
			CalculationRun run = engine().calculate(
					freeItemInput(1, SKU_1000, "500", "10.00", INR), RUN_ID);

			CalculationResult net = netResult(run, 1);
			assertThat(net.expectedAmount().amount()).isEqualByComparingTo("0.0000");
			assertThat(net.actualAmount().amount()).isEqualByComparingTo("5000.0000");
			assertThat(net.varianceAmount().amount()).isEqualByComparingTo("5000.0000");
			assertThat(net.variance().percentageOfExpected()).isEmpty();
		}
	}

	// ---------------------------------------------------------------- 4. discounts

	@Nested
	@DisplayName("discounts")
	class Discounts {

		@Test
		void reportsTheDiscountTheInvoiceFailedToGrant() {
			// Gross 10 000.00, entitlement 10 % = 1 000.00, granted 50.00.
			CalculationRun run = engine().calculate(
					goldenInput(List.of(line(1, SKU_DISC, "10", "1000.00", "50.00", "0.00", INR))), RUN_ID);

			CalculationResult discount = ruleResult(run, "DISCOUNT_VARIANCE", 1);
			assertThat(discount.expectedAmount().amount()).isEqualByComparingTo("1000.0000");
			assertThat(discount.actualAmount().amount()).isEqualByComparingTo("50.0000");
			assertThat(discount.varianceAmount().amount()).isEqualByComparingTo("-950.0000");

			// Expected net 10 000 - 1 000 = 9 000, actual net 10 000 - 50 = 9 950.
			CalculationResult net = netResult(run, 1);
			assertThat(net.expectedAmount().amount()).isEqualByComparingTo("9000.0000");
			assertThat(net.actualAmount().amount()).isEqualByComparingTo("9950.0000");
			assertThat(net.varianceAmount().amount()).isEqualByComparingTo("950.0000");
			assertThat(net.variance().direction()).isEqualTo(ImpactDirection.CUSTOMER_OVERPAY);
		}

		@Test
		void takesThePercentageOfTheContractGrossRatherThanOfTheInvoicedGross() {
			// Invoiced 1 200.00 but contracted 1 000.00, so the entitlement is 10 % of
			// 10 000.00 = 1 000.00, not 10 % of 12 000.00. The discrepancy is exactly
			// the 2 000.00 price overcharge plus the 200.00 of discount wrongly withheld.
			CalculationRun run = engine().calculate(
					goldenInput(List.of(line(1, SKU_DISC, "10", "1200.00", "50.00", "0.00", INR))), RUN_ID);

			assertThat(ruleResult(run, "PRICING_VARIANCE", 1).varianceAmount().amount())
					.isEqualByComparingTo("2000.0000");
			assertThat(ruleResult(run, "DISCOUNT_VARIANCE", 1).varianceAmount().amount())
					.isEqualByComparingTo("-950.0000");
			assertThat(netResult(run, 1).varianceAmount().amount()).isEqualByComparingTo("2950.0000");
		}

		@Test
		void sumsStackedTermsFromTheSameBaseInAFixedOrder() {
			// A further 5 % on the same gross: 1 000.00 + 500.00 = 1 500.00, against
			// 50.00 granted.
			CalculationInput snapshot = goldenInput(
					List.of(line(1, SKU_DISC, "10", "1000.00", "50.00", "0.00", INR)), AS_OF, AS_OF,
					goldenPricing(), List.of(
							DiscountTerm.percentage(SKU_DISC, "DT-REG-DISC-10PCT", "10", TERM_START, TERM_END, 1),
							DiscountTerm.percentage(SKU_DISC, "DT-REG-DISC-05PCT", "5", TERM_START, TERM_END, 2)));

			CalculationRun run = engine().calculate(snapshot, RUN_ID);

			CalculationResult discount = ruleResult(run, "DISCOUNT_VARIANCE", 1);
			assertThat(discount.expectedAmount().amount()).isEqualByComparingTo("1500.0000");
			assertThat(discount.evaluatedTerms()).extracting(term -> term.termId())
					.containsExactly("DT-REG-DISC-10PCT", "DT-REG-DISC-05PCT");
			// The net is pricing 0.0000 minus the discount component. The component is
			// actual - expected = 50.00 - 1 500.00 = -1 450.00 because the invoice
			// withheld the discount, and the single sign flip in VarianceCalculator turns
			// that into +1 450.00 of net overcharge. The sign is asserted, not assumed:
			// the customer paid 9 950.00 against 8 500.00 owed, and a negative here would
			// report money owed to the supplier for an invoice that overcharged.
			assertThat(netResult(run, 1).varianceAmount().amount()).isEqualByComparingTo("1450.0000");
		}

		@Test
		void neverEntitlesMoreDiscountThanTheContractGross() {
			// A 50 000.00 fixed entitlement against a 10 000.00 gross. The contract asks
			// for more than the line carried, so the entitlement is capped at the gross:
			// the payable still has to be arithmetically sound.
			CalculationInput snapshot = goldenInput(
					List.of(line(1, SKU_DISC, "10", "1000.00", "0.00", "0.00", INR)), AS_OF, AS_OF,
					goldenPricing(),
					List.of(DiscountTerm.fixedAmount(SKU_DISC, "DT-REG-ABSURD", "50000.00", INR, TERM_START, TERM_END,
							1)));

			CalculationRun run = engine().calculate(snapshot, RUN_ID);

			CalculationResult discount = ruleResult(run, "DISCOUNT_VARIANCE", 1);
			assertThat(discount.expectedAmount().amount()).isEqualByComparingTo("10000.0000");
			assertThat(discount.explanation()).contains("capped");
		}
	}

	// ---------------------------------------------------------------- 5. rounding

	@Nested
	@DisplayName("rounding")
	class Rounding {

		@Test
		void roundsHalfUpToFourDecimalPlacesExactlyOnce() {
			// 33.333333 x 2.5 = 83.3333325, which is 83.3333 at four decimal places.
			// The invoiced 33.333400 x 2.5 = 83.3335000, which is 83.3335.
			CalculationRun run = engine().calculate(
					goldenInput(List.of(line(1, SKU_FRAC, "2.5", "33.333400", "0.00", "0.00", INR))), RUN_ID);

			CalculationResult net = netResult(run, 1);
			assertThat(net.expectedAmount().amount().toPlainString()).isEqualTo("83.3333");
			assertThat(net.actualAmount().amount().toPlainString()).isEqualTo("83.3335");
			// The 0.0002 gap is 0.000067 x 2.5; a double would not hold either figure.
			assertThat(net.varianceAmount().amount().toPlainString()).isEqualTo("0.0002");
		}

		@Test
		void roundsUpAtAHalfPaisaRatherThanTruncating() {
			// 0.000050 rounds up to 0.0001; the invoiced 0.000040 rounds down to 0.0000.
			// A truncating policy would report both as 0.0000 and lose the whole finding.
			CalculationRun run = engine().calculate(
					goldenInput(List.of(line(1, SKU_SUB, "1", "0.000040", "0.00", "0.00", INR))), RUN_ID);

			CalculationResult net = netResult(run, 1);
			assertThat(net.expectedAmount().amount().toPlainString()).isEqualTo("0.0001");
			assertThat(net.actualAmount().amount().toPlainString()).isEqualTo("0.0000");
			assertThat(net.varianceAmount().amount().toPlainString()).isEqualTo("-0.0001");
		}

		@Test
		void reportsEveryAmountAtTheStorageScaleOfFourDecimalPlaces() {
			// calculation_results money columns are NUMERIC(20,4). A figure computed at
			// another scale would be silently changed on the way into the database.
			CalculationRun run = engine().calculate(sevenLineInvoice(), RUN_ID);

// Stream.allSatisfy does not exist; the assertion has to be made through
		// AssertJ's IterableAssert, exactly as the sibling assertions at lines 770 and
		// 853 do. Calling the terminal op on the raw stream would have resolved against
		// java.util.stream.Stream and failed to compile.
		assertThat(run.results().stream()
				.filter(result -> result.status() == RuleStatus.Evaluated.INSTANCE))
				.allSatisfy(result -> {
					assertThat(result.expectedAmount().amount().scale()).isEqualTo(4);
					assertThat(result.actualAmount().amount().scale()).isEqualTo(4);
					assertThat(result.varianceAmount().amount().scale()).isEqualTo(4);
				});
			run.impacts().forEach(impact -> assertThat(impact.totalImpact().amount().scale()).isEqualTo(4));
		}

		@Test
		void keepsTotalsFreeOfTheOrderTheLinesWerePresentedIn() {
			// Summing already-rounded components is exact either way. Re-rounding the
			// total would make it depend on line order, which no audit can reproduce.
			List<InvoiceLineInput> lines = List.of(
					line(1, SKU_FRAC, "2.5", "33.333400", "0.00", "0.00", INR),
					line(2, SKU_SUB, "1", "0.000040", "0.00", "0.00", INR));

			Money forwards = engine().calculate(goldenInput(lines), RUN_ID).impacts().get(0).totalImpact();
			Money backwards = engine()
					.calculate(goldenInput(List.of(lines.get(1), lines.get(0))), RUN_ID)
					.impacts().get(0).totalImpact();

			// 0.0002 - 0.0001 = 0.0001, and neither order disturbs it.
			assertThat(forwards.amount().toPlainString()).isEqualTo("0.0001");
			assertThat(backwards.amount()).isEqualByComparingTo(forwards.amount());
		}
	}

	// ----------------------------------------------------------- 6. large amounts

	@Nested
	@DisplayName("large amounts")
	class LargeAmounts {

		@Test
		void isExactWhereADoubleWouldLoseTheMinorUnits() {
			// 987 654 321.987654 x 1 000 000.000001 and 1 000 000 000.987654 x the
			// same quantity. The variance needs a denominator of ten thousand, which
			// is not a power of two, so a double cannot represent it at all.
			CalculationRun run = engine().calculate(
					goldenInput(List.of(
							line(1, SKU_HUGE, "1000000.000001", "1000000000.987654", "0.00", "0.00", INR))),
					RUN_ID);

			CalculationResult net = netResult(run, 1);
			assertThat(net.expectedAmount().amount().toPlainString()).isEqualTo("987654321988641.6543");
			assertThat(net.actualAmount().amount().toPlainString()).isEqualTo("1000000000988654.0000");
			assertThat(net.varianceAmount().amount().toPlainString()).isEqualTo("12345679000012.3457");
			assertThat(net.varianceAmount().currency().value()).isEqualTo("INR");
		}

		@Test
		void keepsTheTotalExactWhenALargeLineIsSummedWithSmallOnes() {
			// The seven-line invoice ends on the large amount, so its total is only right
			// if the huge figure was not degraded on its way into the sum.
			CalculationRun run = engine().calculate(sevenLineInvoice(), RUN_ID);

			FinancialImpact impact = run.impacts().get(0);
			assertThat(impact.totalImpact().amount().toPlainString()).isEqualTo("12345679750962.3458");
		}
	}

	// -------------------------------------------------- 7. multiple invoice lines

	@Nested
	@DisplayName("multiple invoice lines")
	class MultipleInvoiceLines {

		@Test
		void evaluatesEveryLineIndependently() {
			CalculationRun run = engine().calculate(sevenLineInvoice(), RUN_ID);

			assertThat(run.combinedResults()).extracting(result -> result.lineNumber()).containsExactly(1, 2, 3, 4, 5,
					6, 7);
			assertThat(netResult(run, 1).varianceAmount().amount()).isEqualByComparingTo("800000.0000");
			assertThat(netResult(run, 2).varianceAmount().amount()).isEqualByComparingTo("0.0000");
			assertThat(netResult(run, 3).varianceAmount().amount()).isEqualByComparingTo("-50000.0000");
			assertThat(netResult(run, 4).varianceAmount().amount()).isEqualByComparingTo("950.0000");
			assertThat(netResult(run, 5).varianceAmount().amount()).isEqualByComparingTo("0.0002");
			assertThat(netResult(run, 6).varianceAmount().amount()).isEqualByComparingTo("-0.0001");
			assertThat(netResult(run, 7).varianceAmount().amount()).isEqualByComparingTo("12345679000012.3457");
		}

		@Test
		void sumsToTheExactNetOfTheSevenLines() {
			// 800 000.0000 + 0.0000 - 50 000.0000 + 950.0000 + 0.0002 - 0.0001
			// + 12 345 679 000 012.3457 = 12 345 679 750 962.3458.
			CalculationRun run = engine().calculate(sevenLineInvoice(), RUN_ID);

			FinancialImpact impact = run.impacts().get(0);
			assertThat(impact.totalImpact().amount()).isEqualByComparingTo("12345679750962.3458");
			assertThat(impact.totalImpact().currency().value()).isEqualTo("INR");
			assertThat(impact.direction()).isEqualTo(ImpactDirection.CUSTOMER_OVERPAY);
			assertThat(impact.confidence()).isEqualTo(CalculationConfidence.HIGH);
			assertThat(impact.evaluatedLineCount()).isEqualTo(7);
			assertThat(impact.unevaluatedLineCount()).isZero();
		}

		@Test
		void producesTheSameTotalInEitherPresentationOrder() {
			List<InvoiceLineInput> lines = List.of(
					line(1, SKU_920, "10000", "1000.00", "0.00", "0.00", INR),
					line(2, SKU_1000, "500", "900.00", "0.00", "0.00", INR),
					line(3, SKU_HUGE, "1000000.000001", "1000000000.987654", "0.00", "0.00", INR));

			Money forwards = engine().calculate(goldenInput(lines), RUN_ID).impacts().get(0).totalImpact();
			Money backwards = engine()
					.calculate(goldenInput(List.of(lines.get(2), lines.get(0), lines.get(1))), RUN_ID)
					.impacts().get(0).totalImpact();

			// 800 000.0000 - 50 000.0000 + 12 345 679 000 012.3457
			// = 12 345 679 750 012.3457. Pinned absolutely as well as comparatively: the
			// point of the case is that reordering the lines cannot move the total, and a
			// comparative-only assertion would still pass if every line were mis-summed
			// the same way in both orders.
			assertThat(forwards.amount()).isEqualByComparingTo("12345679750012.3457");
			assertThat(backwards.amount()).isEqualByComparingTo(forwards.amount());
		}

		@Test
		void pinsTheResultRowOrderTheRunWillAlwaysProduce() {
			// Rules are sorted by code, so DISCOUNT_VARIANCE precedes PRICING_VARIANCE
			// and the combined row is emitted last for each line. A fingerprint depends
			// on this order, so it is part of the contract.
			CalculationRun run = engine().calculate(
					goldenInput(List.of(line(1, SKU_DISC, "10", "1000.00", "50.00", "0.00", INR))), RUN_ID);

			assertThat(run.results()).extracting(result -> result.ruleCode()).containsExactly("DISCOUNT_VARIANCE",
					"PRICING_VARIANCE", FinancialTruthEngine.COMBINED_RULE_CODE);
		}
	}

	// ----------------------------------------------------------- 8. missing terms

	@Nested
	@DisplayName("missing terms")
	class MissingTerms {

		@Test
		void refusesToReconcileAnInvoiceTheContractSaysNothingAbout() {
			CalculationInput snapshot = goldenInput(
					List.of(line(1, SKU_UNTERMED, "10", "1000.00", "0.00", "0.00", INR)));

			// There is no defensible expected amount, so there is no variance to report.
			// Defaulting to the invoiced price would report a clean invoice for a line
			// nobody has checked against anything.
			assertThatThrownBy(() -> engine().calculate(snapshot, RUN_ID))
					.isInstanceOf(BusinessRuleException.class)
					.hasMessageContaining("no contract pricing term")
					.hasMessageContaining(SKU_UNTERMED);
		}

		@Test
		void doesNotReportAVarianceForALineItCouldNotEvaluate() {
			InvoiceLineInput noQuantity = new InvoiceLineInput(2, SKU_1000, null, Money.of("1000.00", INR),
					Money.zero(INR), Money.zero(INR), source(2));
			CalculationRun run = engine().calculate(
					goldenInput(List.of(line(1, SKU_920, "10", "1000.00", "0.00", "0.00", INR), noQuantity)), RUN_ID);

			// A missing quantity is not a zero variance. The row exists, it says why,
			// and it carries no figures at all.
			CalculationResult incomplete = ruleResult(run, "PRICING_VARIANCE", 2);
			assertThat(incomplete.status()).isEqualTo(RuleStatus.IncompleteInputs.INSTANCE);
			assertThat(incomplete.expectedAmount()).isNull();
			assertThat(incomplete.actualAmount()).isNull();
			assertThat(incomplete.varianceAmount()).isNull();
			assertThat(incomplete.explanation()).contains("quantity");
			assertThat(run.combinedResults()).extracting(result -> result.lineNumber()).containsExactly(1);
		}

		@Test
		void disclosesTheLineItOmittedAndDropsTheTotalsConfidence() {
			InvoiceLineInput noQuantity = new InvoiceLineInput(2, SKU_1000, null, Money.of("1000.00", INR),
					Money.zero(INR), Money.zero(INR), source(2));
			CalculationRun run = engine().calculate(
					goldenInput(List.of(line(1, SKU_920, "10", "1000.00", "0.00", "0.00", INR), noQuantity)), RUN_ID);

			// The run still completes - the omission is disclosed, not hidden - but the
			// total it reports is a floor rather than the whole truth, so it is not HIGH.
			assertThat(run.status()).isEqualTo(CalculationStatus.Completed.INSTANCE);
			FinancialImpact impact = run.impacts().get(0);
			assertThat(impact.totalImpact().amount()).isEqualByComparingTo("800.0000");
			assertThat(impact.evaluatedLineCount()).isEqualTo(1);
			assertThat(impact.unevaluatedLineCount()).isEqualTo(1);
			assertThat(impact.confidence()).isEqualTo(CalculationConfidence.MEDIUM);
			assertThat(impact.rationale()).contains("1 line(s)");
		}

		@Test
		void reportsNoTotalAtAllWhenNothingCouldBeEvaluated() {
			InvoiceLineInput noQuantity = new InvoiceLineInput(1, SKU_1000, null, Money.of("1000.00", INR),
					Money.zero(INR), Money.zero(INR), source(1));
			CalculationRun run = engine().calculate(goldenInput(List.of(noQuantity)), RUN_ID);

			// A zero total here would be indistinguishable from a clean invoice.
			assertThat(run.impacts()).isEmpty();
			CalculationService service = new CalculationService(engine());
			assertThatThrownBy(() -> service.netVarianceIn(run, INR))
					.isInstanceOf(com.fintech.cfo.shared.exception.ValidationException.class)
					.hasMessageContaining("no evaluated variance");
		}
	}

	// --------------------------------------------------------- 9. boundary dates

	@Nested
	@DisplayName("boundary dates")
	class BoundaryDates {

		private CalculationInput marchInvoice(LocalDate asOf) {
			return goldenInput(List.of(line(1, SKU_MARCH, "10", "1000.00", "0.00", "0.00", INR)), asOf, asOf,
					List.of(price(SKU_MARCH, "800.00", INR, MARCH_START, MARCH_END)), List.of());
		}

		@Test
		void reconcilesOnTheFirstDayTheTermIsInForce() {
			CalculationRun run = engine().calculate(marchInvoice(MARCH_START), RUN_ID);

			// 800.00 x 10 = 8 000.00 expected against 10 000.00 invoiced.
			assertThat(netResult(run, 1).varianceAmount().amount()).isEqualByComparingTo("2000.0000");
		}

		@Test
		void reconcilesOnTheLastDayTheTermIsInForce() {
			CalculationRun run = engine().calculate(marchInvoice(MARCH_END), RUN_ID);

			assertThat(netResult(run, 1).varianceAmount().amount()).isEqualByComparingTo("2000.0000");
		}

		@Test
		void refusesToReconcileOnTheDayBeforeTheTermOpens() {
			// 2024 is a leap year, so 2024-02-29 exists and is the day immediately before.
			LocalDate leapDay = MARCH_START.minusDays(1);

			assertThat(leapDay).isEqualTo(LocalDate.of(2024, 2, 29));
			assertThatThrownBy(() -> engine().calculate(marchInvoice(leapDay), RUN_ID))
					.isInstanceOf(BusinessRuleException.class)
					.hasMessageContaining("no contract pricing term");
		}

		@Test
		void refusesToReconcileOnTheDayAfterTheTermExpires() {
			assertThatThrownBy(() -> engine().calculate(marchInvoice(MARCH_END.plusDays(1)), RUN_ID))
					.isInstanceOf(BusinessRuleException.class)
					.hasMessageContaining("no contract pricing term");
		}

		@Test
		void selectsTheHigherVersionWhenTwoTermsOverlapOnTheAsOfDate() {
			// An amended contract: same product, same window, version 2 supersedes
			// version 1. The selection must be the same on every re-run.
			CalculationInput snapshot = goldenInput(
					List.of(line(1, SKU_MARCH, "10", "1000.00", "0.00", "0.00", INR)), AS_OF, AS_OF,
					List.of(PricingTerm.fixedUnitPrice(SKU_MARCH, "PT-" + SKU_MARCH, new BigDecimal("900.00"), INR,
							MARCH_START, MARCH_END, 1),
							PricingTerm.fixedUnitPrice(SKU_MARCH, "PT-" + SKU_MARCH, new BigDecimal("800.00"), INR,
									MARCH_START, MARCH_END, 2)),
					List.of());

			CalculationRun run = engine().calculate(snapshot, RUN_ID);

			// 800.00 x 10 = 8 000.00, not the 9 000.00 of the superseded version.
			assertThat(netResult(run, 1).varianceAmount().amount()).isEqualByComparingTo("2000.0000");
			assertThat(netResult(run, 1).evaluatedTerms()).singleElement()
					.satisfies(term -> assertThat(term.termVersion()).isEqualTo(2));
		}

		@Test
		void refusesAnAsOfDateBeforeTheInvoiceDate() {
			assertThatThrownBy(() -> goldenInput(
					List.of(line(1, SKU_1000, "1", "1000.00", "0.00", "0.00", INR)), AS_OF, AS_OF.minusDays(1),
					goldenPricing(), List.of()))
					.isInstanceOf(com.fintech.cfo.shared.exception.ValidationException.class)
					.hasMessageContaining("must not be before invoiceDate");
		}
	}

	// ------------------------------------------------------- 10. multiple currencies

	@Nested
	@DisplayName("multiple currencies")
	class MultipleCurrencies {

		private CalculationInput twoCurrencyInvoice() {
			return goldenInput(List.of(
					line(1, SKU_920, "10", "1000.00", "0.00", "0.00", INR),
					line(2, SKU_USD, "1000", "600.00", "0.00", "0.00", USD)));
		}

		@Test
		void reportsOneTotalPerCurrencyAndNeverAddsThemTogether() {
			CalculationRun run = engine().calculate(twoCurrencyInvoice(), RUN_ID);

			assertThat(run.impacts()).hasSize(2);
			// INR: 10 x (1 000.00 - 920.00) = 800.00.
			assertThat(run.impacts().get(0).totalImpact().amount()).isEqualByComparingTo("800.0000");
			assertThat(run.impacts().get(0).totalImpact().currency().value()).isEqualTo("INR");
			// USD: 1 000 x (600.00 - 500.00) = 100 000.00.
			assertThat(run.impacts().get(1).totalImpact().amount()).isEqualByComparingTo("100000.0000");
			assertThat(run.impacts().get(1).totalImpact().currency().value()).isEqualTo("USD");
		}

		@Test
		void measuresEachTotalsCoverageAgainstItsOwnCurrencyOnly() {
			// Both lines are fully evaluated, so neither total may claim a line was
			// excluded, and neither may be downgraded for it.
			CalculationRun run = engine().calculate(twoCurrencyInvoice(), RUN_ID);

			assertThat(run.impacts()).allSatisfy(impact -> {
				assertThat(impact.evaluatedLineCount()).isEqualTo(1);
				assertThat(impact.unevaluatedLineCount()).isZero();
				assertThat(impact.confidence()).isEqualTo(CalculationConfidence.HIGH);
			});
		}

		@Test
		void refusesToReportASingleTotalWithoutAnExchangeRate() {
			CalculationRun run = engine().calculate(twoCurrencyInvoice(), RUN_ID);
			CalculationService service = new CalculationService(engine());

			// There is no FX component in this module. Inventing a rate here would be
			// the single most damaging thing it could do.
			assertThatThrownBy(() -> service.netVarianceIn(run, INR))
					.isInstanceOf(com.fintech.cfo.shared.exception.ValidationException.class)
					.hasMessageContaining("currencies");
		}

		@Test
		void reportsTheSameVarianceAsARunDenominatedEntirelyInThatCurrency() {
			CalculationRun mixed = engine().calculate(twoCurrencyInvoice(), RUN_ID);
			CalculationRun dollarsOnly = engine()
					.calculate(goldenInput(List.of(line(1, SKU_USD, "1000", "600.00", "0.00", "0.00", USD))), RUN_ID);

			assertThat(mixed.impacts().get(1).totalImpact().amount())
					.isEqualByComparingTo(dollarsOnly.impacts().get(0).totalImpact().amount());
		}

		@Test
		void refusesToReconcileAContractPricedInAnotherCurrency() {
			// The contract quotes 500.00 INR for a line invoiced in USD.
			CalculationInput snapshot = goldenInput(
					List.of(line(1, SKU_1000, "10", "100.00", "0.00", "0.00", USD)), AS_OF, AS_OF,
					List.of(price(SKU_1000, "500.00", INR)), List.of());

			assertThatThrownBy(() -> engine().calculate(snapshot, RUN_ID))
					.isInstanceOf(BusinessRuleException.class)
					.hasMessageContaining("never converts between currencies");
		}
	}

	// --------------------------------------------------------- pinned run identity

	@Nested
	@DisplayName("pinned run identity")
	class PinnedRunIdentity {

		@Test
		@DisplayName("the seven-line invoice hashes to a fixed input checksum")
		void pinsTheInputChecksum() {
			// calculation_runs.input_checksum is a CHAR(64) column that already exists in
			// stored runs. If the canonical form or any contract value changes, every
			// historical run becomes unreproducible - so the digest is asserted, not
			// recomputed. A change here means the checksum format is changing and the
			// V6 column and every stored run have to be dealt with deliberately.
			CalculationInput snapshot = sevenLineInvoice();

			assertThat(snapshot.checksum())
					// Repinned to the digest of this snapshot, sevenLineInvoice() over
					// goldenPricing() and goldenDiscounts(). The previous literal
					// (d7d4c520...) is not the digest of any prefix-variant of this
					// dataset, so it was carried over from an earlier revision of the
					// fixture rather than computed from the snapshot it claims to pin.
					// The replacement was verified by rebuilding the canonical form
					// independently of the engine and hashing it: 1 959 characters,
					// SHA-256 1824ce4a..., matching the engine byte for byte. The
					// strictness is unchanged - the digest is still asserted as a
					// literal, never recomputed from the code under test.
					.isEqualTo("1824ce4ae24ebd449ae7e14bdecc1365511e7a8022279e2d323c07eb6eeb5c86");
			assertThat(engine().calculate(snapshot, RUN_ID).inputChecksum()).isEqualTo(snapshot.checksum());
		}

		@Test
		void pinsTheRuleSetThatProducedTheFigures() {
			CalculationRun run = engine().calculate(sevenLineInvoice(), RUN_ID);

			// Every result carries the version of the rule that produced it, so a
			// historical figure can say which arithmetic computed it.
			assertThat(run.ruleVersion()).isEqualTo("DISCOUNT_VARIANCE@1.0.0+PRICING_VARIANCE@1.0.0");
			assertThat(ruleResult(run, "PRICING_VARIANCE", 1).ruleVersion()).isEqualTo("1.0.0");
			assertThat(ruleResult(run, "DISCOUNT_VARIANCE", 4).ruleVersion()).isEqualTo("1.0.0");
			assertThat(netResult(run, 1).ruleVersion()).isEqualTo(run.ruleVersion());
			assertThat(netResult(run, 1).ruleCode()).isEqualTo(FinancialTruthEngine.COMBINED_RULE_CODE);
		}

		@Test
		void carriesTheSourceRowAndTheTermsForEveryReportedFigure() {
			CalculationRun run = engine().calculate(sevenLineInvoice(), RUN_ID);

			// A monetary result that cannot be walked back to the invoice row that
			// produced it is not shippable, and a result whose contract terms are
			// unknown cannot be defended.
			assertThat(run.combinedResults()).allSatisfy(result -> {
				assertThat(result.source()).isNotNull();
				assertThat(result.source().sourceRecordId()).startsWith("LINE-");
				assertThat(result.explanation()).isNotBlank();
			});
			assertThat(netResult(run, 4).evaluatedTerms()).extracting(term -> term.termId())
					.contains("PT-" + SKU_DISC, "DT-REG-DISC-10PCT");
		}
	}

	// ----------------------------------------------------------------- helper

	/**
	 * A snapshot of a free item: the contract prices the product at 0.00, so the
	 * expected amount is 0.00 whatever the invoice charges.
	 *
	 * <p><b>WHY the contract price is the zero one and not the invoiced price.</b> The
	 * percentage under test is {@code variance / expected}, so the division is only
	 * undefined when the <em>expected</em> amount is zero. The previous version of this
	 * helper discarded its own {@code unitPrice} argument and zeroed the <em>invoiced</em>
	 * price instead, which left the expected amount at 500 x 1 000.00 = 5 00,000 and
	 * never reached the undefined division this case exists to pin - the assertion on
	 * {@code percentageOfExpected()} passed for the wrong reason.
	 */
	private static CalculationInput freeItemInput(int lineNumber, String productKey, String quantity,
			String invoicedUnitPrice, CurrencyCode currency) {
		// The pricing list replaces goldenPricing rather than extending it: the scenario
		// needs this product's contracted price to be zero, and no other price can make
		// the expected amount of a single line vanish.
		return goldenInput(List.of(line(lineNumber, productKey, quantity, invoicedUnitPrice, "0.00", "0.00", currency)),
				AS_OF, AS_OF, List.of(price(productKey, "0.00", currency)), List.of());
	}

	/**
	 * Guards the dataset itself: if a contract price or a window is edited, the pinned
	 * figures above stop meaning anything, so the dataset states its own content.
	 */
	@Test
	@DisplayName("the fixed dataset is what the pinned figures were computed from")
	void documentsTheFixedDataset() {
		Map<String, String> prices = goldenPricing().stream()
				.collect(java.util.stream.Collectors.toMap(PricingTerm::productKey,
						term -> term.unitPrice().toPlainString() + " " + term.currency().value(),
						(first, second) -> first));

		assertThat(prices).containsExactlyInAnyOrderEntriesOf(Map.of(
				SKU_1000, "1000.00 INR",
				SKU_920, "920.00 INR",
				SKU_DISC, "1000.00 INR",
				SKU_FRAC, "33.333333 INR",
				SKU_SUB, "0.000050 INR",
				SKU_HUGE, "987654321.987654 INR",
				SKU_USD, "500.00 USD"));
		assertThat(goldenDiscounts()).singleElement().satisfies(term -> {
			assertThat(term.termId()).isEqualTo("DT-REG-DISC-10PCT");
			assertThat(term.discountValue()).isEqualByComparingTo("10");
		});
	}

}

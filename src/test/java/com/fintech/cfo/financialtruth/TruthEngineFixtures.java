package com.fintech.cfo.financialtruth;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.fintech.cfo.financialtruth.calculator.ActualAmountCalculator;
import com.fintech.cfo.financialtruth.calculator.ExpectedAmountCalculator;
import com.fintech.cfo.financialtruth.calculator.FinancialTruthEngine;
import com.fintech.cfo.financialtruth.calculator.ImpactAggregator;
import com.fintech.cfo.financialtruth.calculator.VarianceCalculator;
import com.fintech.cfo.financialtruth.enums.CalculationType;
import com.fintech.cfo.financialtruth.model.CalculationInput;
import com.fintech.cfo.financialtruth.model.CalculationResult;
import com.fintech.cfo.financialtruth.model.CalculationRun;
import com.fintech.cfo.financialtruth.model.DiscountTerm;
import com.fintech.cfo.financialtruth.model.InvoiceLineInput;
import com.fintech.cfo.financialtruth.model.PricingTerm;
import com.fintech.cfo.financialtruth.model.RoundingPolicy;
import com.fintech.cfo.financialtruth.rules.DiscountVarianceRule;
import com.fintech.cfo.financialtruth.rules.PricingVarianceRule;
import com.fintech.cfo.shared.domain.CurrencyCode;
import com.fintech.cfo.shared.domain.Money;
import com.fintech.cfo.shared.domain.OrganizationId;
import com.fintech.cfo.shared.domain.SourceReference;
import com.fintech.cfo.shared.domain.UserId;

/**
 * Fixed datasets shared by the financial truth tests.
 *
 * <p>Every value here is a literal. Nothing is derived from a clock, a random source
 * or the environment, because a financial regression suite whose expected values can
 * move is not a regression suite.
 *
 * <p>Product keys are named for the scenario they exist to exercise so a failing
 * assertion is self-describing.
 *
 * <p><b>Package-private and final, with a private constructor.</b> This is a
 * fixture, not a component: it must never be injectable, never appear in a
 * Spring context, and never be subclassed. Everything it hands out is either a
 * constant or a factory over those constants.
 *
 * <p><b>Contract for anyone extending it.</b> A new product key may be added
 * freely, but changing the value of an existing one invalidates pinned figures
 * in {@link FinancialRegressionTest} - including a hard-coded input checksum -
 * and those figures were computed from the values as they stand. Treat edits to
 * existing keys the same way the module treats an amended contract price: a
 * deliberate, reviewed change that also updates the pinned expectations.
 */
final class TruthEngineFixtures {

	/** Fixed clock: 2024-03-16T09:30:00Z, one day after {@link #AS_OF}. */
	static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2024-03-16T09:30:00Z"), ZoneOffset.UTC);

	static final CurrencyCode INR = CurrencyCode.inr();

	static final CurrencyCode USD = CurrencyCode.usd();

	static final CurrencyCode EUR = CurrencyCode.eur();

	static final LocalDate TERM_START = LocalDate.of(2024, 1, 1);

	static final LocalDate TERM_END = LocalDate.of(2024, 12, 31);

	/** Default business date for the term-selection tests. */
	static final LocalDate AS_OF = LocalDate.of(2024, 3, 15);

	/** March-only window, used by the boundary-date tests. */
	static final LocalDate MARCH_START = LocalDate.of(2024, 3, 1);

	static final LocalDate MARCH_END = LocalDate.of(2024, 3, 31);

	static final OrganizationId ORGANIZATION = new OrganizationId(
			UUID.fromString("0f9a1b3c-1111-4a2b-9c3d-5e6f70819a2b"));

	static final UUID RUN_ID = UUID.fromString("1a2b3c4d-2222-4b3c-8d4e-6f708192a3b4");

	static final UserId TRIGGERED_BY = new UserId(UUID.fromString("2b3c4d5e-3333-4c4d-9e5f-708192a3b4c5"));

	/** Contract price 920.00 INR; the acceptance example prices above it. */
	static final String SKU_OVERCHARGED = "SKU-OVERCHARGED";

	/** Contract price 1000.00 INR, invoiced at contract price. */
	static final String SKU_MATCHED = "SKU-MATCHED";

	/** Contract price 1000.00 INR, invoiced below it. */
	static final String SKU_UNDERCHARGED = "SKU-UNDERCHARGED";

	/** Contract price 1000.00 INR with a ten percent discount entitlement. */
	static final String SKU_DISCOUNTED = "SKU-DISCOUNTED";

	/** Contract price 500.00 USD for the multi-currency tests. */
	static final String SKU_DOLLAR = "SKU-DOLLAR";

	/** No pricing term and no discount term exist for this key. */
	static final String SKU_UNTERMED = "SKU-UNTERMED";

	/** Priced only during March 2024, for the boundary-date tests. */
	static final String SKU_MARCH_ONLY = "SKU-MARCH-ONLY";

	/** Contract price 33.333333 INR, for the rounding tests. */
	static final String SKU_FRACTIONAL = "SKU-FRACTIONAL";

	/** Contract price 0.000050 INR, exactly on a HALF_UP boundary. */
	static final String SKU_SUB_PAISA = "SKU-SUB-PAISA";

	/** Contract price 987654321.987654 INR, past the precision of a double. */
	static final String SKU_HUGE = "SKU-HUGE";

	private TruthEngineFixtures() {
	}

	// ---------------------------------------------------------------- calculators

	static ExpectedAmountCalculator expectedCalculator() {
		return new ExpectedAmountCalculator(RoundingPolicy.MONETARY_SCALE, RoundingPolicy.ROUNDING_MODE);
	}

	static ActualAmountCalculator actualCalculator() {
		return new ActualAmountCalculator(RoundingPolicy.MONETARY_SCALE, RoundingPolicy.ROUNDING_MODE);
	}

	static VarianceCalculator varianceCalculator() {
		return new VarianceCalculator(RoundingPolicy.MONETARY_SCALE, RoundingPolicy.ROUNDING_MODE);
	}

	static ImpactAggregator impactAggregator() {
		return new ImpactAggregator(RoundingPolicy.MONETARY_SCALE, RoundingPolicy.ROUNDING_MODE);
	}

	/** Engine with both rules, the configuration production would use. */
	static FinancialTruthEngine engine() {
		ExpectedAmountCalculator expected = expectedCalculator();
		ActualAmountCalculator actual = actualCalculator();
		VarianceCalculator variance = varianceCalculator();
		return new FinancialTruthEngine(expected, actual, variance, impactAggregator(),
				List.of(new PricingVarianceRule(expected, actual, variance),
						new DiscountVarianceRule(expected, actual, variance)),
				FIXED_CLOCK);
	}

	/** Engine with the pricing rule alone, to isolate one rule's behaviour. */
	static FinancialTruthEngine pricingOnlyEngine() {
		ExpectedAmountCalculator expected = expectedCalculator();
		ActualAmountCalculator actual = actualCalculator();
		VarianceCalculator variance = varianceCalculator();
		return new FinancialTruthEngine(expected, actual, variance, impactAggregator(),
				List.of(new PricingVarianceRule(expected, actual, variance)), FIXED_CLOCK);
	}

	// ------------------------------------------------------------------ contract

	static PricingTerm pricing(String productKey, String unitPrice, CurrencyCode currency) {
		return PricingTerm.fixedUnitPrice(productKey, "PT-" + productKey, new java.math.BigDecimal(unitPrice), currency,
				TERM_START, TERM_END, 1);
	}

	static PricingTerm pricing(String productKey, String unitPrice, CurrencyCode currency, LocalDate from,
			LocalDate to, int version) {
		return PricingTerm.fixedUnitPrice(productKey, "PT-" + productKey + "-v" + version,
				new java.math.BigDecimal(unitPrice), currency, from, to, version);
	}

	static DiscountTerm tenPercentDiscount(String productKey) {
		return DiscountTerm.percentage(productKey, "DT-" + productKey + "-10PCT", "10", TERM_START, TERM_END, 1);
	}

	// --------------------------------------------------------------------- lines

	static InvoiceLineInput line(int lineNumber, String productKey, String quantity, String unitPrice,
			CurrencyCode currency) {
		return InvoiceLineInput.of(lineNumber, productKey, new java.math.BigDecimal(quantity),
				Money.of(unitPrice, currency), source(lineNumber));
	}

	static InvoiceLineInput lineWithDiscount(int lineNumber, String productKey, String quantity, String unitPrice,
			String discountAmount, CurrencyCode currency) {
		return new InvoiceLineInput(lineNumber, productKey, new java.math.BigDecimal(quantity),
				Money.of(unitPrice, currency), Money.of(discountAmount, currency), Money.zero(currency),
				source(lineNumber));
	}

	static InvoiceLineInput lineWithTax(int lineNumber, String productKey, String quantity, String unitPrice,
			String taxAmount, CurrencyCode currency) {
		return new InvoiceLineInput(lineNumber, productKey, new java.math.BigDecimal(quantity),
				Money.of(unitPrice, currency), Money.zero(currency), Money.of(taxAmount, currency), source(lineNumber));
	}

	/** A line whose quantity was never captured, to exercise the incomplete-input path. */
	static InvoiceLineInput lineWithoutQuantity(int lineNumber, String productKey, String unitPrice,
			CurrencyCode currency) {
		return new InvoiceLineInput(lineNumber, productKey, null, Money.of(unitPrice, currency),
				Money.zero(currency), Money.zero(currency), source(lineNumber));
	}

	static SourceReference source(int lineNumber) {
		return SourceReference.of("erp", "invoice_line", "LINE-" + lineNumber, "file-inv-001", (long) lineNumber);
	}

	// --------------------------------------------------------------------- input

	/** The standard contract: three INR products plus one USD product. */
	static CalculationInput standardInput(List<InvoiceLineInput> lines) {
		return standardInput(lines, AS_OF);
	}

	static CalculationInput standardInput(List<InvoiceLineInput> lines, LocalDate asOf) {
		return input(lines, asOf,
				List.of(pricing(SKU_OVERCHARGED, "920.00", INR), pricing(SKU_MATCHED, "1000.00", INR),
						pricing(SKU_UNDERCHARGED, "1000.00", INR), pricing(SKU_DISCOUNTED, "1000.00", INR),
						pricing(SKU_FRACTIONAL, "33.333333", INR), pricing(SKU_SUB_PAISA, "0.000050", INR),
						pricing(SKU_HUGE, "987654321.987654", INR), pricing(SKU_DOLLAR, "500.00", USD)),
				List.of(tenPercentDiscount(SKU_DISCOUNTED)));
	}

	static CalculationInput input(List<InvoiceLineInput> lines, LocalDate asOf, List<PricingTerm> pricingTerms,
			List<DiscountTerm> discountTerms) {
		return new CalculationInput(ORGANIZATION, CalculationType.COMBINED_VARIANCE, "INV-2024-0001", asOf, asOf,
				lines, pricingTerms, discountTerms, TRIGGERED_BY);
	}

	static CalculationInput inputWithInvoiceDate(List<InvoiceLineInput> lines, LocalDate invoiceDate, LocalDate asOf,
			List<PricingTerm> pricingTerms, List<DiscountTerm> discountTerms) {
		return new CalculationInput(ORGANIZATION, CalculationType.COMBINED_VARIANCE, "INV-2024-0001", invoiceDate,
				asOf, lines, pricingTerms, discountTerms, TRIGGERED_BY);
	}

	// ------------------------------------------------------------------- lookups

	/** The authoritative combined row for a line. */
	static CalculationResult netResult(CalculationRun run, int lineNumber) {
		return run.results().stream()
				.filter(result -> result.calculationType() == CalculationType.COMBINED_VARIANCE)
				.filter(result -> result.lineNumber() == lineNumber)
				.findFirst()
				.orElseThrow(() -> new AssertionError("no combined result for line " + lineNumber));
	}

	/** The row produced by one rule for one line. */
	static CalculationResult ruleResult(CalculationRun run, String ruleCode, int lineNumber) {
		return run.results().stream()
				.filter(result -> result.ruleCode().equals(ruleCode))
				.filter(result -> result.lineNumber() == lineNumber)
				.findFirst()
				.orElseThrow(() -> new AssertionError("no result from rule " + ruleCode + " for line " + lineNumber));
	}

}

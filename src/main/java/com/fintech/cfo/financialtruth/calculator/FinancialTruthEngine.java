package com.fintech.cfo.financialtruth.calculator;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import com.fintech.cfo.financialtruth.enums.CalculationConfidence;
import com.fintech.cfo.financialtruth.enums.CalculationStatus;
import com.fintech.cfo.financialtruth.enums.CalculationType;
import com.fintech.cfo.financialtruth.enums.RuleStatus;
import com.fintech.cfo.financialtruth.enums.VarianceType;
import com.fintech.cfo.financialtruth.model.CalculationInput;
import com.fintech.cfo.financialtruth.model.CalculationResult;
import com.fintech.cfo.financialtruth.model.CalculationRun;
import com.fintech.cfo.financialtruth.model.FinancialImpact;
import com.fintech.cfo.financialtruth.model.InvoiceLineInput;
import com.fintech.cfo.financialtruth.model.TermEvaluation;
import com.fintech.cfo.financialtruth.model.Variance;
import com.fintech.cfo.financialtruth.rules.FinancialRule;
import com.fintech.cfo.financialtruth.rules.RuleContext;
import com.fintech.cfo.financialtruth.rules.RuleEvaluationResult;
import com.fintech.cfo.shared.domain.CurrencyCode;
import com.fintech.cfo.shared.domain.Money;
import com.fintech.cfo.shared.exception.ValidationException;

/**
 * The deterministic financial truth engine: expected, actual, variance, impact,
 * rule version, calculation run.
 *
 * <h2>Determinism</h2>
 * <ul>
 * <li>The only source of time is the injected {@link Clock}, read exactly once per
 * run so every result row in a run shares one {@code calculatedAt}. A fixed clock
 * makes a run byte-identical on replay.</li>
 * <li>The only source of data is the supplied {@link CalculationInput}. The engine
 * has no repository, no HTTP client and no ambient configuration.</li>
 * <li>Rules are sorted by code and version, so the rule set fingerprint and the
 * evaluation order do not depend on the order the caller injected them in.</li>
 * <li>Lines are processed in the order given. Totals sum already-rounded components,
 * so the total itself is order-independent as well.</li>
 * </ul>
 *
 * <h2>How a line is evaluated</h2>
 * <ol>
 * <li>Every registered rule is evaluated and its finding recorded as its own result
 * row, tagged with the rule code and version.</li>
 * <li>The engine then composes one <em>combined</em> row per line:
 * {@code expectedNet = expectedGross - expectedDiscount + tax} and
 * {@code actualNet = actualGross - actualDiscount + tax}.</li>
 * <li>The net variance must reconcile exactly with the components,
 * {@code pricingVariance - discountVariance}, or the run fails. Tax is contractual
 * pass-through and is included identically on both sides, so it contributes nothing
 * to the variance; it is still reported because the payable includes it.</li>
 * <li>That reconciliation is asserted only where it is provable: when the discount
 * entitlement was actually measured, or when the invoice granted no discount. A line
 * carrying a discount the contract never authorised is still reconciled and reported,
 * but at reduced confidence, because no component row exists that explains it.</li>
 * </ol>
 *
 * <h2>What is never invented</h2>
 * If the pricing rule raises because no contract price was in force, the whole run
 * fails: there is no defensible expected amount. If a rule reports
 * {@code INCOMPLETE_INPUTS}, the line gets no combined row and the run discloses the
 * omission in its impact confidence. Either way the engine substitutes no default.
 */
public final class FinancialTruthEngine {

	/** Rule code recorded on the authoritative combined row. */
	public static final String COMBINED_RULE_CODE = "NET_EXPECTED_AMOUNT";

	private final ExpectedAmountCalculator expectedAmountCalculator;
	private final ActualAmountCalculator actualAmountCalculator;
	private final VarianceCalculator varianceCalculator;
	private final ImpactAggregator impactAggregator;
	private final List<FinancialRule> rules;
	private final Clock clock;

	public FinancialTruthEngine(ExpectedAmountCalculator expectedAmountCalculator,
			ActualAmountCalculator actualAmountCalculator, VarianceCalculator varianceCalculator,
			ImpactAggregator impactAggregator, List<FinancialRule> rules, Clock clock) {
		this.expectedAmountCalculator = expectedAmountCalculator;
		this.actualAmountCalculator = actualAmountCalculator;
		this.varianceCalculator = varianceCalculator;
		this.impactAggregator = impactAggregator;
		if (clock == null) {
			throw new ValidationException("clock must not be null; the engine never reads the system clock directly");
		}
		this.clock = clock;
		if (rules == null || rules.isEmpty()) {
			throw new ValidationException("at least one FinancialRule must be registered");
		}
		this.rules = sortedByCode(rules);
	}

	/**
	 * Fingerprint of the exact rule set that produced a run, stored in
	 * {@code calculation_runs.rule_version}.
	 *
	 * <p>Sorted by rule code so the string is a property of the rules themselves and
	 * not of the injection order a caller happened to use.
	 *
	 * @return the codes joined by {@code +}, each as {@code code@version}
	 */
	public String ruleSetVersion() {
		StringBuilder version = new StringBuilder();
		for (FinancialRule rule : this.rules) {
			if (version.length() > 0) {
				version.append('+');
			}
			version.append(rule.versionedCode());
		}
		return version.toString();
	}

	/**
	 * Runs the full calculation.
	 *
	 * @param input a frozen snapshot of every input the result depends on
	 * @param runId supplied by the caller so that a replay can reconstruct the run
	 * @return a completed run carrying every rule row, the combined per-line rows and
	 *         one impact per currency
	 * @throws com.fintech.cfo.shared.exception.BusinessRuleException when a contract
	 *         term needed to establish an expected amount is missing or unusable
	 */
	public CalculationRun calculate(CalculationInput input, UUID runId) {
		if (input == null) {
			throw new ValidationException("input must not be null");
		}
		if (runId == null) {
			throw new ValidationException("runId must be supplied; a generated id would defeat reproducibility");
		}
		// Read once. A fixed clock therefore stamps an entire run with one instant.
		Instant evaluatedAt = this.clock.instant();
		String ruleVersion = this.ruleSetVersion();

		List<CalculationResult> results = new ArrayList<>();
		List<CalculationResult> combinedResults = new ArrayList<>();

		for (InvoiceLineInput line : input.lines()) {
			// The context is rebuilt per line and closed over that line, terms and
			// as-of date only. A rule therefore cannot see another line, which is what
			// makes per-line results independent and the whole run order-insensitive.
			RuleContext context = RuleContext.builder()
					.line(line)
					.asOfDate(input.asOfDate())
					.currency(line.currency())
					.pricingTerm(input.effectivePricingTerm(line.productKey(), input.asOfDate()))
					.discountTerms(input.effectiveDiscountTerms(line.productKey(), input.asOfDate()))
					.build();

			// LinkedHashMap: at most one rule may claim each calculation type, and the
			// last write would silently displace an earlier rule's finding. Insertion
			// order is preserved for the same reason the rule list is sorted.
			Map<CalculationType, RuleEvaluationResult> byCalculation = new LinkedHashMap<>();
			for (FinancialRule rule : this.rules) {
				// appliesTo is checked first so a rule that cannot possibly apply costs
				// nothing, and so its reason names the line rather than a generic message.
				RuleEvaluationResult evaluation = rule.appliesTo(context) ? rule.evaluate(context)
						: RuleEvaluationResult.notApplicable(rule.calculationType(),
								rule.code() + " does not apply to line " + line.lineNumber());
				// Every rule's row is recorded, including the ones that made no claim.
				// Omitting them would hide the fact that a check was considered.
				results.add(CalculationResult.fromRule(evaluation, rule.code(), rule.version(), line.lineNumber(),
						line.source(), confidenceFor(evaluation), evaluatedAt));
				byCalculation.put(rule.calculationType(), evaluation);
			}

			CalculationResult combined = this.combineLine(input, line, byCalculation, ruleVersion, evaluatedAt);
			if (combined != null) {
				results.add(combined);
				// Only combined rows reach the aggregator. Feeding it component rows as
				// well would count the same deviation twice.
				combinedResults.add(combined);
			}
		}

		// Line counts are gathered from the snapshot, not from the results: the whole
		// point is to know about the lines that produced no combined row at all.
		List<FinancialImpact> impacts = this.impactAggregator.aggregate(combinedResults, lineCountsByCurrency(input));
		// Stamps the same evaluatedAt into both lifecycle fields. A run is a single
		// evaluation, so it must not appear to have started and finished at two
		// different instants under the fixed clock.
		return new CalculationRun(runId, input.organizationId(), input.calculationType(), CalculationStatus.Completed.INSTANCE,
				ruleVersion, input.period().startDate(), input.period().endDate(), input.checksum(), input.triggeredBy(),
				evaluatedAt, evaluatedAt, null, results, impacts);
	}

	/**
	 * How many lines the snapshot presented in each currency.
	 *
	 * <p>Keyed by currency rather than a single total, so each per-currency impact can
	 * state its own coverage. A global count would make the INR total of a two-currency
	 * invoice claim that the USD line was excluded from it.
	 */
	private static Map<CurrencyCode, Integer> lineCountsByCurrency(CalculationInput input) {
		Map<CurrencyCode, Integer> counts = new TreeMap<>(Comparator.comparing(CurrencyCode::value));
		for (InvoiceLineInput line : input.lines()) {
			counts.merge(line.currency(), 1, Integer::sum);
		}
		return counts;
	}

	/**
	 * Builds the authoritative net row for one line, or {@code null} when the line
	 * could not be evaluated on complete inputs.
	 *
	 * <p>Returning {@code null} is not a silent omission: the aggregator counts the
	 * line as unevaluated and drops the run's confidence accordingly, and the
	 * component rows already on the record say why.
	 */
	private CalculationResult combineLine(CalculationInput input, InvoiceLineInput line,
			Map<CalculationType, RuleEvaluationResult> byCalculation, String ruleVersion, Instant evaluatedAt) {
		RuleEvaluationResult pricing = byCalculation.get(CalculationType.PRICING_VARIANCE);
		if (pricing == null || !pricing.isEvaluated()) {
			return null;
		}
		RuleEvaluationResult discount = byCalculation.get(CalculationType.DISCOUNT_VARIANCE);
		if (discount != null && discount.status() == RuleStatus.IncompleteInputs.INSTANCE) {
			// A discount entitlement we could not read is not an entitlement of zero.
			return null;
		}

		Money expectedGross = pricing.expected().amount();
		// An absent discount entitlement is zero, and only then. NOT_APPLICABLE means the
		// contract granted none, which is a positive statement; INCOMPLETE_INPUTS, caught
		// above, means we could not read it, which is not.
		Money expectedDiscount = discount != null && discount.isEvaluated() ? discount.expected().amount()
				: Money.zero(line.currency());
		// Tax is statutory, not contractual: the same amount is expected as was charged,
		// so it cancels out of the variance while remaining part of the payable.
		Money tax = this.actualAmountCalculator.actualTaxAmount(line);
		Money expectedNet = this.expectedAmountCalculator.expectedNetAmount(expectedGross, expectedDiscount, tax);
		Money actualNet = this.actualAmountCalculator.actualNetAmount(line);
		Variance netVariance = this.varianceCalculator.variance(expectedNet, actualNet, VarianceType.Combined.INSTANCE);

		// net variance = pricingVariance - discountVariance, because the net payable
		// subtracts the discount. Built from zero rather than from the net figure so
		// the check below is a genuine comparison rather than a tautology.
		Money components = Money.zero(line.currency());
		if (pricing.variance() != null) {
			components = components.add(pricing.variance().amount());
		}
		boolean entitlementMeasured = discount != null && discount.isEvaluated();
		if (entitlementMeasured && discount.variance() != null) {
			components = components.subtract(discount.variance().amount());
		}
		// The decomposition is only provable when the discount entitlement was actually
		// measured, or when the invoice granted nothing to decompose. A line that carries
		// a discount the contract never authorised is a genuine finding, but no
		// DiscountVariance result exists to explain it - asserting reconciliation there
		// would compare a figure against components that cannot produce it. The total is
		// still reported, at reduced confidence and with the gap stated.
		boolean decomposable = entitlementMeasured || this.actualAmountCalculator.actualDiscountAmount(line).isZero();
		if (decomposable) {
			this.varianceCalculator.assertReconciled(netVariance.amount(), components);
		}

		String decompositionNote = decomposable ? ""
				: "; the invoice granted a discount of " + this.actualAmountCalculator.actualDiscountAmount(line)
						.amount().toPlainString() + " " + line.currency().value()
						+ " that no contract entitlement covers, so this total cannot be split into its components";
		FinancialImpact impact = FinancialImpact.of(netVariance.amount(),
				decomposable ? CalculationConfidence.HIGH : CalculationConfidence.MEDIUM, 1, 0,
				"Line " + line.lineNumber() + " net payable variance; net = contract gross less entitled discount plus tax"
						+ decompositionNote);
		// Terms accumulate in evaluation order: pricing first, then discount, so a reader
		// of the lineage sees the same sequence the arithmetic used.
		List<TermEvaluation> terms = new ArrayList<>(pricing.evaluatedTerms());
		if (discount != null) {
			terms.addAll(discount.evaluatedTerms());
		}
		return CalculationResult.combined(COMBINED_RULE_CODE, ruleVersion, line.lineNumber(), expectedNet, actualNet,
				netVariance, impact, terms, line.source(),
				"Expected net " + expectedNet.amount().toPlainString() + " " + expectedNet.currency().value()
						+ ", actual net " + actualNet.amount().toPlainString() + " " + actualNet.currency().value()
						+ " for invoice " + input.invoiceNumber(),
				evaluatedAt);
	}

	/**
	 * Confidence in a rule's finding follows its status: a finding nobody could make
	 * is not a high-confidence finding.
	 *
	 * <p>An exhaustive {@code switch} over the closed {@link RuleStatus} set, so adding a
	 * variant is a compile error here rather than a silent fall-through to a wrong
	 * confidence. {@code NOT_APPLICABLE} is {@code LOW} on purpose: the rule made no
	 * claim, and a row that carries no figure must not look like one that does.
	 */
	private static CalculationConfidence confidenceFor(RuleEvaluationResult evaluation) {
		return switch (evaluation.status()) {
			case RuleStatus.Evaluated evaluated -> CalculationConfidence.HIGH;
			case RuleStatus.NotApplicable notApplicable -> CalculationConfidence.LOW;
			case RuleStatus.IncompleteInputs incomplete -> CalculationConfidence.LOW;
		};
	}

	/**
	 * Sorts by code then version and rejects duplicate codes. Without the duplicate
	 * check, two rules sharing a code would silently overwrite each other's results in
	 * the per-calculation map and one finding would vanish from the run.
	 */
	private static List<FinancialRule> sortedByCode(List<FinancialRule> rules) {
		List<FinancialRule> sorted = new ArrayList<>(rules);
		sorted.sort(Comparator.comparing(FinancialRule::code).thenComparing(FinancialRule::version));
		for (int index = 1; index < sorted.size(); index++) {
			if (sorted.get(index).code().equals(sorted.get(index - 1).code())) {
				throw new ValidationException("duplicate rule code " + sorted.get(index).code()
						+ "; distinct rules must carry distinct codes or their results cannot be told apart");
			}
		}
		return List.copyOf(sorted);
	}

}
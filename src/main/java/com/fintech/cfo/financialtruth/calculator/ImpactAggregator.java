package com.fintech.cfo.financialtruth.calculator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fintech.cfo.financialtruth.enums.CalculationConfidence;
import com.fintech.cfo.financialtruth.enums.CalculationType;
import com.fintech.cfo.financialtruth.enums.RuleStatus;
import com.fintech.cfo.financialtruth.model.CalculationResult;
import com.fintech.cfo.financialtruth.model.FinancialImpact;
import com.fintech.cfo.financialtruth.model.RoundingPolicy;
import com.fintech.cfo.shared.domain.CurrencyCode;
import com.fintech.cfo.shared.domain.Money;
import com.fintech.cfo.shared.exception.ValidationException;

/**
 * Rolls per-line variances up into one impact figure per currency.
 *
 * <h2>Only combined results are aggregated</h2>
 * Component rows (pricing, discount) exist to explain a deviation; they must never
 * be added to each other or to a combined row, because the same rupees would then be
 * counted twice. This aggregator accepts combined rows exclusively and rejects
 * anything else, rather than trusting the caller to have filtered correctly.
 *
 * <h2>Never crosses currencies</h2>
 * An invoice set that spans currencies produces one impact per currency, in a fixed
 * currency-code order. No exchange rate is available to this module and inventing
 * one would be the single most damaging thing it could do, so a total is only ever
 * produced for a currency every contributing amount shares. Each total measures its
 * own coverage against the lines denominated in its own currency, so it never
 * reports - or discounts confidence for - a line that belongs to another total.
 */
public final class ImpactAggregator {

	/**
	 * Fixed ordering of the per-currency impacts. Stated explicitly rather than left
	 * to a hash order, so the sequence of returned impacts is reproducible.
	 */
	private static final Comparator<CurrencyCode> CURRENCY_ORDER = Comparator.comparing(CurrencyCode::value);

	private final int monetaryScale;
	private final RoundingMode roundingMode;

	public ImpactAggregator() {
		this(RoundingPolicy.MONETARY_SCALE, RoundingPolicy.ROUNDING_MODE);
	}

	/**
	 * @param monetaryScale decimal places every produced total is rounded to
	 * @param roundingMode  the single rounding mode applied to every total
	 */
	public ImpactAggregator(int monetaryScale, RoundingMode roundingMode) {
		if (monetaryScale < 0) {
			throw new ValidationException("monetaryScale must not be negative");
		}
		if (roundingMode == null) {
			throw new ValidationException("roundingMode must not be null; implicit rounding is not permitted");
		}
		this.monetaryScale = monetaryScale;
		this.roundingMode = roundingMode;
	}

	/**
	 * @param netResults          combined per-line results; no component row may be passed
	 * @param lineCountByCurrency how many invoice lines were presented in each currency,
	 *                            so a total can disclose only the lines it was actually
	 *                            unable to cover
	 * @return one impact per currency, ordered by currency code; empty when nothing
	 *         could be evaluated
	 */
	public List<FinancialImpact> aggregate(List<CalculationResult> netResults,
			Map<CurrencyCode, Integer> lineCountByCurrency) {
		if (lineCountByCurrency == null) {
			// Required rather than defaulted: a total that cannot say how many lines it
			// was given cannot disclose the lines it left out.
			throw new ValidationException("lineCountByCurrency must not be null; a total cannot disclose its own "
					+ "coverage without knowing how many lines it was given");
		}
		for (Map.Entry<CurrencyCode, Integer> entry : lineCountByCurrency.entrySet()) {
			if (entry.getKey() == null || entry.getValue() == null || entry.getValue() < 0) {
				throw new ValidationException("line counts must be non-negative and keyed by currency");
			}
		}
		List<CalculationResult> contributing = requireCombinedOnly(netResults);

		// TreeMap under an explicit comparator: the per-currency impacts come back in
		// currency-code order every run, so a report built from them is reproducible.
		Map<CurrencyCode, List<CalculationResult>> byCurrency = new TreeMap<>(CURRENCY_ORDER);
		for (CalculationResult result : contributing) {
			byCurrency.computeIfAbsent(result.varianceAmount().currency(), key -> new ArrayList<>()).add(result);
		}

		List<FinancialImpact> impacts = new ArrayList<>(byCurrency.size());
		for (Map.Entry<CurrencyCode, List<CalculationResult>> entry : byCurrency.entrySet()) {
			impacts.add(summarise(entry.getKey(), entry.getValue(), lineCountByCurrency));
		}
		return List.copyOf(impacts);
	}

	/**
	 * Only the lines in this total's own currency are candidates for its coverage.
	 *
	 * <p>Dividing by a currency-blind line count would make every total on a
	 * multi-currency invoice claim that the lines belonging to the <em>other</em>
	 * currency had been excluded from it. That is a false statement in a document a
	 * finance team acts on, and it would also downgrade a fully evaluated invoice to
	 * medium confidence for no reason.
	 */
	private FinancialImpact summarise(CurrencyCode currency, List<CalculationResult> rows,
			Map<CurrencyCode, Integer> lineCountByCurrency) {
		// Sorted before summing. Summing already-rounded components is exact whatever
		// the order, but iterating in a fixed order keeps this method independent of
		// how the caller happened to assemble its list.
		List<CalculationResult> ordered = new ArrayList<>(rows);
		ordered.sort(Comparator.comparingInt(CalculationResult::lineNumber));

		BigDecimal total = BigDecimal.ZERO;
		// The run inherits the weakest confidence of its rows, not the strongest or the
		// most common. One unreadable line among twenty makes the twenty-line total a
		// statement about nineteen lines.
		CalculationConfidence worst = CalculationConfidence.HIGH;
		for (CalculationResult row : ordered) {
			// Summing already-rounded components: exact, and never re-rounded below, so
			// the total cannot depend on the order the lines were presented in.
			total = total.add(row.varianceAmount().amount());
			if (row.confidence().isLessConfidentThan(worst)) {
				worst = row.confidence();
			}
		}
		// Only this currency's own line count is consulted. A negative count would mean
		// more combined rows than lines, which cannot arise; floored at zero so the
		// disclosure can never state a negative number of exclusions.
		int unevaluated = Math.max(0, lineCountByCurrency.getOrDefault(currency, 0) - ordered.size());
		String rationale = "Sum of " + ordered.size() + " combined line variance(s) in " + currency.value()
				+ "; " + unevaluated + " line(s) in " + currency.value()
				+ " could not be evaluated and are excluded from this total";
		return FinancialImpact.of(Money.of(total.setScale(this.monetaryScale, this.roundingMode), currency),
				downgradeForGaps(worst, unevaluated), ordered.size(), unevaluated, rationale);
	}

	/**
	 * A total that omits lines is a floor, not the whole truth, so it must not be
	 * presented at the highest confidence however exact the figures it does contain
	 * are.
	 */
	private static CalculationConfidence downgradeForGaps(CalculationConfidence worst, int unevaluated) {
		// A LOW row is already the floor, so nothing can lower it further. Otherwise any
		// omitted line costs exactly one step: HIGH to MEDIUM, MEDIUM unchanged.
		if (worst == CalculationConfidence.LOW || unevaluated > 0) {
			return worst == CalculationConfidence.LOW ? worst : CalculationConfidence.MEDIUM;
		}
		return worst;
	}

	private static List<CalculationResult> requireCombinedOnly(List<CalculationResult> results) {
		List<CalculationResult> combined = new ArrayList<>();
		for (CalculationResult result : results) {
			if (result == null) {
				// A null entry is skipped rather than rejected: a caller assembling a
				// filtered list should not have to compact it first.
				continue;
			}
			// The guard that stops the same deviation being counted twice. Component
			// rows are rejected outright rather than filtered, so a caller cannot
			// misread "no complaint" as "nothing to worry about".
			if (result.calculationType() != CalculationType.COMBINED_VARIANCE) {
				throw new ValidationException("ImpactAggregator accepts only COMBINED_VARIANCE results; got "
						+ result.calculationType() + " for rule " + result.ruleCode()
						+ ". Aggregating component rows as well would count the same money twice");
			}
			// Only rows that actually carry a figure contribute. A combined row with no
			// variance is not a zero to add; it is a line with nothing established.
			if (result.status() == RuleStatus.Evaluated.INSTANCE && result.varianceAmount() != null) {
				combined.add(result);
			}
		}
		return combined;
	}

}
package com.fintech.cfo.financialtruth.service;

import java.util.List;
import java.util.UUID;

import com.fintech.cfo.financialtruth.calculator.FinancialTruthEngine;
import com.fintech.cfo.financialtruth.model.CalculationInput;
import com.fintech.cfo.financialtruth.model.CalculationResult;
import com.fintech.cfo.financialtruth.model.CalculationRun;
import com.fintech.cfo.financialtruth.model.FinancialImpact;
import com.fintech.cfo.shared.domain.CurrencyCode;
import com.fintech.cfo.shared.domain.Money;
import com.fintech.cfo.shared.exception.NotFoundException;
import com.fintech.cfo.shared.exception.ValidationException;

/**
 * The application-facing entry point for running and reading a calculation.
 *
 * <p>Deliberately thin and infrastructure-free: it holds the engine and a few
 * query helpers, nothing else. Persistence is not wired in here. The
 * {@code platform.audit.AuditService} that a production run must also write to
 * requires a JPA repository, so injecting it would make this class untestable without
 * a database; that wiring belongs to whoever assembles the module at runtime.
 */
public final class CalculationService {

	private final FinancialTruthEngine engine;

	public CalculationService(FinancialTruthEngine engine) {
		this.engine = engine;
	}

	/**
	 * Runs the engine over the supplied snapshot.
	 *
	 * <p>The run id is passed through, not generated, so a caller that wants to record
	 * this run and later replay it uses one identifier for both.
	 *
	 * @param input frozen snapshot of every input the result depends on
	 * @param runId identifier the run will be stored and replayed under
	 * @return the completed run
	 * @throws com.fintech.cfo.shared.exception.BusinessRuleException when contract terms
	 *         needed to establish an expected amount are missing or unusable
	 */
	public CalculationRun calculate(CalculationInput input, UUID runId) {
		return this.engine.calculate(input, runId);
	}

	/** The value that belongs in {@code calculation_runs.input_checksum}. */
	public String inputChecksum(CalculationInput input) {
		return input.checksum();
	}

	/** The rule-set fingerprint that belongs in {@code calculation_runs.rule_version}. */
	public String ruleSetVersion() {
		return this.engine.ruleSetVersion();
	}

	/**
	 * The authoritative net variances of a run, one per evaluated line, in line order.
	 *
	 * <p>Component rows are excluded by construction rather than filtered by the
	 * caller, so no report can accidentally sum a price deviation and a discount
	 * deviation together.
	 */
	public List<CalculationResult> netResults(CalculationRun run) {
		return run.combinedResults();
	}

	/** The net variance for one line, or a not-found error naming the line. */
	public CalculationResult netResultForLine(CalculationRun run, int lineNumber) {
		return netResults(run).stream()
				.filter(result -> result.lineNumber() == lineNumber)
				.findFirst()
				.orElseThrow(() -> new NotFoundException(
						"calculation run " + run.runId() + " has no evaluated net result for line " + lineNumber));
	}

	/** Every per-currency impact of a run, in currency-code order. */
	public List<FinancialImpact> impacts(CalculationRun run) {
		return run.impacts();
	}

	/**
	 * The net variance total for a single-currency run.
	 *
	 * @throws ValidationException when the run reported no impact at all, or reported
	 *                              more than one currency - never a silent zero
	 */
	public Money netVarianceIn(CalculationRun run, CurrencyCode currency) {
		List<FinancialImpact> impacts = run.impacts();
		// Every branch raises. There is no path here that returns a zero, because a
		// caller asking for "the total" and receiving 0 would report a clean invoice for a
		// run that established nothing.
		if (impacts.isEmpty()) {
			throw new ValidationException("calculation run " + run.runId()
					+ " produced no evaluated variance, so there is no total in any currency");
		}
		if (impacts.size() > 1) {
			// Multi-currency: one total per currency is the only honest answer. Picking one,
			// or summing them, would need an FX conversion this module does not have.
			throw new ValidationException("calculation run " + run.runId() + " spans " + impacts.size()
					+ " currencies; a single total cannot be reported without an FX conversion this module does not have");
		}
		FinancialImpact only = impacts.get(0);
		if (!only.totalImpact().currency().equals(currency)) {
			throw new ValidationException("calculation run " + run.runId() + " reports in "
					+ only.totalImpact().currency().value() + ", not " + currency.value());
		}
		return only.totalImpact();
	}

}
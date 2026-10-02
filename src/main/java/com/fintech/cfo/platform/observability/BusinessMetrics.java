package com.fintech.cfo.platform.observability;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Business-level metrics, deliberately distinct from framework metrics.
 *
 * <h2>What is safe to measure</h2>
 * Counts and latencies are fine; monetary values are not exported as metric
 * tags. A metric label is indexed and often visible on shared dashboards, and a
 * tagged amount would leak tenant financial data into telemetry. Amounts are
 * aggregated into distributions without a tenant or entity label.
 *
 * <p>Tags are limited to low-cardinality, non-identifying values (status,
 * calculation type, rule code). Tenant IDs are deliberately excluded.
 */
@Component
public class BusinessMetrics {

	private final MeterRegistry meterRegistry;

	public BusinessMetrics(MeterRegistry meterRegistry) {
		this.meterRegistry = meterRegistry;
	}

	/**
	 * Records the outcome of a calculation run.
	 *
	 * <p>Count and duration are published as two separate meters rather than one
	 * timer, because they answer different questions: the counter says how often,
	 * the timer says how long. {@code status} is tagged only on the counter, since a
	 * per-status duration breakdown is not something the dashboards query.
	 *
	 * @param calculationType calculation type code, low cardinality
	 * @param status          outcome status, low cardinality
	 * @param durationMillis  wall-clock duration of the run
	 */
	public void recordCalculationRun(String calculationType, String status, long durationMillis) {
		Counter.builder("cfo.calculation.runs")
			.tag("type", calculationType)
			.tag("status", status)
			.description("Financial truth engine calculation runs")
			.register(this.meterRegistry)
			.increment();

		Timer.builder("cfo.calculation.duration")
			.tag("type", calculationType)
			.description("Calculation run duration")
			.register(this.meterRegistry)
			.record(durationMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
	}

	/**
	 * Records a variance discovered by a rule. Amount is counted, never tagged.
	 *
	 * <p>The variance amount is deliberately not exported anywhere. It is the single
	 * most sensitive value in the system — a leaked one tells a competitor what a
	 * specific customer overpaid — and metric tags are indexed, retained for the
	 * full metrics window and frequently readable by anyone with dashboard access.
	 *
	 * @param ruleCode      rule that fired, low cardinality
	 * @param varianceType  variance classification, low cardinality
	 */
	public void recordVariance(String ruleCode, String varianceType) {
		Counter.builder("cfo.variances.detected")
			.tag("rule", ruleCode)
			.tag("type", varianceType)
			.description("Variances detected by financial rules")
			.register(this.meterRegistry)
			.increment();
	}

	/**
	 * Records the outcome and shape of an ingestion batch.
	 *
	 * <p>Row count goes into a {@code DistributionSummary}, not a tag: a
	 * per-row-count tag value would create a new series for every distinct file size
	 * and exhaust the registry, which is exactly what the meter cap in
	 * {@link MetricsConfiguration} exists to catch.
	 *
	 * @param dataSource     source system, low cardinality
	 * @param status         batch outcome, low cardinality
	 * @param rowCount       rows processed, or negative when not applicable and
	 *                       therefore not recorded
	 * @param durationMillis wall-clock duration of the batch
	 */
	public void recordIngestionBatch(String dataSource, String status, long rowCount, long durationMillis) {
		Counter.builder("cfo.ingestion.batches")
			.tag("source", dataSource)
			.tag("status", status)
			.register(this.meterRegistry)
			.increment();

		Timer.builder("cfo.ingestion.duration")
			.tag("source", dataSource)
			.register(this.meterRegistry)
			.record(durationMillis, java.util.concurrent.TimeUnit.MILLISECONDS);

		if (rowCount >= 0) {
			// Distribution only - no tenant or entity label on a row count.
			io.micrometer.core.instrument.DistributionSummary.builder("cfo.ingestion.rows")
				.tag("source", dataSource)
				.register(this.meterRegistry)
				.record(rowCount);
		}
	}

	/**
	 * Records a lifecycle transition on an economic opportunity.
	 *
	 * @param opportunityType opportunity classification, low cardinality
	 * @param toStatus        status entered, not the status left; counting only the
	 *                        destination makes the metric a histogram of current
	 *                        states rather than a count of every event
	 */
	public void recordOpportunityLifecycle(String opportunityType, String toStatus) {
		Counter.builder("cfo.opportunities.transitions")
			.tag("type", opportunityType)
			.tag("status", toStatus)
			.description("Economic opportunity lifecycle transitions")
			.register(this.meterRegistry)
			.increment();
	}

	/**
	 * Records an audit row that could not be written.
	 *
	 * <p>This is the metric that justifies the isolated-transaction trade-off in
	 * {@code AuditService}. Audit failures are swallowed there so that a storage
	 * problem never rolls back a legitimate financial operation, which is only
	 * defensible because the failure is counted here: the trade hides the failure
	 * from the caller, not from operations.
	 *
	 * @param eventType attempted event type, low cardinality
	 */
	public void recordAuditFailure(String eventType) {
		Counter.builder("cfo.audit.write.failures")
			.tag("event", eventType)
			.description("Audit rows that could not be written - always actionable")
			.register(this.meterRegistry)
			.increment();
	}

	/**
	 * Records a request served from a stored idempotent response.
	 *
	 * <p>Untagged and count-only. The metric answers "is client retry behaviour
	 * normal?", which is the reason the replay path exists at all.
	 */
	public void recordIdempotentReplays() {
		Counter.builder("cfo.idempotency.replays")
			.description("Requests served from a stored idempotent response")
			.register(this.meterRegistry)
			.increment();
	}

}
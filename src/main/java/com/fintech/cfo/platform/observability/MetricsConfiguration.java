package com.fintech.cfo.platform.observability;

import java.util.Locale;
import java.util.Set;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.core.instrument.config.MeterFilter;

/**
 * Meter registry hygiene.
 *
 * <p>Guards against the failure mode where instrumentation itself becomes the
 * incident: unbounded label cardinality, and identity data leaking into
 * telemetry.
 */
@Configuration(proxyBeanMethods = false)
public class MetricsConfiguration {

	/**
	 * Tag keys that must never appear on a metric. A metric label is indexed,
	 * retained and often visible on shared dashboards, so tagging one with a
	 * tenant or user identifier would export financial data into telemetry.
	 */
	private static final Set<String> FORBIDDEN_TAG_KEYS = Set.of("organizationId", "organization_id", "tenantId",
			"tenant_id", "userId", "user_id", "actorId", "actor_id", "email", "customerId", "customer_id",
			"accountId", "account_id");

	/** Upper bound on distinct meters registered by this application. */
	private static final int MAX_DISTINCT_METERS = 2000;

	/**
	 * Drops any meter that carries an identity tag.
	 *
	 * <p>Deliberately enforced in code rather than by convention: the failure it
	 * prevents is silent, and a review checklist is not an adequate control.
	 */
	@Bean
	public MeterFilter forbidIdentityTags() {
		return MeterFilter.deny(id -> id.getTags().stream().anyMatch(tag -> {
			String key = tag.getKey().toLowerCase(Locale.ROOT);
			return FORBIDDEN_TAG_KEYS.contains(key);
		}));
	}

	/**
	 * Caps the total number of distinct meters so a runaway high-cardinality
	 * tag cannot exhaust the registry.
	 */
	@Bean
	public MeterFilter capMeterCount() {
		return MeterFilter.maximumAllowableMetrics(MAX_DISTINCT_METERS);
	}

}
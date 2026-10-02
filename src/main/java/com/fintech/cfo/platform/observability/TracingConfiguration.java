package com.fintech.cfo.platform.observability;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.config.MeterFilter;

/**
 * Tracing and observation configuration.
 *
 * <h2>Tracing is intentionally not configured here</h2>
 * {@code micrometer-tracing} is not on the classpath. A tracer without a
 * configured exporter produces spans that are built and then discarded, which
 * costs work and yields no operational value while appearing instrumented.
 * Tracing belongs with the first real exporter (Phase 0 observability
 * milestone, M15) so that span sampling and export cost are decided deliberately
 * rather than by accident.
 *
 * <p>What is configured now is the part that must be correct regardless: metrics
 * identity, and the correlation ID that joins log lines together.
 */
@Configuration(proxyBeanMethods = false)
public class TracingConfiguration {

	/**
	 * Stamps every meter with the application name so a shared metrics backend
	 * can distinguish these series from any other service on the same cluster.
	 */
	@Bean
	public MeterFilter applicationIdentityTag() {
		return MeterFilter.commonTags(Tags.of("application", "cfo-api"));
	}

}
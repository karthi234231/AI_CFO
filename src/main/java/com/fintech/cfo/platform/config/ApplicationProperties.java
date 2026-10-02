package com.fintech.cfo.platform.config;

import java.time.Duration;
import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Strongly typed application configuration bound from {@code cfo.application.*}.
 *
 * <p>Bound once by {@code @ConfigurationPropertiesScan} and injected as an immutable
 * object; services never read YAML directly and {@code @Value} is not scattered through
 * business modules. Secrets (database passwords, API keys, LLM keys, cloud credentials)
 * are deliberately excluded and must be supplied through a secret manager or environment.
 *
 * <p><b>Why a record rather than a class with setters.</b> Configuration is read once at
 * startup and never mutated, so immutability is free here and removes the possibility of a
 * service observing a half-applied reconfiguration. {@code @DefaultValue} on each component
 * means the application starts with no {@code cfo.application.*} block at all, which keeps a
 * local run and a test slice from having to declare configuration they do not care about.
 *
 * <p><b>What this type is not.</b> It is not a settings screen and not a place to hang feature
 * toggles. Its scope is deliberately the handful of values that describe the deployment
 * itself; anything a module needs to behave differently belongs in that module's own
 * properties type, so the blast radius of a change stays inside the module that made it.
 *
 * @param name                       human-readable application name, used in OpenAPI and log banners
 * @param version                    build version, surfaced in health and documentation output
 * @param environment                deployment environment, selecting environment-specific behaviour
 * @param correlationIdResponseHeader whether to echo {@code X-Correlation-ID} on responses
 */
@ConfigurationProperties(prefix = "cfo.application")
public record ApplicationProperties(
		@DefaultValue("AI_CFO") String name,
		@DefaultValue("0.0.1") String version,
		@DefaultValue("local") Environment environment,
		@DefaultValue("true") boolean correlationIdResponseHeader) {

	/**
	 * Deployment environment, bound case-insensitively from {@code local}, {@code dev},
	 * {@code test}, {@code staging}, {@code prod}.
	 *
	 * <p>A closed enum rather than a free string so that the places where the environment
	 * changes behaviour — logging levels, which endpoints are exposed, whether verbose error
	 * detail may be logged — are decided by a compile-time exhaustive check. A missing value
	 * falls back to {@link #LOCAL}, the safest setting, because an unlabelled deployment
	 * should behave like a development one rather than like production.
	 */
	public enum Environment {

		/** Developer machine or unmanaged run. */
		LOCAL,

		/** Shared development environment. */
		DEV,

		/** Automated test run. */
		TEST,

		/** Pre-production environment mirroring production configuration. */
		STAGING,

		/** Live production deployment. */
		PROD;

		/**
		 * @return true only for {@link #PROD}. Identity comparison rather than a
		 *         set membership test because the question is always strictly
		 *         "is this live?", and {@code PROD} is the one value that must
		 *         never be inferred from a broader match.
		 */
		public boolean isProduction() {
			return this == PROD;
		}

		/**
		 * @param value environment name as configured, in any case, possibly null
		 * @return the matching constant, or {@link #LOCAL} when the value is absent
		 *         or blank
		 * @throws IllegalArgumentException if the value names no known environment;
		 *                                  an unrecognised environment is a
		 *                                  deployment mistake worth surfacing rather
		 *                                  than defaulting away
		 */
		public static Environment from(String value) {
			if (value == null || value.isBlank()) {
				return LOCAL;
			}
			// Locale.ROOT, not the default locale, so a non-English default cannot
			// corrupt the case conversion of the configured name.
			return Environment.valueOf(value.trim().toUpperCase(Locale.ROOT));
		}
	}

}

package com.fintech.cfo.platform.config;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.ToStringSerializer;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Consistent Jackson 3 JSON behaviour.
 *
 * <p>Customizes Spring Boot's managed mapper rather than declaring a competing
 * global bean. The critical financial rule is
 * {@link DeserializationFeature#USE_BIG_DECIMAL_FOR_FLOATS}: financial JSON
 * numbers must never be routed through {@code double} or {@code float}.
 *
 * <p>Polymorphic deserialization is deliberately not enabled, so JSON can
 * never trigger arbitrary Java class instantiation.
 */
@Configuration(proxyBeanMethods = false)
public class JacksonConfig {

	@Bean
	public JsonMapperBuilderCustomizer jsonMapperBuilderCustomizer() {
		return builder -> builder
				.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
				.disable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
				.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
				// Reject trailing tokens so "{} {}" or "1,2" can never be silently accepted.
				.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
				.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
				.disable(MapperFeature.ALLOW_FINAL_FIELDS_AS_MUTATORS)
				.changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
				.addModule(sharedDomainModule());
	}

	/**
	 * Serializer module for shared value objects.
	 *
	 * <p>Types are rendered as their canonical string form so the wire format
	 * stays stable and explicit rather than depending on reflective bean
	 * layout: {@code Money} → {@code "920.00 INR"}, {@code CurrencyCode} →
	 * {@code "INR"}, ids → their UUID text, {@code SourceReference} → a single
	 * reference string. Deserialization of these types is intentionally left to
	 * the DTO layer, which owns the API mapping.
	 */
	private SimpleModule sharedDomainModule() {
		SimpleModule module = new SimpleModule("cfo-shared-domain");
		module.addSerializer(BigDecimal.class, new ToStringSerializer(BigDecimal.class));
		module.addSerializer(Instant.class, new ToStringSerializer(Instant.class));
		module.addSerializer(LocalDate.class, new ToStringSerializer(LocalDate.class));
		module.addSerializer(LocalDateTime.class, new ToStringSerializer(LocalDateTime.class));
		module.addSerializer(OffsetDateTime.class, new ToStringSerializer(OffsetDateTime.class));
		return module;
	}

}

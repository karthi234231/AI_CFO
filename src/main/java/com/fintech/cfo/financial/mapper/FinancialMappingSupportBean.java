package com.fintech.cfo.financial.mapper;

import org.springframework.stereotype.Component;

/**
 * Spring-wirable implementation of {@link FinancialMappingSupport}.
 *
 * <p>The conversions live as {@code default} methods on the config interface so
 * MapStruct can use them at compile time through {@code uses = ...}, but a
 * {@code @MapperConfig} type is never instantiated as a bean. The generated
 * {@code *MapperImpl} classes declare it as an {@code @Autowired} helper, so
 * without a concrete bean the application context fails with
 * {@code NoSuchBeanDefinitionException}. This empty implementation inherits
 * every conversion and exists only to give Spring something to instantiate.
 *
 * <p>A separate top-level type in its own file rather than an inner class
 * because MapStruct treats a type referenced by {@code uses} as a helper
 * source; nesting it inside the config would risk it being picked up as an
 * additional mapping provider.
 */
@Component
class FinancialMappingSupportBean implements FinancialMappingSupport {
}

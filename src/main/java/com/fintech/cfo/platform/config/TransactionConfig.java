package com.fintech.cfo.platform.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Transaction management configuration.
 *
 * <p>Application service / use-case methods own transaction boundaries with
 * {@code @Transactional}; repository methods do not. Financial mutations and
 * their audit records must commit in the same transaction.
 *
 * <p>Large file-processing operations are deliberately not wrapped in one
 * massive transaction — ingestion and calculation jobs use chunked
 * transactions via {@code processing/}.
 */
@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement
public class TransactionConfig {

}

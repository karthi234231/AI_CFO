package com.fintech.cfo.platform.config;

import java.lang.reflect.Method;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.VirtualThreadTaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Controlled asynchronous execution for lightweight, non-durable work only
 * (document metadata, non-critical notifications, independent post-processing).
 *
 * <p>Durable ingestion, long-running financial calculations, and anything
 * needing retry/resume semantics belong to {@code processing/} and Spring
 * Batch, not to this executor.
 *
 * <p><b>Why virtual threads.</b> The work reaching this executor is I/O-bound —
 * waiting on a database row or a storage read — so thread-per-request buys the
 * platform's cheap threads instead of paying to park expensive ones. This is
 * also why there is no pool sizing here: a bounded platform thread pool needs a
 * size tuned to a workload, and tuning a number that costs nothing to overshoot
 * is a decision made for the wrong reason.
 *
 * <p><b>The boundary is the important part.</b> Work arriving here may be lost if
 * the process exits, because nothing is persisted before the task starts and no
 * task is retried. That is acceptable for a notification and unacceptable for an
 * ingestion batch, which is exactly why {@code processing/} exists alongside this
 * class. When a use case becomes durable, it must move rather than gain a
 * retry loop here, so that the two durability models never overlap.
 *
 * <p><b>Why this file is separate from {@code TransactionConfig} and the rest of
 * {@code platform/config}.</b> {@code @EnableAsync} switches on proxy-based
 * interception of {@code @Async} methods across the whole application, so it is
 * enabled in exactly one place. Declaring it in more than one configuration class
 * is legal and produces duplicate infrastructure beans.
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
public class AsyncConfig {

	private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

	/**
	 * The default executor for {@code @Async}, named to match the framework's
	 * expected bean so {@code @Async} with no explicit qualifier resolves here.
	 *
	 * <p>The {@code cfo-async-} prefix makes async work identifiable in thread
	 * dumps and log patterns, which is the only practical way to tell a stuck
	 * task apart from a stuck request once it is running.
	 *
	 * @return a virtual-thread executor
	 */
	@Bean(name = "applicationTaskExecutor")
	public Executor applicationTaskExecutor() {
		return new VirtualThreadTaskExecutor("cfo-async-");
	}

	/**
	 * Handles an exception thrown by a {@code void} {@code @Async} method.
	 *
	 * <p>{@code void} is the case that matters: when an async method returns
	 * void, nobody holds its Future, so an exception it throws has no other way
	 * to surface and would otherwise disappear entirely. Without this handler a
	 * failed background task is invisible, and the failure is discovered much
	 * later as missing data. Methods returning a value surface their own failure
	 * through the Future and do not reach this.
	 *
	 * @return a handler that logs the failing method and its exception
	 */
	@Bean
	public AsyncUncaughtExceptionHandler asyncUncaughtExceptionHandler() {
		// The Method is logged rather than the arguments: arguments to an async
		// business task can carry customer financial data.
		return (Throwable ex, Method method, Object... params) -> log.error("Unhandled async failure in {}", method,
				ex);
	}

}

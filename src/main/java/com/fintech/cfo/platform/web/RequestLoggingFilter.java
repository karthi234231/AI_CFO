package com.fintech.cfo.platform.web;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Emits exactly one structured operational log entry per HTTP request.
 *
 * <p>Runs as a servlet filter, so it observes every request at the edge of the
 * application — before routing, before security, before any controller — and it
 * observes them again on the way out. That position is what makes it the right
 * place to measure a request as a whole: a controller-level log would miss time
 * spent in filters and would produce nothing at all for a request rejected by
 * security.
 *
 * <p><b>Filter order.</b> {@code @Order(HIGHEST_PRECEDENCE + 1)} places it
 * immediately after the highest-priority filter, which is
 * {@code CorrelationIdFilter}. That is a hard requirement rather than a
 * preference: this filter reads the correlation ID out of the logging
 * {@link MDC}, so the correlation filter must already have populated it. Running
 * second means every entry this filter writes is joinable with the rest of the
 * request's logs, and the measured duration includes the correlation filter's
 * own work, which is negligible but consistent.
 *
 * <p>Extends {@link OncePerRequestFilter} so the body runs once per request even
 * when the servlet container forwards the request internally (for example to an
 * error page). Without that guarantee a single user action could produce
 * duplicate, contradictory log lines.
 *
 * <p><b>Privacy boundary.</b> Authorization headers, cookies, bodies, uploaded
 * file contents, contract text and customer financial payloads are never logged.
 * Only the method, URI, status, duration and correlation ID are recorded. This
 * filter sits on the request path of every endpoint in the system, so anything it
 * logged would end up in log aggregation for the whole organisation; the
 * deliberate omission of payloads is what keeps that store free of customer
 * financial data.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestLoggingFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

	/**
	 * Duration at which an otherwise-successful request is escalated from INFO to
	 * WARN.
	 *
	 * <p>A latency budget, not a hard failure. Crossing it does not mean anything
	 * is broken, but it does mean the request would be visible to a user as a
	 * delay, so it is surfaced at WARN where alerting can pick it up without
	 * filling the log with errors.
	 */
	private static final long SLOW_REQUEST_THRESHOLD_MS = 2_000L;

	/**
	 * Times the request and logs the outcome once the chain has fully unwound.
	 *
	 * <p>Two deliberate choices here:
	 *
	 * <ul>
	 * <li><b>{@link System#nanoTime()} rather than {@code currentTimeMillis}.</b>
	 * A monotonic clock. Wall-clock time can jump backwards when the host is
	 * resynchronised with an NTP server, which would yield a negative duration
	 * and corrupt latency reporting.</li>
	 * <li><b>Logging in {@code finally}.</b> The entry is written whether the
	 * request succeeded or threw. A filter that only logged on the success path
	 * would go silent exactly when something has gone wrong, and
	 * {@code GlobalExceptionHandler} converts a thrown exception into a handled
	 * response, so by this point the status code is the only reliable evidence of
	 * what happened.</li>
	 * </ul>
	 *
	 * <p>{@code response.getStatus()} is read here rather than passed in, because
	 * the status is not final until the whole chain has run: an exception handled
	 * downstream will have overwritten the default 200 by this point.
	 */
	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		long start = System.nanoTime();
		try {
			// Hands control to the next filter, eventually the DispatcherServlet.
			// This call blocks for the duration of the whole request.
			filterChain.doFilter(request, response);
		}
		finally {
			// Convert the elapsed nanoseconds to milliseconds for a human-readable log.
			long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
			int status = response.getStatus();
			// Read the correlation ID that CorrelationIdFilter put in the MDC earlier.
			String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
			logEntry(request, status, durationMs, correlationId);
		}
	}

	/**
	 * Writes the single log entry at a severity chosen from the outcome.
	 *
	 * <p>The three-way split exists so that operational signal is legible without
	 * dashboards. An operator can filter WARN and above to see every request that
	 * was slow or unsuccessful, and filter ERROR to see only genuine server-side
	 * faults. Logging everything at one level would force that triage to be done
	 * by eye over the whole stream.
	 *
	 * <p>The message format string is declared once and reused across all three
	 * calls because the logged fields must be identical regardless of severity —
	 * otherwise a query that matches on {@code correlationId} would miss entries
	 * purely because of the level they were written at. {@code {}} placeholders
	 * are filled by SLF4J from the trailing arguments, so no string concatenation
	 * happens unless the level is actually enabled.
	 *
	 * <p>Condition order matters and is cheapest-first: a 5xx is always an error
	 * even if it was also slow; a slow or 4xx request is a warning; anything else
	 * is routine.
	 */
	private void logEntry(HttpServletRequest request, int status, long durationMs, String correlationId) {
		String message = "method={} uri={} status={} durationMs={} correlationId={}";
		if (status >= 500) {
			// Server-side fault: the request could not be served as designed.
			log.error(message, request.getMethod(), request.getRequestURI(), status, durationMs, correlationId);
		}
		else if (durationMs >= SLOW_REQUEST_THRESHOLD_MS || status >= 400) {
			// Either too slow to be invisible to the user, or rejected (4xx/5xx-free).
			log.warn(message, request.getMethod(), request.getRequestURI(), status, durationMs, correlationId);
		}
		else {
			// Successful and within budget: the overwhelming majority of traffic.
			log.info(message, request.getMethod(), request.getRequestURI(), status, durationMs, correlationId);
		}
	}

}

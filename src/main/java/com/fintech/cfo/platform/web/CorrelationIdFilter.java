package com.fintech.cfo.platform.web;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Creates and propagates a single request correlation ID across the HTTP
 * lifecycle.
 *
 * <p>Observability only: this ID must never influence tenant authorization or
 * user identity.
 *
 * <p><b>What it solves.</b> A single user action fans out across many log lines:
 * a filter, a security check, a service, a repository, a batch job, and often
 * several application instances. Without a shared identifier those lines are
 * unrelated strings in an aggregated log, and a user reporting "this number was
 * wrong" cannot be connected to what the server did. This filter mints one ID per
 * request and publishes it three ways, so the same value can be quoted by the
 * user, read by the handler layer, and stamped onto every log line automatically.
 *
 * <p><b>Filter order is load-bearing.</b> {@code @Order(HIGHEST_PRECEDENCE)} makes
 * this the very first filter, ahead of {@code RequestLoggingFilter} (which reads
 * the ID it puts in the {@link MDC}), ahead of Spring Security, and ahead of
 * routing. It has to run first for two independent reasons: the security filters
 * log under this ID, so a rejected request is still traceable, and the ID must
 * exist before anything can fail, or the failure itself would be untraceable.
 *
 * <p><b>Three channels, three audiences.</b> The response header lets the client
 * quote the ID. The request attribute lets in-process code —
 * {@code GlobalExceptionHandler} reads it — recover the ID without depending on
 * the MDC. The MDC lets every logging framework call include it with no plumbing.
 * All three are populated from one value, so they cannot disagree.
 *
 * <p><b>Trust boundary.</b> An inbound {@code X-Correlation-ID} is honoured, but
 * only after validation, because the header is attacker-controlled and this value
 * reaches log files. The allow-list and length cap in
 * {@link #resolveCorrelationId} exist so a caller cannot inject newlines or
 * control characters into log output, forge another tenant's ID to confuse an
 * investigation, or push an unbounded string into every log line of the request.
 * A value that fails validation is discarded in favour of a fresh UUID rather
 * than rejected: a malformed observability header should not fail a legitimate
 * business request.
 *
 * <p>Extends {@link OncePerRequestFilter} so an internal forward cannot mint a
 * second ID for what the user experiences as one action.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

	/** Inbound and outbound header carrying the ID. */
	public static final String HEADER_NAME = "X-Correlation-ID";

	/** MDC key that the logging pattern reads to stamp the ID onto every line. */
	public static final String MDC_KEY = "correlationId";

	/**
	 * Request-attribute key. Namespaced with the class name so it cannot collide
	 * with an attribute set by Spring or any other library, and public because
	 * {@code GlobalExceptionHandler} reads it.
	 */
	public static final String REQUEST_ATTRIBUTE = CorrelationIdFilter.class.getName() + ".correlationId";

	/**
	 * Cap on an accepted inbound ID.
	 *
	 * <p>Generous enough for a UUID, a trace ID or a service-generated token, and
	 * small enough that a caller cannot inflate every log line of a request.
	 */
	private static final int MAX_LENGTH = 64;

	/**
	 * The only characters an inbound ID may contain.
	 *
	 * <p>Letters, digits, dot, underscore and hyphen. Everything else is refused —
	 * notably CR and LF, whose whole purpose in a header value is to let a caller
	 * forge additional log lines, plus spaces and angle brackets that could break
	 * log parsing or downstream markup. Anchored at both ends, so the whole value
	 * must match and a partially-valid string such as {@code "abc\nGET /admin"} is
	 * rejected rather than trimmed down to a plausible-looking prefix.
	 */
	private static final Pattern ALLOWED = Pattern.compile("^[A-Za-z0-9._-]+$");

	/**
	 * Establishes the ID on all three channels, then runs the rest of the chain.
	 *
	 * <p>{@code response.setHeader} runs before the chain so the ID is on the
	 * response even if a later filter or the handler throws — otherwise the
	 * failure case, the one that most needs an ID, would be the one case missing
	 * it. The same reasoning applies to placing the MDC entry before
	 * {@code doFilter}.
	 *
	 * <p>{@link MDC#remove} in {@code finally} is essential rather than tidy. The
	 * MDC is a {@link ThreadLocal}, and servlet containers reuse request threads
	 * from a pool. Without the removal, the next request served by this thread
	 * would inherit the previous request's ID and log every one of its lines under
	 * it — silently corrupting the audit trail this class exists to produce. The
	 * removal must be in {@code finally} so an exception unwinding the chain does
	 * not skip it.
	 */
	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		// Adopt the caller's ID if it is valid, otherwise mint one.
		String correlationId = resolveCorrelationId(request);
		// Channel 1: in-process access, used by the exception handler.
		request.setAttribute(REQUEST_ATTRIBUTE, correlationId);
		// Channel 2: outbound, so the caller can quote this ID in a support request.
		response.setHeader(HEADER_NAME, correlationId);
		// Channel 3: the MDC, which the logging pattern renders onto every line.
		MDC.put(MDC_KEY, correlationId);
		try {
			filterChain.doFilter(request, response);
		}
		finally {
			// Clear the thread-local; see the note above on thread reuse.
			MDC.remove(MDC_KEY);
		}
	}

	/**
	 * Returns a validated inbound ID, or a freshly generated one.
	 *
	 * <p>Three checks must all pass for a caller's value to be adopted:
	 * {@link StringUtils#hasText} rejects null, empty and whitespace-only values,
	 * since an empty ID is worse than none at all — it would group unrelated
	 * requests together. Trimming then handles a value that arrived padded, which
	 * proxies and some gateways do. Only then are the length cap and the
	 * character allow-list applied.
	 *
	 * <p>Fall-through to {@link UUID#randomUUID()} covers every rejection case
	 * uniformly. Generating rather than reusing means a hostile or broken client
	 * degrades to a unique ID instead of degrading the observability of the whole
	 * system for everyone.
	 */
	private String resolveCorrelationId(HttpServletRequest request) {
		String incoming = request.getHeader(HEADER_NAME);
		if (StringUtils.hasText(incoming)) {
			String candidate = incoming.trim();
			// Both conditions must hold; the cap protects storage, the pattern protects log integrity.
			if (candidate.length() <= MAX_LENGTH && ALLOWED.matcher(candidate).matches()) {
				return candidate;
			}
		}
		// Random rather than sequential or timestamp-based: the ID is not a secret,
		// but it must not be guessable enough to let one tenant imply another's.
		return UUID.randomUUID().toString();
	}

}

package com.fintech.cfo.platform.idempotency;

import java.io.IOException;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import com.fintech.cfo.shared.exception.ConflictException;
import com.fintech.cfo.shared.security.SecurityContext;
import com.fintech.cfo.shared.security.SecurityPrincipal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Applies idempotency to unsafe HTTP methods that carry an
 * {@code Idempotency-Key} header.
 *
 * <h2>Why the response is buffered</h2>
 * A completed claim must be replayable verbatim, so the handler's response is
 * captured with {@link ContentCachingResponseWrapper} rather than streamed
 * straight to the socket. Buffering is only enabled for requests that opted in
 * with an idempotency key; every other request passes through untouched.
 *
 * <h2>Scope and limits</h2>
 * The filter fingerprints the request <em>line</em> (method, URI, query
 * string). It deliberately does not read the request body, because doing so
 * would consume the input stream the handler still needs. Handlers that require
 * body-level deduplication must fingerprint their payload themselves with
 * {@code HashUtils} and store the result on the domain record - this is what
 * ingestion does with its file checksum.
 *
 * <p>This mechanism prevents duplicate <em>requests</em>. It is not the
 * calculation run pinning or the ingestion checksum, which prevent duplicate
 * <em>work</em>.
 */
@Component
public class IdempotencyFilter extends OncePerRequestFilter {

	/**
	 * The key header, also echoed on the response so a client can correlate a
	 * replay with the original without having retained the key itself.
	 */
	public static final String HEADER = "Idempotency-Key";

	/**
	 * Cap on an accepted key, matching the column width in
	 * {@code idempotency_records}.
	 *
	 * <p>Validating against the same bound the schema enforces turns a would-be
	 * database truncation error into a clean rejection at the edge, before any state
	 * is created for the key.
	 */
	private static final int MAX_KEY_LENGTH = 255;

	private final IdempotencyService idempotencyService;

	/** Injected rather than looked up, so the filter is testable in isolation. */
	public IdempotencyFilter(IdempotencyService idempotencyService) {
		this.idempotencyService = idempotencyService;
	}

	/**
	 * Opt-in gate: only unsafe methods carrying the header are handled.
	 *
	 * <p>Overriding the hook rather than branching inside {@code doFilterInternal}
	 * means a request that does not apply is never wrapped, never touches the
	 * database and never has its response buffered — the overwhelming majority of
	 * traffic pays nothing for this feature.
	 *
	 * <p>The method test is the HTTP definition of safe: GET, HEAD, OPTIONS and
	 * TRACE are specified not to change server state, so a duplicate of one is
	 * harmless and needs no key. Everything else — POST, PUT, PATCH, DELETE — is
	 * assumed to be unsafe, which is the right default: a method this code does not
	 * anticipate is treated as state-changing rather than silently exempted.
	 *
	 * <p>Requiring the header rather than generating one means idempotency is
	 * always the caller's explicit choice, and a client that omits it gets ordinary
	 * at-most-once behaviour rather than a key it never agreed to.
	 */
	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String method = request.getMethod();
		boolean safe = "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)
				|| "TRACE".equals(method);
		// Safe method, or no key supplied: nothing to deduplicate.
		return safe || request.getHeader(HEADER) == null;
	}

	/**
	 * Runs the claim/decide/complete cycle around the handler.
	 *
	 * <p>Three outcomes, and the ordering between them is the whole design:
	 *
	 * <ol>
	 * <li><b>Replay</b> — a completed record exists for this key and fingerprint.
	 * The stored status and body are written back verbatim and the chain is not
	 * invoked at all, which is the entire point: the original work does not happen
	 * twice. The {@code Idempotency-Replayed} header lets a client tell a replay
	 * from a fresh execution without inspecting the body.</li>
	 * <li><b>In progress</b> — a concurrent request holds the claim. This is
	 * raised as a {@link ConflictException} so {@code GlobalExceptionHandler}
	 * renders it in the same error envelope as everything else, and answered with
	 * 409 so the client knows to retry rather than to change the request.</li>
	 * <li><b>Claimed</b> — this request owns the work and proceeds.</li>
	 * </ol>
	 *
	 * <p><b>Why the response is buffered only here.</b>
	 * {@link ContentCachingResponseWrapper} keeps the handler's output in memory
	 * instead of streaming it to the client, so the bytes can be stored against the
	 * key and replayed later. Because this path is only reached for requests that
	 * supplied a key, the buffering cost is paid solely by callers who asked for
	 * replayability.
	 *
	 * <p><b>Why {@code abandon} on failure.</b> A claim that is never completed
	 * would block that key permanently, so every exit path must release it: on an
	 * exception here, and on any 4xx/5xx response below. Only a successful response
	 * is recorded as replayable, because a stored error body would pin a transient
	 * failure to a key forever and the client's retry could never succeed.
	 *
	 * <p><b>Why completion precedes {@code copyBodyToResponse}.</b> The record is
	 * written first so that a retry arriving in the window between this handler
	 * returning and the client receiving the response can already be served the
	 * completed result. Reversing the two would leave a window in which a duplicate
	 * request is told "still in progress" for work that had in fact just finished.
	 *
	 * <p>{@code copyBodyToResponse} must run on every path that buffered, and it
	 * runs last so the client sees the handler's real output rather than the cached
	 * copy.
	 */
	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		// Validate the key before creating any state for it.
		String key = normalizeKey(request.getHeader(HEADER));
		if (key == null) {
			response.sendError(HttpStatus.BAD_REQUEST.value(), "Idempotency-Key header is invalid");
			return;
		}

		// The fingerprint identifies the request line. The body is deliberately not
		// read here: doing so would consume the stream the handler still needs.
		// String.valueOf on the query string covers a null query as the text "null".
		String fingerprintSource = request.getMethod() + " " + request.getRequestURI() + "?"
				+ String.valueOf(request.getQueryString());

		// Scoped by tenant, so one organization's key can never replay another's response.
		IdempotencyService.Claim claim = this.idempotencyService.claim(key, fingerprintSource, currentOrganizationId());

		if (claim instanceof IdempotencyService.Claim.Replay replay) {
			// Echo the key so the client can match this response to its request.
			response.setHeader(HEADER, key);
			// Distinguish a replay from a fresh execution for the client's benefit.
			response.setHeader("Idempotency-Replayed", "true");
			// Status and body are optional: a record that failed before producing a
			// response has neither, and the default 200 stands.
			if (replay.responseStatus() != null) {
				response.setStatus(replay.responseStatus().intValue());
			}
			if (replay.responseBody() != null) {
				response.setContentType("application/json");
				response.getWriter().write(replay.responseBody());
			}
			// The chain is intentionally not invoked: the work must not run twice.
			return;
		}

		if (claim instanceof IdempotencyService.Claim.InProgress) {
			// Rendered by GlobalExceptionHandler so the error shape is identical
			// to every other failure in the API.
			throw new ConflictException("A request with this Idempotency-Key is still in progress");
		}

		// Buffer the response so it can be stored and replayed.
		ContentCachingResponseWrapper cached = new ContentCachingResponseWrapper(response);
		try {
			chain.doFilter(request, cached);
		}
		catch (Exception ex) {
			// The handler blew up, so no response was produced. Release the key
			// rather than leaving it claimed forever.
			this.idempotencyService.abandon(key);
			// Re-throw so normal exception translation applies; the client must see
			// the real failure, not a synthesised one.
			throw ex;
		}

		// The handler returned normally, so the status is now final.
		int status = cached.getStatus();
		if (status < 400) {
			// Store before releasing the body, so a retry racing this response
			// can already replay it.
			// UTF-8 is stated rather than platform-default, so a stored body is
			// byte-identical to what the original client received.
			this.idempotencyService.complete(key, status, new String(cached.getContentAsByteArray(),
					java.nio.charset.StandardCharsets.UTF_8));
		}
		else {
			// A client or server error is not a result worth pinning to the key;
			// releasing it lets a corrected retry use the same key.
			this.idempotencyService.abandon(key);
		}
		// Finally flush the buffered body to the real response.
		cached.copyBodyToResponse();
	}

	/**
	 * Validates and trims the raw header.
	 *
	 * <p>Null return means "do not proceed" and is distinct from an empty string,
	 * which would otherwise be a valid-looking key that every caller shares.
	 * Trimming tolerates a padded value from a proxy; the emptiness and length
	 * checks reject a key that is absent or too long to store.
	 */
	private static String normalizeKey(String rawHeader) {
		if (rawHeader == null) {
			return null;
		}
		String trimmed = rawHeader.trim();
		if (trimmed.isEmpty() || trimmed.length() > MAX_KEY_LENGTH) {
			return null;
		}
		return trimmed;
	}

	/**
	 * The current caller's tenant, for scoping the key.
	 *
	 * <p>Read from {@link SecurityContext} rather than from a request parameter,
	 * so a client cannot nominate the tenant whose stored response it receives.
	 * Returns null when unauthenticated, which the service treats as "no tenant
	 * scope" rather than defaulting to some organisation.
	 */
	private static UUID currentOrganizationId() {
		SecurityPrincipal principal = SecurityContext.currentPrincipal();
		return principal == null ? null : principal.organizationUuid();
	}

}
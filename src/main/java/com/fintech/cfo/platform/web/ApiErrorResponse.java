package com.fintech.cfo.platform.web;

import java.time.Instant;
import java.util.Map;

/**
 * Stable external error representation returned by every REST endpoint.
 *
 * <p>Clients must branch on {@code code}, never on {@code detail}. Stack
 * traces, SQL, internal class names, credentials, raw uploaded data and AI
 * provider internals must never appear here.
 *
 * @param timestamp    time the API generated the error
 * @param status       HTTP status code
 * @param code         stable machine-readable application error code
 * @param title        short error category
 * @param detail       sanitized human-readable explanation
 * @param path         request URI
 * @param correlationId request correlation identifier
 * @param fieldErrors  validation failures keyed by request field
 */
public record ApiErrorResponse(
		Instant timestamp,
		int status,
		String code,
		String title,
		String detail,
		String path,
		String correlationId,
		Map<String, String> fieldErrors) {

	public ApiErrorResponse {
		fieldErrors = fieldErrors == null ? Map.of() : Map.copyOf(fieldErrors);
	}

}

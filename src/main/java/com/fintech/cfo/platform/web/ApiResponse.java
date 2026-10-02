package com.fintech.cfo.platform.web;

import java.time.Instant;

/**
 * Standard success-response contract for REST APIs.
 *
 * <p>HTTP representation only — domain and application services return domain
 * results or DTOs, never this wrapper.
 *
 * @param data payload
 * @param meta response metadata
 * @param <T>  payload type
 */
public record ApiResponse<T>(T data, ApiMeta meta) {

	/**
	 * @param data          payload
	 * @param correlationId correlation ID from the current request, echoed rather
	 *                      than generated here so the body and the
	 *                      {@code X-Correlation-ID} header always agree
	 * @return the wrapped success response
	 */
	public static <T> ApiResponse<T> success(T data, String correlationId) {
		return new ApiResponse<>(data, new ApiMeta(Instant.now(), correlationId));
	}

	/**
	 * Response metadata. The correlation ID is supplied by the caller and
	 * never regenerated here.
	 *
	 * @param timestamp     response creation time
	 * @param correlationId request correlation identifier
	 */
	public record ApiMeta(Instant timestamp, String correlationId) {
	}

}

package com.fintech.cfo.platform.web;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.fintech.cfo.shared.exception.AccessDeniedException;
import com.fintech.cfo.shared.exception.BusinessRuleException;
import com.fintech.cfo.shared.exception.ConflictException;
import com.fintech.cfo.shared.exception.DomainException;
import com.fintech.cfo.shared.exception.NotFoundException;
import com.fintech.cfo.shared.exception.ValidationException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

/**
 * Single REST exception-translation layer.
 *
 * <p>Every exception raised anywhere in a request is funnelled into exactly one
 * handler here, which then decides the HTTP status, the stable machine-readable
 * {@code code}, a human title and a safe detail message. Centralising this is what
 * makes the API predictable: a client never sees a 500 with a stack trace, and two
 * different services cannot invent two different shapes for "not found".
 *
 * <p><b>Why {@code @RestControllerAdvice} and not per-controller handlers:</b> the
 * advice is registered once for all controllers, so a service that throws
 * {@link NotFoundException} maps identically whether it was reached through the
 * invoice endpoint or the opportunity endpoint. It is also registered as advice
 * rather than a controller, so it is not routable itself.
 *
 * <p><b>Handler resolution order.</b> When an exception escapes a handler method,
 * Spring looks for the most specific {@code @ExceptionHandler} whose declared
 * exception type is assignable from the thrown one. That is why
 * {@code BusinessRuleException} and {@link DomainException} have their own methods
 * ahead of the {@code Exception.class} catch-all: without that ordering they
 * would be swallowed by the catch-all and reported as 500. The catch-all is the
 * safety net that guarantees a response is always produced.
 *
 * <p><b>Never leak internals.</b> No handler returns {@code ex.getMessage()} for an
 * unexpected exception, and the catch-all logs the stack trace but replies with a
 * fixed generic string. Exception messages routinely embed SQL, file paths and
 * internal identifiers, so echoing them to an untrusted client is an information
 * disclosure. Known domain exceptions are the exception to this rule: their
 * messages are written deliberately for clients and are safe to return.
 *
 * <p><b>Correlation ID.</b> Every response carries the correlation ID that
 * {@code CorrelationIdFilter} placed on the request, so a user reporting a failure
 * can quote it and the logs can be found. {@code build} reads it from the request
 * attribute set by that filter.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	/**
	 * Bean-validation failure on a {@code @Valid} request body or query object.
	 *
	 * <p>{@code MethodArgumentNotValidException} is raised before the controller
	 * method is entered, so the business logic never sees the invalid input. This
	 * handler flattens the {@code BindingResult} into a field-to-message map via
	 * {@link #convertFieldErrors} so the client can highlight individual inputs
	 * rather than parse prose. Deliberate {@code putIfAbsent}: the first message
	 * for a field wins, keeping the response stable when several constraints fail
	 * on the same property.
	 */
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiErrorResponse> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpServletRequest request) {
		return build(HttpStatus.BAD_REQUEST, ValidationException.CODE, "Validation failed",
				"One or more request fields are invalid.", request, convertFieldErrors(ex));
	}

	/**
	 * Bean-validation failure on a directly constrained method parameter, e.g.
	 * {@code @RequestParam @Min(1) int page}.
	 *
	 * <p>Raised by the argument resolver rather than the binder, so there is no
	 * {@code BindingResult} to read and no field mapping. The message is safe to
	 * return because it is composed from the constraint metadata the developer
	 * declared, not from user-supplied content.
	 */
	@ExceptionHandler(ConstraintViolationException.class)
	public ResponseEntity<ApiErrorResponse> handleConstraintViolation(ConstraintViolationException ex,
			HttpServletRequest request) {
		return build(HttpStatus.BAD_REQUEST, ValidationException.CODE, "Validation failed", ex.getMessage(), request,
				Map.of());
	}

	/**
	 * Body present but not parseable as the expected JSON.
	 *
	 * <p>Reported as a client error, not a server fault: the caller sent something
	 * this endpoint cannot interpret. The underlying Jackson message is suppressed
	 * because it quotes the offending payload and class names, which helps an
	 * attacker map the application's internals.
	 */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ApiErrorResponse> handleUnreadable(HttpMessageNotReadableException ex,
			HttpServletRequest request) {
		return build(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Malformed request",
				"The request body could not be parsed.", request, Map.of());
	}

	/**
	 * A path variable or query parameter that cannot be converted to the target
	 * type, or a required parameter that is absent.
	 *
	 * <p>Both are grouped under one handler because they mean the same thing to a
	 * client: the request does not match the contract of this endpoint. Messages
	 * are echoed here; they are generated by Spring from the parameter name and
	 * target type only.
	 */
	@ExceptionHandler({ MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class })
	public ResponseEntity<ApiErrorResponse> handleBadRequestParameter(Exception ex, HttpServletRequest request) {
		return build(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Malformed request", ex.getMessage(), request,
				Map.of());
	}

	/**
	 * A requested resource does not exist, or the caller may not know that it
	 * does. 404 rather than 403 in that second case: distinguishing them would let
	 * a caller enumerate records belonging to another tenant.
	 */
	@ExceptionHandler(NotFoundException.class)
	public ResponseEntity<ApiErrorResponse> handleNotFound(NotFoundException ex, HttpServletRequest request) {
		return build(HttpStatus.NOT_FOUND, NotFoundException.CODE, "Resource not found", ex.getMessage(), request,
				Map.of());
	}

	/**
	 * The request is well-formed but conflicts with current state, e.g. a duplicate
	 * idempotency key or an illegal lifecycle transition. 409 distinguishes
	 * "retry later" from "fix the request", which a client needs in order to decide
	 * whether to back off or to change the payload.
	 */
	@ExceptionHandler(ConflictException.class)
	public ResponseEntity<ApiErrorResponse> handleConflict(ConflictException ex, HttpServletRequest request) {
		return build(HttpStatus.CONFLICT, ConflictException.CODE, "Resource conflict", ex.getMessage(), request,
				Map.of());
	}

	/**
	 * The caller is authenticated but lacks the permission for this operation.
	 * 403, not 401: re-authenticating would not help, so prompting for credentials
	 * again is misleading.
	 */
	@ExceptionHandler(AccessDeniedException.class)
	public ResponseEntity<ApiErrorResponse> handleAccessDenied(AccessDeniedException ex,
			HttpServletRequest request) {
		return build(HttpStatus.FORBIDDEN, AccessDeniedException.CODE, "Access denied", ex.getMessage(), request,
				Map.of());
	}

	/**
	 * Explicitly raised domain validation, as opposed to the automatic bean
	 * validation above. Distinct code so a client can tell "the framework rejected
	 * this" from "our rule rejected this".
	 */
	@ExceptionHandler(ValidationException.class)
	public ResponseEntity<ApiErrorResponse> handleValidation(ValidationException ex, HttpServletRequest request) {
		return build(HttpStatus.BAD_REQUEST, ValidationException.CODE, "Validation failed", ex.getMessage(), request,
				Map.of());
	}

	/**
	 * The request passed structural validation but breaks a business rule, e.g. an
	 * action recorded against an opportunity that has not been validated.
	 *
	 * <p>422 rather than 400: the payload is well-formed and the client is not at
	 * fault for the field values, but the operation is not permitted in this
	 * state. 400 would wrongly invite the client to resend edited data.
	 */
	@ExceptionHandler(BusinessRuleException.class)
	public ResponseEntity<ApiErrorResponse> handleBusinessRule(BusinessRuleException ex,
			HttpServletRequest request) {
		return build(HttpStatus.UNPROCESSABLE_ENTITY, BusinessRuleException.CODE, "Business rule violation",
				ex.getMessage(), request, Map.of());
	}

	/**
	 * Any other deliberate domain failure. Uses the exception's own code so new
	 * domain exceptions map to a distinct, stable client-visible code without
	 * needing a new handler here.
	 */
	@ExceptionHandler(DomainException.class)
	public ResponseEntity<ApiErrorResponse> handleDomain(DomainException ex, HttpServletRequest request) {
		return build(HttpStatus.BAD_REQUEST, ex.getCode(), "Domain error", ex.getMessage(), request, Map.of());
	}

	/**
	 * A precondition helper in the shared layer rejected an argument. Mapped to
	 * the same code as other validation failures because to a client it is the
	 * same class of problem: input that cannot be accepted as given.
	 */
	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<ApiErrorResponse> handleIllegalArgument(IllegalArgumentException ex,
			HttpServletRequest request) {
		return build(HttpStatus.BAD_REQUEST, ValidationException.CODE, "Validation failed", ex.getMessage(), request,
				Map.of());
	}

	/**
	 * Last-resort handler for anything not matched above, including framework
	 * exceptions and genuine defects.
	 *
	 * <p>The ordering here is the important part and the reason the two assertions
	 * differ: the full exception, stack trace included, is passed to
	 * {@link Logger} for the operator, while the response carries only a fixed
	 * generic string. The operator gets what they need to debug; the caller gets
	 * nothing to exploit. {@code correlationId}, method and path are logged
	 * alongside so the entry can be located from the ID the client was shown.
	 */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
		log.error("Unhandled exception correlationId={} method={} path={}", getCorrelationId(request),
				request.getMethod(), request.getRequestURI(), ex);
		return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal server error",
				"An unexpected error occurred.", request, Map.of());
	}

	/**
	 * Assembles the single response body shape used for every error.
	 *
	 * <p>Funnelling all handlers through one builder is what guarantees the error
	 * envelope is identical regardless of which exception occurred: timestamp,
	 * numeric status, stable code, title, detail, path, correlation ID and
	 * optional per-field errors. It also guarantees the correlation ID is attached
	 * in every case, including ones a future handler might forget.
	 *
	 * <p>{@code ResponseEntity.status(...).body(...)} sets both the status line and
	 * the entity; nothing is written to the response body directly.
	 */
	private ResponseEntity<ApiErrorResponse> build(HttpStatus status, String code, String title, String detail,
			HttpServletRequest request, Map<String, String> fieldErrors) {
		ApiErrorResponse body = new ApiErrorResponse(Instant.now(), status.value(), code, title, detail,
				request.getRequestURI(), getCorrelationId(request), fieldErrors);
		return ResponseEntity.status(status).body(body);
	}

	/**
	 * Reads the correlation ID that {@code CorrelationIdFilter} stored as a request
	 * attribute.
	 *
	 * <p>Null-tolerant by design: the attribute is absent if the filter did not run
	 * (unit tests, or a non-HTTP invocation), and a missing correlation ID must
	 * never turn into a second failure while already handling one.
	 */
	private String getCorrelationId(HttpServletRequest request) {
		Object attribute = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
		return attribute != null ? attribute.toString() : null;
	}

	/**
	 * Flattens a {@code BindingResult} into a field-to-message map.
	 *
	 * <p>{@code LinkedHashMap} rather than {@code HashMap} to preserve the
	 * declaration order of the constraints, so the same invalid request always
	 * produces a byte-identical response and a diff of two responses is
	 * meaningful.
	 *
	 * <p>Field errors and global errors (class-level constraints such as
	 * {@code @Valid} on a nested object) share the map. {@code putIfAbsent} keeps
	 * the first message for a given name, and the field pass runs first so a
	 * field-specific message always wins over a class-level one.
	 */
	private Map<String, String> convertFieldErrors(MethodArgumentNotValidException ex) {
		Map<String, String> errors = new LinkedHashMap<>();
		for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
			errors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
		}
		ex.getBindingResult().getGlobalErrors()
				.forEach(error -> errors.putIfAbsent(error.getObjectName(), error.getDefaultMessage()));
		return errors;
	}

}

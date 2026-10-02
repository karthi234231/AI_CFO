package com.fintech.cfo.shared.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Strongly typed user identifier.
 *
 * <p>Immutable by design: as a record it is inherently thread-safe and cannot
 * be mutated after construction, so it is safe to carry through pooled-thread
 * pipelines and to cache for the lifetime of a calculation. Strong typing
 * prevents a UserId from being confused with an OrganizationId or TenantId
 * at compile time — the only defence against a key being used in the wrong
 * tenant context.
 */
/**
 * @param value underlying UUID
 * @throws NullPointerException if {@code value} is null
 */
public record UserId(UUID value) implements Serializable {

	private static final long serialVersionUID = 1L;

	public UserId {
		Objects.requireNonNull(value, "user id must not be null");
	}

	/** @return a freshly generated random user identifier */
	public static UserId newId() {
		return new UserId(UUID.randomUUID());
	}

	/**
	 * @param value UUID text, as it arrives from a claim or request path
	 * @return the parsed identifier
	 * @throws NullPointerException     if {@code value} is null
	 * @throws IllegalArgumentException if the text is not a UUID
	 */
	public static UserId fromString(String value) {
		Objects.requireNonNull(value, "user id must not be null");
		return new UserId(UUID.fromString(value.trim()));
	}

}

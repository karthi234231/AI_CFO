package com.fintech.cfo.shared.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Strongly typed tenant identifier.
 *
 * <p>Immutable by design: as a record it is inherently thread-safe and cannot
 * be mutated after construction, which matters because it is read from
 * pooled threads throughout the financial calculation path. A mutable
 * identifier could let a tenant boundary be silently retuned mid-calculation,
 * corrupting a result that must be reproducible (§4 determinism).
 *
 * <p>Security note: this is an identifier only. It is never an authorization
 * decision; tenant access is enforced separately against authenticated identity
 * and membership.
 */
/**
 * @param value underlying UUID
 * @throws NullPointerException if {@code value} is null
 */
public record TenantId(UUID value) implements Serializable {

	private static final long serialVersionUID = 1L;

	public TenantId {
		Objects.requireNonNull(value, "tenant id must not be null");
	}

	/**
	 * @return a freshly generated random tenant identifier, for provisioning
	 */
	public static TenantId newId() {
		return new TenantId(UUID.randomUUID());
	}

	/**
	 * @param value UUID text, as it arrives from a header or path variable
	 * @return the parsed identifier
	 * @throws NullPointerException     if {@code value} is null
	 * @throws IllegalArgumentException if the text is not a UUID
	 */
	public static TenantId fromString(String value) {
		Objects.requireNonNull(value, "tenant id must not be null");
		// Trimmed because a header value is frequently carried with stray
		// whitespace, and UUID.fromString would reject it.
		return new TenantId(UUID.fromString(value.trim()));
	}

}

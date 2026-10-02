package com.fintech.cfo.shared.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Strongly typed organization identifier. Acts as the tenant boundary for all
 * enterprise financial data.
 *
 * <p>Record rather than a hand-written wrapper because the whole type is one
 * value with no behaviour beyond construction: the compiler-generated
 * {@code equals}, {@code hashCode} and {@code toString} are exactly as correct as
 * hand-written ones and cannot drift from the accessor names.
 */
/**
 * @param value underlying UUID
 * @throws NullPointerException if {@code value} is null
 */
public record OrganizationId(UUID value) implements Serializable {

	private static final long serialVersionUID = 1L;

	public OrganizationId {
		Objects.requireNonNull(value, "organization id must not be null");
	}

	/**
	 * @return a freshly generated random organization identifier, for
	 *         tenant provisioning
	 */
	public static OrganizationId newId() {
		return new OrganizationId(UUID.randomUUID());
	}

	/**
	 * @param value UUID text, as it arrives from a token claim or header
	 * @return the parsed identifier
	 * @throws NullPointerException     if {@code value} is null
	 * @throws IllegalArgumentException if the text is not a UUID
	 */
	public static OrganizationId fromString(String value) {
		Objects.requireNonNull(value, "organization id must not be null");
		return new OrganizationId(UUID.fromString(value.trim()));
	}

}

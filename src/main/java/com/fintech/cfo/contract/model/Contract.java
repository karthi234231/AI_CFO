package com.fintech.cfo.contract.model;

import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.fintech.cfo.contract.enums.ContractStatus;
import com.fintech.cfo.shared.domain.CurrencyCode;
import com.fintech.cfo.shared.domain.OrganizationId;
import com.fintech.cfo.shared.exception.ValidationException;

/**
 * Immutable mirror of the V5 {@code contracts} row.
 *
 * <p>A value type, not a JPA entity. Field order and names follow
 * {@code V5__create_contracts.sql} exactly, and {@code ck_contracts_range} is
 * enforced here so an invalid window cannot be constructed in memory either.
 *
 * <p>Use {@link #effectiveWindow()} for logic and the raw accessors for storage
 * concerns. The window is derived on demand rather than cached, which keeps
 * {@code equals} purely value-based on the stored columns.
 *
 * @param customerId nullable in V5: a contract may be with the tenant as a whole
 * @param signedAt nullable in V5; a contract can be loaded before signature
 * @param documentReference a reference to the contract document, never its
 * contents
 */
public record Contract(
		UUID id,
		OrganizationId organizationId,
		@Nullable UUID customerId,
		String contractNumber,
		@Nullable String title,
		ContractStatus status,
		CurrencyCode currency,
		LocalDate effectiveFrom,
		@Nullable LocalDate effectiveTo,
		@Nullable Instant signedAt,
		@Nullable String documentReference,
		long version) implements Serializable {

	/**
	 * Width of {@code contracts.contract_number} in V5.
	 */
	public static final int MAX_CONTRACT_NUMBER_LENGTH = 120;

	/**
	 * Width of {@code contracts.title} in V5.
	 */
	public static final int MAX_TITLE_LENGTH = 500;

	public Contract {
		Objects.requireNonNull(id, "id must not be null");
		Objects.requireNonNull(organizationId, "organizationId must not be null");
		contractNumber = requireText(contractNumber, "contractNumber", MAX_CONTRACT_NUMBER_LENGTH);
		title = requireOptionalText(title, "title", MAX_TITLE_LENGTH);
		Objects.requireNonNull(status, "status must not be null");
		Objects.requireNonNull(currency, "currency must not be null");
		Objects.requireNonNull(effectiveFrom, "effectiveFrom must not be null");
		if (effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) {
			throw new ValidationException("effectiveTo must not be before effectiveFrom");
		}
		if (version < 0) {
			throw new ValidationException("version must not be negative");
		}
	}

	/**
	 * The clause type, currency and open-ended window, without the fields a test
	 * or a controlled data feed rarely varies.
	 */
	public static Contract of(UUID id, OrganizationId organizationId, String contractNumber, ContractStatus status,
			CurrencyCode currency, LocalDate effectiveFrom) {
		return new Contract(id, organizationId, null, contractNumber, null, status, currency, effectiveFrom, null, null,
				null, 0L);
	}

	public EffectiveWindow effectiveWindow() {
		return EffectiveWindow.of(this.effectiveFrom, this.effectiveTo);
	}

	/**
	 * Whether the contract's own effective window covers the date. Says nothing
	 * about {@link ContractStatus}: both must hold before terms may be used.
	 */
	public boolean isInForceOn(LocalDate date) {
		return effectiveWindow().contains(date);
	}

	/**
	 * Whether this contract may supply commercial terms on a date. The single
	 * predicate callers should use before pricing anything.
	 */
	public boolean canSupplyTermsOn(LocalDate date) {
		return isInForceOn(date) && this.status.suppliesTerms();
	}

	/**
	 * Contract numbers are unique per tenant ({@code ux_contracts_org_number}) and
	 * are the only human-facing identifier safe to quote in an error message.
	 */
	public String canonical() {
		return CanonicalText.join(this.id, this.organizationId, this.customerId, this.contractNumber, this.title,
				this.status.code(), this.currency.value(), this.effectiveFrom, this.effectiveTo, this.signedAt,
				this.documentReference, this.version);
	}

	static String requireText(String value, String field, int maxLength) {
		Objects.requireNonNull(value, field + " must not be null");
		String trimmed = value.trim();
		if (trimmed.isEmpty()) {
			throw new ValidationException(field + " must not be blank");
		}
		if (trimmed.length() > maxLength) {
			throw new ValidationException(field + " exceeds " + maxLength + " characters: " + trimmed.length());
		}
		return trimmed;
	}

	static String requireOptionalText(@Nullable String value, String field, int maxLength) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return requireText(value, field, maxLength);
	}

}

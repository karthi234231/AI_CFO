package com.fintech.cfo.contract.model;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.fintech.cfo.contract.enums.ContractTermType;
import com.fintech.cfo.shared.domain.OrganizationId;
import com.fintech.cfo.shared.exception.ValidationException;

/**
 * Immutable mirror of the V5 {@code contract_terms} row: a narrative clause,
 * versioned and effective-dated.
 *
 * <p>Carries no money. What makes it worth versioning is that it is versioned the
 * same way a price is, so a calculation can record which generation of a clause it
 * was read against rather than merely "some clause that said net 30".
 */
public record ContractTerm(
		UUID id,
		OrganizationId organizationId,
		UUID contractId,
		ContractTermType termType,
		@Nullable String description,
		EffectiveWindow effectiveWindow,
		int termVersion,
		long version) implements VersionedTerm, Serializable {

	/**
	 * V5 declares {@code term_version INT NOT NULL DEFAULT 1}, so 0 cannot occur in
	 * a stored row. Rejecting it here keeps 0 available as a sentinel meaning
	 * "no term was applied".
	 */
	public static final int MIN_TERM_VERSION = 1;

	/**
	 * Width of {@code contract_terms.description} in V5.
	 */
	public static final int MAX_DESCRIPTION_LENGTH = 2000;

	public ContractTerm {
		Objects.requireNonNull(id, "id must not be null");
		Objects.requireNonNull(organizationId, "organizationId must not be null");
		Objects.requireNonNull(contractId, "contractId must not be null");
		Objects.requireNonNull(termType, "termType must not be null");
		description = Contract.requireOptionalText(description, "description", MAX_DESCRIPTION_LENGTH);
		Objects.requireNonNull(effectiveWindow, "effectiveWindow must not be null");
		if (termVersion < MIN_TERM_VERSION) {
			throw new com.fintech.cfo.shared.exception.ValidationException(
					"termVersion must be at least " + MIN_TERM_VERSION);
		}
		if (version < 0) {
			throw new com.fintech.cfo.shared.exception.ValidationException("version must not be negative");
		}
	}

	/**
	 * Convenience for the common case, taking the raw V5 date columns.
	 */
	public ContractTerm(UUID id, OrganizationId organizationId, UUID contractId, ContractTermType termType,
			@Nullable String description, LocalDate effectiveFrom, @Nullable LocalDate effectiveTo, int termVersion) {
		this(id, organizationId, contractId, termType, description, EffectiveWindow.of(effectiveFrom, effectiveTo),
				termVersion, 0L);
	}

	public LocalDate effectiveFrom() {
		return this.effectiveWindow.effectiveFrom();
	}

	@Override
	public String canonical() {
		return CanonicalText.join(this.id, this.organizationId, this.contractId, this.termType.code(),
				CanonicalText.text(this.description), this.effectiveWindow.canonical(), this.termVersion, this.version);
	}

}

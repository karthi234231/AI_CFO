package com.fintech.cfo.contract.dto;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.fintech.cfo.contract.model.ContractTerm;

/**
 * Wire representation of a narrative contract clause.
 *
 * <p>{@code termVersion} is on the wire because the whole point of versioning a
 * clause is that a reader can tell which generation a stored calculation used. The
 * window is exposed as the two raw dates plus {@code openEnded} so a client does
 * not have to infer the third from a null.
 *
 * @param inForceOnAsOfDate whether this clause covers the date the response is
 * being served for, null when no date is in question
 */
public record ContractTermResponse(
		UUID id,
		UUID organizationId,
		UUID contractId,
		String termType,
		String description,
		LocalDate effectiveFrom,
		LocalDate effectiveTo,
		boolean openEnded,
		int termVersion,
		long version,
		@Nullable Boolean inForceOnAsOfDate) implements Serializable {

	public static ContractTermResponse from(ContractTerm term) {
		return from(term, null);
	}

	public static ContractTermResponse from(ContractTerm term, @Nullable LocalDate asOfDate) {
		return new ContractTermResponse(term.id(), term.organizationId().value(), term.contractId(),
				term.termType().code(), term.description(), term.effectiveWindow().effectiveFrom(),
				term.effectiveWindow().effectiveTo(), term.effectiveWindow().isOpenEnded(), term.termVersion(),
				term.version(), asOfDate == null ? null : term.effectiveWindow().contains(asOfDate));
	}

	/**
	 * Whether this clause covers a date, on the same inclusive boundary rule the
	 * resolver uses.
	 */
	public boolean covers(LocalDate date) {
		Objects.requireNonNull(date, "date must not be null");
		return this.effectiveFrom.compareTo(date) <= 0
				&& (this.effectiveTo == null || this.effectiveTo.compareTo(date) >= 0);
	}

}
package com.fintech.cfo.contract.extraction;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.fintech.cfo.contract.enums.ContractTermType;

/**
 * A clause candidate pulled out of a document, before it has been accepted as a
 * term.
 *
 * <p>Kept separate from {@code ContractTerm} on purpose. Extracted text is a
 * proposal: it may be ambiguous, may carry no dates at all, and may be a
 * misreading. {@link ExtractedContractTerm} can hold a missing description or a
 * missing window because "we could not read this" and "this says nothing" have to
 * be distinguishable; a {@code ContractTerm} cannot, because both of those would
 * mean a stored row that lies about itself.
 *
 * <p>Nothing here is trusted. {@link ContractTermExtractionService} reports
 * {@link #confidence()} and requires the author to confirm it, so a guess never
 * becomes a price.
 *
 * @param clauseType what kind of clause was recognised
 * @param description the clause text as read, null when nothing usable was found
 * @param effectiveFrom start of the window, null when the document gave no start
 * @param effectiveTo end of the window, null when the document gave no end or the
 * clause reads as open-ended
 * @param confidence how sure the extractor is, from 0 to 100
 */
public record ExtractedContractTerm(
		UUID contractId,
		ContractTermType clauseType,
		@Nullable String description,
		@Nullable LocalDate effectiveFrom,
		@Nullable LocalDate effectiveTo,
		int confidence) implements Serializable {

	public ExtractedContractTerm {
		Objects.requireNonNull(contractId, "contractId must not be null");
		Objects.requireNonNull(clauseType, "clauseType must not be null");
		// Bounded range rather than an enum: the extractor is one implementation of
		// the port, and a later one may express confidence differently. The range is
		// what every consumer actually relies on.
		if (confidence < 0 || confidence > 100) {
			throw new IllegalArgumentException("confidence must be between 0 and 100: " + confidence);
		}
		// effectiveFrom and effectiveTo are left unchecked here on purpose. A reversed
		// pair is a misreading the reader is allowed to produce - it becomes a
		// proposal a human corrects - and this type is not the place where a window
		// becomes enforceable. ContractTerm is.
	}

	/**
	 * Whether the clause carries enough to become a term. A window is not optional
	 * here: a clause with no dates cannot be resolved as of any date, and admitting
	 * one would invite someone to guess a start.
	 */
	public boolean isUsable() {
		return this.description != null && !this.description.isBlank() && this.effectiveFrom != null;
	}

	/**
	 * Whether the author would need to look at this before trusting it. Below 100
	 * means at least one part was inferred rather than quoted.
	 */
	public boolean needsReview() {
		return this.confidence < 100;
	}

	public Optional<LocalDate> closedOn() {
		return Optional.ofNullable(this.effectiveTo);
	}

	/**
	 * Canonical form for {@link #groupKey()} and for reporting; deliberately not a
	 * checksum input, because this is an unverified proposal.
	 */
	public String groupKey() {
		return this.contractId + "|" + this.clauseType.code();
	}

	public static List<ExtractedContractTerm> of(ExtractedContractTerm... terms) {
		return List.of(terms);
	}

}
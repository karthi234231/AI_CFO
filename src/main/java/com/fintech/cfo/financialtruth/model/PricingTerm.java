package com.fintech.cfo.financialtruth.model;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.fintech.cfo.financialtruth.enums.PricingType;
import com.fintech.cfo.shared.domain.CurrencyCode;
import com.fintech.cfo.shared.domain.VersionedValue;
import com.fintech.cfo.shared.exception.BusinessRuleException;
import com.fintech.cfo.shared.exception.ValidationException;
import com.fintech.cfo.shared.validation.Preconditions;

/**
 * A versioned contract pricing term: the authority for what one product should
 * have cost on a given business date.
 *
 * <p>Carries its own {@code term_version} and effective window so a calculation
 * performed today can prove which version of the contract it evaluated. The window
 * is inclusive at both ends, matching {@code DateRange.contains} semantics and the
 * {@code effective_from <= effective_to} checks in {@code V5__create_contracts.sql}.
 *
 * @param productKey product or SKU this price applies to; {@code null} means the
 *                   contract-wide default price for the customer
 */
public record PricingTerm(
		String productKey,
		String termId,
		PricingType pricingType,
		BigDecimal unitPrice,
		BigDecimal minimumUnitPrice,
		BigDecimal maximumUnitPrice,
		CurrencyCode currency,
		LocalDate effectiveFrom,
		LocalDate effectiveTo,
		int termVersion) {

	public PricingTerm {
		termId = com.fintech.cfo.shared.validation.Preconditions.requireText(termId, "termId");
		if (pricingType == null) {
			throw new ValidationException("pricingType must not be null");
		}
		if (currency == null) {
			throw new ValidationException("currency must not be null");
		}
		if (effectiveFrom == null) {
			throw new ValidationException("effectiveFrom must not be null");
		}
		// An open-ended window is represented by a null effectiveTo, which isEffectiveOn
		// treats as "still in force". A backwards window is rejected here rather than
		// silently matching nothing.
		if (effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) {
			throw new ValidationException("effectiveTo must not be before effectiveFrom for term " + termId);
		}
		if (termVersion <= 0) {
			throw new ValidationException("termVersion must be greater than 0 for term " + termId);
		}
	}

	/**
	 * Builds a term with no declared bounds, which is the shape every contract price
	 * actually uses.
	 *
	 * <p>Minimum and maximum are null rather than defaulting to the price itself,
	 * because {@link #assertWithinDeclaredBounds()} treats an absent bound as no
	 * constraint and a defaulted one as a permanent lock.
	 */
	public static PricingTerm fixedUnitPrice(String termKey, String termId, BigDecimal unitPrice, CurrencyCode currency,
			LocalDate effectiveFrom, LocalDate effectiveTo, int termVersion) {
		return new PricingTerm(termKey, termId, PricingType.FIXED_UNIT_PRICE, unitPrice, null, null, currency,
				effectiveFrom, effectiveTo, termVersion);
	}

	/**
	 * Whether this term was in force on the supplied business date.
	 *
	 * <p>Deliberately evaluated against an explicit date passed in by the caller.
	 * Nothing here reads a system clock, which is what allows a re-run months later
	 * to select the same term the original run selected.
	 */
	public boolean isEffectiveOn(LocalDate asOf) {
		if (asOf == null) {
			throw new ValidationException("asOf date must not be null");
		}
		return !asOf.isBefore(this.effectiveFrom) && (this.effectiveTo == null || !asOf.isAfter(this.effectiveTo));
	}

	/**
	 * Wraps this term so it can be stored and compared as a versioned value. The
	 * effective instant is the start of the term's first day in UTC, so it is a
	 * property of the contract rather than of when this code happened to run.
	 */
	public VersionedValue<PricingTerm> asVersionedValue() {
		return new VersionedValue<>(this, this.termVersion,
				this.effectiveFrom.atStartOfDay(java.time.ZoneOffset.UTC).toInstant());
	}

	/** The proof recorded on a result so a historical figure can be re-derived. */
	public TermEvaluation toEvaluation() {
		return new TermEvaluation(this.termId, this.pricingType.name(), this.termVersion, this.effectiveFrom,
				this.effectiveTo);
	}

	/**
	 * Rejects a contract price that sits outside the bounds the contract itself
	 * declares. A term that contradicts itself cannot be used as an authority.
	 */
	public void assertWithinDeclaredBounds() {
		if (this.unitPrice == null) {
			// No price to check. The caller raises its own, more specific message.
			return;
		}
		// compareTo, not equals: only the ordering matters, and neither bound carries a
		// scale this module would want to impose.
		if (this.minimumUnitPrice != null && this.unitPrice.compareTo(this.minimumUnitPrice) < 0) {
			throw new BusinessRuleException("pricing term " + this.termId + " sets unit price "
					+ this.unitPrice.toPlainString() + " below its own minimum " + this.minimumUnitPrice.toPlainString());
		}
		if (this.maximumUnitPrice != null && this.unitPrice.compareTo(this.maximumUnitPrice) > 0) {
			throw new BusinessRuleException("pricing term " + this.termId + " sets unit price "
					+ this.unitPrice.toPlainString() + " above its own maximum " + this.maximumUnitPrice.toPlainString());
		}
	}

	

}
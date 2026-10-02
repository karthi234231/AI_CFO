package com.fintech.cfo.financialtruth.rules;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

import com.fintech.cfo.financialtruth.enums.DiscountType;
import com.fintech.cfo.financialtruth.model.DiscountTerm;
import com.fintech.cfo.financialtruth.model.InvoiceLineInput;
import com.fintech.cfo.financialtruth.model.PricingTerm;
import com.fintech.cfo.shared.domain.CurrencyCode;
import com.fintech.cfo.shared.exception.ValidationException;

/**
 * Everything one rule is allowed to see when it is asked about one line.
 *
 * <p>Deliberately closed. A rule cannot reach the rest of the invoice, cannot query
 * the contract database and cannot read a clock. It sees a line, an explicit as-of
 * date, the terms that were in force on that date, and nothing else. That restriction
 * is what makes the rules deterministic and cheap enough to test exhaustively.
 *
 * <p>{@link #pricingTerm()} may be {@code null}: it means the contract said nothing
 * about this product on the as-of date. A rule must treat that as a missing
 * authority, never as a price of zero.
 *
 * <p>{@link #discountTerms()} arrives already filtered to the as-of date and already
 * in the fixed application order, so a rule cannot invent a different stacking
 * sequence from the one recorded on the input.
 */
public final class RuleContext {

	private final InvoiceLineInput line;
	private final LocalDate asOfDate;
	private final CurrencyCode currency;
	private final PricingTerm pricingTerm;
	private final List<DiscountTerm> discountTerms;

	private RuleContext(Builder builder) {
		// Objects.requireNonNull for the structural fields, ValidationException for the
		// ones with a business reason. A null as-of date is not a programming mistake,
		// it is an attempt to evaluate terms against no date at all.
		this.line = Objects.requireNonNull(builder.line, "line must not be null");
		if (builder.asOfDate == null) {
			throw new ValidationException("asOfDate must not be null; rules never read a system clock");
		}
		this.asOfDate = builder.asOfDate;
		this.currency = Objects.requireNonNull(builder.currency, "currency must not be null");
		// Deliberately nullable: a null pricing term means the contract said nothing.
		this.pricingTerm = builder.pricingTerm;
		// Copied, so a rule cannot mutate or reorder the caller's snapshot mid-run.
		this.discountTerms = builder.discountTerms == null ? List.of() : List.copyOf(builder.discountTerms);
	}

	public static Builder builder() {
		return new Builder();
	}

	public InvoiceLineInput line() {
		return this.line;
	}

	/** The business date the terms are being evaluated against. Never the system date. */
	public LocalDate asOfDate() {
		return this.asOfDate;
	}

	public CurrencyCode currency() {
		return this.currency;
	}

	/** The contract price in force, or {@code null} when there is none. */
	public PricingTerm pricingTerm() {
		return this.pricingTerm;
	}

	/** Discounts in force on the as-of date, in fixed application order. */
	public List<DiscountTerm> discountTerms() {
		return this.discountTerms;
	}

	/**
	 * The first discount of a given kind, or {@code null} if none is in force.
	 *
	 * <p>"First" means first in the fixed application order already present in
	 * {@link #discountTerms()}, so this is as deterministic as the list itself.
	 */
	public DiscountTerm firstDiscountOfType(DiscountType discountType) {
		return this.discountTerms.stream().filter(term -> term.discountType() == discountType).findFirst().orElse(null);
	}

	/** Mutable builder; the resulting context is immutable and safe to share. */
	public static final class Builder {

		private InvoiceLineInput line;
		private LocalDate asOfDate;
		private CurrencyCode currency;
		private PricingTerm pricingTerm;
		private List<DiscountTerm> discountTerms;

		public Builder line(InvoiceLineInput value) {
			this.line = value;
			return this;
		}

		public Builder asOfDate(LocalDate value) {
			this.asOfDate = value;
			return this;
		}

		public Builder currency(CurrencyCode value) {
			this.currency = value;
			return this;
		}

		public Builder pricingTerm(PricingTerm value) {
			this.pricingTerm = value;
			return this;
		}

		public Builder discountTerms(List<DiscountTerm> value) {
			this.discountTerms = value;
			return this;
		}

		public RuleContext build() {
			return new RuleContext(this);
		}

	}

}
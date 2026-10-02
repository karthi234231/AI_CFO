package com.fintech.cfo.opportunity.enums;

import java.util.List;

import com.fintech.cfo.shared.exception.ValidationException;

/**
 * What kind of economic opportunity the record describes
 * ({@code opportunities.opportunity_type VARCHAR(48)}).
 *
 * <p>A sealed interface of records rather than an enum, so every {@code switch} over
 * it is a compile-time exhaustiveness check: adding a variant and forgetting to
 * handle it stops the build instead of quietly falling through to a default.
 * {@link CodedEnum} supplies the stable {@code VARCHAR} value and the strict
 * {@code fromCode} resolution V8 stores.
 *
 * <p>The type is part of the stored record, so it must stay stable: a report written
 * against {@code COMBINED_VARIANCE} must mean the same thing years later. New
 * categories are added as new variants; existing ones are never repurposed.
 *
 * <p>The three variants are exactly the deviation classes the deterministic
 * financial-truth engine can currently prove, and each one is reachable from
 * {@code OpportunityDetectionService}. There is deliberately no {@code OTHER}
 * variant. A catch-all bucket is the easiest value to add and the most expensive to
 * keep: it silently absorbs every leakage class discovered later, so a portfolio
 * report grouped by type would keep reporting a growing share of money under a
 * heading that says nothing about it. A genuinely new category must be named here,
 * which costs one line and buys a report that can be trusted.
 */
public sealed interface OpportunityType extends CodedEnum
		permits OpportunityType.PricingVariance, OpportunityType.DiscountVariance, OpportunityType.CombinedVariance {

	/** Width of {@code opportunities.opportunity_type} in V8. */
	int MAX_CODE_LENGTH = 48;

	/**
	 * Every variant, in a fixed order that never depends on declaration order.
	 *
	 * <p>A method rather than a constant: a static field holding nested
	 * {@code INSTANCE} references cannot initialise, because the nested classes are
	 * themselves subclasses of the interface being initialised.
	 */
	static List<OpportunityType> all() {
		return List.of(PricingVariance.INSTANCE, DiscountVariance.INSTANCE, CombinedVariance.INSTANCE);
	}

	/**
	 * Resolves a stored {@code opportunity_type} value.
	 *
	 * @throws ValidationException if the value is blank or unknown
	 */
	static OpportunityType fromCode(String code) {
		String normalized = CodedEnum.normalise(code, "OpportunityType");
		for (OpportunityType candidate : all()) {
			if (candidate.code().equals(normalized)) {
				candidate.requireColumnWidth(MAX_CODE_LENGTH);
				return candidate;
			}
		}
		throw new ValidationException("unknown opportunity type code: " + code);
	}

	/**
	 * Whether this class of deviation is measured on the payable amount itself or on
	 * one of its components.
	 *
	 * <p>An exhaustive {@code switch}, so a new variant must state which figure it
	 * reports before the compiler will let the module build. Getting this wrong would
	 * let a component deviation be added into a total of payable deviations and
	 * double-count the same money.
	 *
	 * @return true for {@link PricingVariance} and {@link DiscountVariance}, false
	 *         for the authoritative {@link CombinedVariance}
	 */
	default boolean isComponentOfPayableDeviation() {
		return switch (this) {
			case PricingVariance pricing -> true;
			case DiscountVariance discount -> true;
			case CombinedVariance combined -> false;
		};
	}

	/**
	 * The invoiced unit price differs from the contracted unit price. Disjoint from
	 * {@link DiscountVariance}.
	 */
	record PricingVariance() implements OpportunityType {

		public static final PricingVariance INSTANCE = new PricingVariance();

		@Override
		public String code() {
			return "PRICING_VARIANCE";
		}

	}

	/**
	 * The discount granted differs from the discount the contract entitled. Measured
	 * on the discount amount itself, so it must never be added to a
	 * {@link PricingVariance} figure without netting the two first.
	 */
	record DiscountVariance() implements OpportunityType {

		public static final DiscountVariance INSTANCE = new DiscountVariance();

		@Override
		public String code() {
			return "DISCOUNT_VARIANCE";
		}

	}

	/**
	 * The net deviation of the whole payable amount. This is the authoritative figure
	 * an auditor reports; the component variants exist to explain it. A portfolio
	 * total may only sum records of this type, which is what
	 * {@link #isComponentOfPayableDeviation()} exists to make checkable.
	 */
	record CombinedVariance() implements OpportunityType {

		public static final CombinedVariance INSTANCE = new CombinedVariance();

		@Override
		public String code() {
			return "COMBINED_VARIANCE";
		}

	}

}
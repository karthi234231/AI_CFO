package com.fintech.cfo.financialtruth.enums;

import java.util.List;

import com.fintech.cfo.shared.exception.ValidationException;

/**
 * What kind of deviation a variance describes
 * ({@code calculation_results.variance_type VARCHAR(32)}).
 *
 * <p>A sealed interface of records rather than an enum, so every {@code switch}
 * over it is a compile-time exhaustiveness check: adding a variant and forgetting
 * to handle it stops the build instead of quietly falling through to a wrong
 * figure. {@link CodedEnum} supplies the stable {@code VARCHAR} value and the
 * strict {@code fromCode} resolution V6 stores.
 *
 * <p>The type is part of the stored result, so it must stay stable: a report
 * written against {@code PRICING} must mean the same thing years later. New
 * categories are added as new variants; existing ones are never repurposed.
 *
 * <p>There is deliberately no {@code NONE} variant. An earlier draft declared one
 * for "the amounts matched exactly", but nothing in the engine ever produced it -
 * a rule that matches the contract emits a zero {@link Pricing} variance, and
 * "the rule could not be evaluated" is already carried losslessly by
 * {@link RuleStatus}. A documented constant that no code path can reach is worse
 * than no constant: it reads as a contract that is not enforced.
 */
public sealed interface VarianceType extends CodedEnum
		permits VarianceType.Pricing, VarianceType.Discount, VarianceType.Combined {

	/** Width of {@code calculation_results.variance_type} in V6. */
	int MAX_CODE_LENGTH = 32;

	/**
	 * Every variant, in a fixed order that never depends on declaration order.
	 *
	 * <p>A method rather than a constant: see {@link RuleStatus#all()} for why a
	 * static field holding nested {@code INSTANCE} references cannot initialise.
	 */
	static List<VarianceType> all() {
		return List.of(Pricing.INSTANCE, Discount.INSTANCE, Combined.INSTANCE);
	}

	/**
	 * Resolves a stored {@code variance_type} value.
	 *
	 * @throws ValidationException if the value is blank or unknown
	 */
	static VarianceType fromCode(String code) {
		String normalized = CodedEnum.normalise(code, "VarianceType");
		for (VarianceType candidate : all()) {
			if (candidate.code().equals(normalized)) {
				candidate.requireColumnWidth(MAX_CODE_LENGTH);
				return candidate;
			}
		}
		throw new ValidationException("unknown variance type code: " + code);
	}

	/**
	 * Whether a variance of this type may carry an amount other than zero.
	 *
	 * <p>An exhaustive {@code switch}, so a new variant must state whether it is a
	 * real measurement or a placeholder before the compiler will let the module
	 * build.
	 */
	default boolean admitsNonZeroAmount() {
		return switch (this) {
			case Pricing pricing -> true;
			case Discount discount -> true;
			case Combined combined -> true;
		};
	}

	/**
	 * The invoiced unit price differs from the contracted unit price, measured on
	 * the gross amount (quantity multiplied by unit price). Disjoint from
	 * {@link Discount}.
	 */
	record Pricing() implements VarianceType {

		public static final Pricing INSTANCE = new Pricing();

		@Override
		public String code() {
			return "PRICING";
		}

	}

	/**
	 * The discount granted differs from the discount the contract entitled,
	 * measured on the discount amount itself. A positive value means the customer
	 * received <em>more</em> discount than contracted.
	 */
	record Discount() implements VarianceType {

		public static final Discount INSTANCE = new Discount();

		@Override
		public String code() {
			return "DISCOUNT";
		}

	}

	/**
	 * The net deviation of the whole payable amount, composed of the {@link Pricing}
	 * and {@link Discount} components; tax is contractual pass-through and
	 * contributes zero. This is the authoritative figure an auditor reports, and the
	 * component variants exist only to explain it.
	 */
	record Combined() implements VarianceType {

		public static final Combined INSTANCE = new Combined();

		@Override
		public String code() {
			return "COMBINED";
		}

	}

}
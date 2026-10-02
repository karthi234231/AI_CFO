package com.fintech.cfo.contract.enums;

import java.math.BigDecimal;
import java.util.List;

import com.fintech.cfo.shared.exception.ValidationException;

/**
 * Basis of a discount ({@code discount_terms.discount_type VARCHAR(32)}).
 *
 * <p>V5 keeps both kinds of discount in a single {@code discount_value
 * NUMERIC(20,6)} column, so the unit of that number is decided by this type
 * alone: {@code PERCENTAGE} reads it as a percentage of the amount being
 * discounted, {@code FIXED_AMOUNT} reads it as an absolute amount in the term's
 * currency. The column is not self-describing, and anything that reads it without
 * the type is wrong by a factor of one hundred.
 */
public sealed interface DiscountType extends CodedEnum
		permits DiscountType.Percentage, DiscountType.FixedAmount {

	/**
	 * Width of {@code discount_terms.discount_type} in V5.
	 */
	int MAX_CODE_LENGTH = 32;

	/**
	 * Every variant.
	 *
	 * <p>A method rather than a constant. Initialising a nested record initialises
	 * the interface it implements, because the interface declares default methods -
	 * so a static field here would read {@code INSTANCE} fields that are not assigned
	 * yet and die with a {@code NullPointerException} from {@code List.of} whenever a
	 * variant constant is the first thing this class is asked for. A method body runs
	 * at call time, when the variants exist.
	 */
	static List<DiscountType> all() {
		return List.of(Percentage.INSTANCE, FixedAmount.INSTANCE);
	}

	/**
	 * Resolves a stored {@code discount_terms.discount_type} value.
	 *
	 * @throws ValidationException if the value is blank or unknown
	 */
	static DiscountType fromCode(String code) {
		String normalized = CodedEnum.normalise(code, "DiscountType");
		for (DiscountType candidate : all()) {
			if (candidate.code().equals(normalized)) {
				candidate.requireColumnWidth(MAX_CODE_LENGTH);
				return candidate;
			}
		}
		throw new ValidationException("unknown discount type code: " + code);
	}

	/**
	 * Whether {@code discount_value} is a currency amount, which makes
	 * {@code discount_terms.currency} mandatory even though the column is
	 * nullable.
	 */
	default boolean isMonetary() {
		return switch (this) {
			case DiscountType.FixedAmount fixed -> true;
			case DiscountType.Percentage percentage -> false;
		};
	}

	/**
	 * Whether {@code discount_value} is a percentage of the discounted amount, in
	 * which case it must lie in {@code (0, 100]}.
	 */
	default boolean isPercentageValid(BigDecimal value) {
		return switch (this) {
			case DiscountType.Percentage percentage -> value.signum() > 0
					&& value.compareTo(Percentage.MAX_PERCENTAGE) <= 0;
			case DiscountType.FixedAmount fixed -> true;
		};
	}

	record Percentage() implements DiscountType {

		/**
		 * Upper bound of a meaningful percentage. Above it the term would be
		 * paying the customer instead of discounting them.
		 */
		static final BigDecimal MAX_PERCENTAGE = new BigDecimal("100");

		public static final Percentage INSTANCE = new Percentage();

		@Override
		public String code() {
			return "PERCENTAGE";
		}

	}

	record FixedAmount() implements DiscountType {

		public static final FixedAmount INSTANCE = new FixedAmount();

		@Override
		public String code() {
			return "FIXED_AMOUNT";
		}

	}

}

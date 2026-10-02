package com.fintech.cfo.contract.enums;

import java.util.List;

import com.fintech.cfo.shared.exception.ValidationException;

/**
 * Basis of a price line ({@code pricing_terms.pricing_type VARCHAR(32)}).
 *
 * <p>The type decides where the authoritative unit price comes from, which is the
 * one thing the schema cannot express: {@code unit_price} is nullable, and
 * whether that null means "this row is a bound, not a price" or "this row is
 * incomplete" depends entirely on the type.
 */
public sealed interface PricingType extends CodedEnum
		permits PricingType.FixedUnit, PricingType.Tiered, PricingType.UsageBased, PricingType.FlatFee {

	/**
	 * Width of {@code pricing_terms.pricing_type} in V5.
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
	static List<PricingType> all() {
		return List.of(FixedUnit.INSTANCE, Tiered.INSTANCE, UsageBased.INSTANCE, FlatFee.INSTANCE);
	}

	/**
	 * Resolves a stored {@code pricing_terms.pricing_type} value.
	 *
	 * @throws ValidationException if the value is blank or unknown
	 */
	static PricingType fromCode(String code) {
		String normalized = CodedEnum.normalise(code, "PricingType");
		for (PricingType candidate : all()) {
			if (candidate.code().equals(normalized)) {
				candidate.requireColumnWidth(MAX_CODE_LENGTH);
				return candidate;
			}
		}
		throw new ValidationException("unknown pricing type code: " + code);
	}

	/**
	 * Whether the term's own {@code unit_price} is the price to charge.
	 *
	 * <p>False only for {@link Tiered}, where the rate comes from a volume band
	 * the caller computes elsewhere and the term only supplies the floor and
	 * ceiling that result must respect.
	 */
	default boolean publishesItsOwnPrice() {
		return switch (this) {
			case PricingType.FixedUnit fixed -> true;
			case PricingType.UsageBased usage -> true;
			case PricingType.FlatFee flat -> true;
			case PricingType.Tiered tiered -> false;
		};
	}

	/**
	 * Whether an externally supplied candidate price is a legitimate input to
	 * resolution, rather than an attempt to override an agreed price.
	 */
	default boolean acceptsCandidatePrice() {
		return switch (this) {
			case PricingType.Tiered tiered -> true;
			case PricingType.FixedUnit fixed -> false;
			case PricingType.UsageBased usage -> false;
			case PricingType.FlatFee flat -> false;
		};
	}

	record FixedUnit() implements PricingType {

		public static final FixedUnit INSTANCE = new FixedUnit();

		@Override
		public String code() {
			return "FIXED_UNIT";
		}

	}

	/**
	 * Volume-banded. {@code price_minimum} and {@code price_maximum} are the floor
	 * and ceiling of the band and are applied to whatever the caller resolves the
	 * band to.
	 */
	record Tiered() implements PricingType {

		public static final Tiered INSTANCE = new Tiered();

		@Override
		public String code() {
			return "TIERED";
		}

	}

	record UsageBased() implements PricingType {

		public static final UsageBased INSTANCE = new UsageBased();

		@Override
		public String code() {
			return "USAGE_BASED";
		}

	}

	record FlatFee() implements PricingType {

		public static final FlatFee INSTANCE = new FlatFee();

		@Override
		public String code() {
			return "FLAT_FEE";
		}

	}

}

package com.fintech.cfo.contract.enums;

import java.util.List;

import org.jspecify.annotations.Nullable;

import com.fintech.cfo.shared.exception.ValidationException;

/**
 * Kind of machine-checkable commercial condition ({@code commercial_rules.rule_type
 * VARCHAR(48)}).
 *
 * <p>A rule's type names the single {@code parameters} key it cannot be evaluated
 * without and how that key must be read. Making both part of the type contract is
 * what stops a stored rule from being silently unevaluable: a rule with a missing
 * or unusable parameter is refused at evaluation time rather than allowed to pass
 * a transaction it was written to catch.
 *
 * <p>Every variant except {@link ExpressionThreshold} is evaluated from typed
 * parameters. {@link ExpressionThreshold} is the escape hatch for thresholds
 * whose shape no variant covers; it reads the rule's own
 * {@code commercial_rules.expression} through a constrained grammar.
 */
public sealed interface CommercialRuleType extends CodedEnum
		permits CommercialRuleType.MinimumCharge, CommercialRuleType.PriceFloor, CommercialRuleType.PriceCeiling,
		CommercialRuleType.MaximumDiscount, CommercialRuleType.PaymentTermDays, CommercialRuleType.ApprovalRequired,
		CommercialRuleType.FreeGoodsThreshold, CommercialRuleType.ExpressionThreshold {

	/**
	 * Width of {@code commercial_rules.rule_type} in V5.
	 */
	int MAX_CODE_LENGTH = 48;

	/**
	 * Every variant, in taxonomy order.
	 *
	 * <p>A method rather than a constant. Initialising a nested record initialises
	 * the interface it implements, because the interface declares default methods -
	 * so a static field here would read {@code INSTANCE} fields that are not assigned
	 * yet and die with a {@code NullPointerException} from {@code List.of} whenever a
	 * variant constant is the first thing this class is asked for. A method body runs
	 * at call time, when the variants exist.
	 */
	static List<CommercialRuleType> all() {
		return List.of(MinimumCharge.INSTANCE, PriceFloor.INSTANCE, PriceCeiling.INSTANCE, MaximumDiscount.INSTANCE,
				PaymentTermDays.INSTANCE, ApprovalRequired.INSTANCE, FreeGoodsThreshold.INSTANCE,
				ExpressionThreshold.INSTANCE);
	}

	/**
	 * How a rule type's required parameter must be read.
	 */
	sealed interface ParameterKind {

		/**
		 * Code used in the wire form and in failure messages.
		 */
		String code();

		/** An amount, pinned to the currency of the transaction being checked. */
		record Monetary() implements ParameterKind {

			public static final Monetary INSTANCE = new Monetary();

			@Override
			public String code() {
				return "MONETARY";
			}

		}

		/** A plain decimal, such as a quantity. Carries no currency. */
		record Decimal() implements ParameterKind {

			public static final Decimal INSTANCE = new Decimal();

			@Override
			public String code() {
				return "DECIMAL";
			}

		}

		/** A whole number, such as a number of days. */
		record WholeNumber() implements ParameterKind {

			public static final WholeNumber INSTANCE = new WholeNumber();

			@Override
			public String code() {
				return "WHOLE_NUMBER";
			}

		}

	}

	/**
	 * Resolves a stored {@code commercial_rules.rule_type} value.
	 *
	 * @throws ValidationException if the value is blank or unknown
	 */
	static CommercialRuleType fromCode(String code) {
		String normalized = CodedEnum.normalise(code, "CommercialRuleType");
		for (CommercialRuleType candidate : all()) {
			if (candidate.code().equals(normalized)) {
				candidate.requireColumnWidth(MAX_CODE_LENGTH);
				return candidate;
			}
		}
		throw new ValidationException("unknown commercial rule type code: " + code);
	}

	/**
	 * The {@code commercial_rules.parameters} key this variant cannot be evaluated
	 * without, or null when it reads its operands from
	 * {@code commercial_rules.expression} instead.
	 */
	@Nullable
	String requiredParameter();

	/**
	 * How {@link #requiredParameter()} must be interpreted. Only meaningful when a
	 * required parameter exists.
	 */
	@Nullable
	ParameterKind parameterKind();

	/**
	 * Whether this variant evaluates the rule's {@code expression} column.
	 *
	 * <p>True for exactly one variant. The expression is parsed by a
	 * {@code RuleExpression} restricted to a fixed grammar; no arbitrary text is
	 * ever executed.
	 */
	default boolean readsExpression() {
		return switch (this) {
			case ExpressionThreshold expression -> true;
			case MinimumCharge minimum -> false;
			case PriceFloor floor -> false;
			case PriceCeiling ceiling -> false;
			case MaximumDiscount maximum -> false;
			case PaymentTermDays days -> false;
			case ApprovalRequired approval -> false;
			case FreeGoodsThreshold freeGoods -> false;
		};
	}

	/** Gross amount must reach {@code min_amount}. */
	record MinimumCharge() implements CommercialRuleType {

		public static final MinimumCharge INSTANCE = new MinimumCharge();

		@Override
		public String code() {
			return "MINIMUM_CHARGE";
		}

		@Override
		public String requiredParameter() {
			return "min_amount";
		}

		@Override
		public ParameterKind parameterKind() {
			return ParameterKind.Monetary.INSTANCE;
		}

	}

	/** Unit price must be at least {@code min_unit_price}. */
	record PriceFloor() implements CommercialRuleType {

		public static final PriceFloor INSTANCE = new PriceFloor();

		@Override
		public String code() {
			return "PRICE_FLOOR";
		}

		@Override
		public String requiredParameter() {
			return "min_unit_price";
		}

		@Override
		public ParameterKind parameterKind() {
			return ParameterKind.Monetary.INSTANCE;
		}

	}

	/** Unit price must be at most {@code max_unit_price}. */
	record PriceCeiling() implements CommercialRuleType {

		public static final PriceCeiling INSTANCE = new PriceCeiling();

		@Override
		public String code() {
			return "PRICE_CEILING";
		}

		@Override
		public String requiredParameter() {
			return "max_unit_price";
		}

		@Override
		public ParameterKind parameterKind() {
			return ParameterKind.Monetary.INSTANCE;
		}

	}

	/** Discount granted must not exceed {@code max_discount_amount}. */
	record MaximumDiscount() implements CommercialRuleType {

		public static final MaximumDiscount INSTANCE = new MaximumDiscount();

		@Override
		public String code() {
			return "MAXIMUM_DISCOUNT";
		}

		@Override
		public String requiredParameter() {
			return "max_discount_amount";
		}

		@Override
		public ParameterKind parameterKind() {
			return ParameterKind.Monetary.INSTANCE;
		}

	}

	/** Agreed payment term must not exceed {@code days}. */
	record PaymentTermDays() implements CommercialRuleType {

		public static final PaymentTermDays INSTANCE = new PaymentTermDays();

		@Override
		public String code() {
			return "PAYMENT_TERM_DAYS";
		}

		@Override
		public String requiredParameter() {
			return "days";
		}

		@Override
		public ParameterKind parameterKind() {
			return ParameterKind.WholeNumber.INSTANCE;
		}

	}

	/** Gross amount above {@code threshold_amount} needs an extra approval. */
	record ApprovalRequired() implements CommercialRuleType {

		public static final ApprovalRequired INSTANCE = new ApprovalRequired();

		@Override
		public String code() {
			return "APPROVAL_REQUIRED";
		}

		@Override
		public String requiredParameter() {
			return "threshold_amount";
		}

		@Override
		public ParameterKind parameterKind() {
			return ParameterKind.Monetary.INSTANCE;
		}

	}

	/** Quantity must reach {@code threshold_quantity}. */
	record FreeGoodsThreshold() implements CommercialRuleType {

		public static final FreeGoodsThreshold INSTANCE = new FreeGoodsThreshold();

		@Override
		public String code() {
			return "FREE_GOODS_THRESHOLD";
		}

		@Override
		public String requiredParameter() {
			return "threshold_quantity";
		}

		@Override
		public ParameterKind parameterKind() {
			return ParameterKind.Decimal.INSTANCE;
		}

	}

	/**
	 * The threshold is computed by evaluating {@code commercial_rules.expression}
	 * against the rule's parameters. Satisfied when the gross amount does not
	 * exceed the computed threshold.
	 *
	 * <p>Exists so an unusual threshold can be authored without adding a rule
	 * type, and so that every rule type's semantics stay a compile-time
	 * exhaustiveness question rather than a runtime dispatch on a string.
	 */
	record ExpressionThreshold() implements CommercialRuleType {

		public static final ExpressionThreshold INSTANCE = new ExpressionThreshold();

		@Override
		public String code() {
			return "EXPRESSION_THRESHOLD";
		}

		@Override
		public @Nullable String requiredParameter() {
			return null;
		}

		@Override
		public @Nullable ParameterKind parameterKind() {
			return null;
		}

	}

}

package com.fintech.cfo.financialtruth.enums;

import java.util.List;

import com.fintech.cfo.shared.exception.ValidationException;

/**
 * Outcome of evaluating one rule against one line.
 *
 * <p>A sealed interface of records rather than an enum, so the {@code switch} that
 * maps an outcome to a confidence level, an impact count and a disclosure is
 * checked at compile time: a new outcome cannot be added without deciding all
 * three.
 *
 * <p>The distinction between {@link NotApplicable} and {@link IncompleteInputs} is
 * the whole point of this type. "The contract entitles nothing here" and "I could
 * not read the contract" are different findings, and collapsing either into a zero
 * variance would let an unreadable contract pass as a clean invoice.
 *
 * <p><strong>Schema note:</strong> V6 gives {@code calculation_results} no column for
 * this status - only {@code variance_type} and {@code confidence}. So the
 * distinction this type exists to protect is currently carried in the run's
 * canonical form and in {@code explanation}, not in a column of its own. That is a
 * gap in the migration, not in this module; see the module report.
 */
public sealed interface RuleStatus extends CodedEnum permits RuleStatus.Evaluated, RuleStatus.NotApplicable,
		RuleStatus.IncompleteInputs {

	/** Width this would need if V6 gains a status column; matches {@code confidence VARCHAR(16)} plus headroom. */
	int MAX_CODE_LENGTH = 32;

	/**
	 * Every variant, in a fixed order that never depends on declaration order.
	 *
	 * <p>A method rather than a constant, deliberately. Initialising a nested record
	 * initialises the interface it implements, because the interface declares default
	 * methods - so a static field here would read {@code INSTANCE} fields that are not
	 * assigned yet and die with a {@code NullPointerException} from {@code List.of}.
	 * A method body runs at call time, when the variants exist.
	 */
	static List<RuleStatus> all() {
		return List.of(Evaluated.INSTANCE, NotApplicable.INSTANCE, IncompleteInputs.INSTANCE);
	}

	/**
	 * Resolves a stored status value.
	 *
	 * @throws ValidationException if the value is blank or unknown
	 */
	static RuleStatus fromCode(String code) {
		String normalized = CodedEnum.normalise(code, "RuleStatus");
		for (RuleStatus candidate : all()) {
			if (candidate.code().equals(normalized)) {
				candidate.requireColumnWidth(MAX_CODE_LENGTH);
				return candidate;
			}
		}
		throw new ValidationException("unknown rule status code: " + code);
	}

	/**
	 * Whether this outcome carries a monetary claim.
	 *
	 * <p>True for exactly one variant. The two others exist so that "no finding" is
	 * never dressed up as "a finding of zero".
	 */
	default boolean carriesMonetaryClaim() {
		return switch (this) {
			case Evaluated evaluated -> true;
			case NotApplicable notApplicable -> false;
			case IncompleteInputs incomplete -> false;
		};
	}

	/** The rule ran and produced expected, actual and variance figures. */
	record Evaluated() implements RuleStatus {

		public static final Evaluated INSTANCE = new Evaluated();

		@Override
		public String code() {
			return "EVALUATED";
		}

	}

	/**
	 * The rule is genuinely irrelevant for this line, for example because no discount
	 * was contracted at all. No monetary claim is made.
	 */
	record NotApplicable() implements RuleStatus {

		public static final NotApplicable INSTANCE = new NotApplicable();

		@Override
		public String code() {
			return "NOT_APPLICABLE";
		}

	}

	/**
	 * The rule could not be evaluated because an input it requires was missing or
	 * unusable. No monetary claim is made, and the reason is recorded so the gap can
	 * be chased rather than guessed at.
	 */
	record IncompleteInputs() implements RuleStatus {

		public static final IncompleteInputs INSTANCE = new IncompleteInputs();

		@Override
		public String code() {
			return "INCOMPLETE_INPUTS";
		}

	}

}
package com.fintech.cfo.contract.enums;

import java.util.List;

import com.fintech.cfo.shared.exception.ValidationException;

/**
 * Nature of a narrative contract clause ({@code contract_terms.term_type
 * VARCHAR(48)}).
 *
 * <p>These clauses carry obligations rather than money. They are still resolved
 * per effective date like every other term, because a calculation has to be able
 * to prove which generation of a clause it was read against, but no clause type
 * is ever evaluated arithmetically. Monetary terms live in
 * {@code pricing_terms}, {@code discount_terms} and {@code commercial_rules}.
 */
public sealed interface ContractTermType extends CodedEnum
		permits ContractTermType.DeliveryTerms, ContractTermType.PaymentTerms, ContractTermType.ServiceLevel,
		ContractTermType.Renewal, ContractTermType.LiabilityCap, ContractTermType.TerminationNotice,
		ContractTermType.DataRetention, ContractTermType.Other {

	/**
	 * Width of {@code contract_terms.term_type} in V5.
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
	static List<ContractTermType> all() {
		return List.of(DeliveryTerms.INSTANCE, PaymentTerms.INSTANCE, ServiceLevel.INSTANCE, Renewal.INSTANCE,
				LiabilityCap.INSTANCE, TerminationNotice.INSTANCE, DataRetention.INSTANCE, Other.INSTANCE);
	}

	/**
	 * Resolves a stored {@code contract_terms.term_type} value.
	 *
	 * @throws ValidationException if the value is blank or unknown
	 */
	static ContractTermType fromCode(String code) {
		String normalized = CodedEnum.normalise(code, "ContractTermType");
		for (ContractTermType candidate : all()) {
			if (candidate.code().equals(normalized)) {
				candidate.requireColumnWidth(MAX_CODE_LENGTH);
				return candidate;
			}
		}
		throw new ValidationException("unknown contract term type code: " + code);
	}

	record DeliveryTerms() implements ContractTermType {

		public static final DeliveryTerms INSTANCE = new DeliveryTerms();

		@Override
		public String code() {
			return "DELIVERY_TERMS";
		}

	}

	record PaymentTerms() implements ContractTermType {

		public static final PaymentTerms INSTANCE = new PaymentTerms();

		@Override
		public String code() {
			return "PAYMENT_TERMS";
		}

	}

	record ServiceLevel() implements ContractTermType {

		public static final ServiceLevel INSTANCE = new ServiceLevel();

		@Override
		public String code() {
			return "SERVICE_LEVEL";
		}

	}

	record Renewal() implements ContractTermType {

		public static final Renewal INSTANCE = new Renewal();

		@Override
		public String code() {
			return "RENEWAL";
		}

	}

	record LiabilityCap() implements ContractTermType {

		public static final LiabilityCap INSTANCE = new LiabilityCap();

		@Override
		public String code() {
			return "LIABILITY_CAP";
		}

	}

	record TerminationNotice() implements ContractTermType {

		public static final TerminationNotice INSTANCE = new TerminationNotice();

		@Override
		public String code() {
			return "TERMINATION_NOTICE";
		}

	}

	record DataRetention() implements ContractTermType {

		public static final DataRetention INSTANCE = new DataRetention();

		@Override
		public String code() {
			return "DATA_RETENTION";
		}

	}

	/**
	 * Clause captured before the taxonomy settled. Kept so historical rows stay
	 * readable instead of becoming unresolvable.
	 */
	record Other() implements ContractTermType {

		public static final Other INSTANCE = new Other();

		@Override
		public String code() {
			return "OTHER";
		}

	}

}

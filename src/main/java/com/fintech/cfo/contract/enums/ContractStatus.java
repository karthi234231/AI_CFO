package com.fintech.cfo.contract.enums;

import java.util.List;

import com.fintech.cfo.shared.exception.ValidationException;

/**
 * Lifecycle of a contract ({@code contracts.status VARCHAR(32)}).
 *
 * <p>Status answers a question the effective window cannot: whether a contract
 * that is date-valid is nevertheless allowed to supply commercial terms. A draft
 * or terminated contract has terms on paper and no terms in force, and pricing an
 * invoice from one would produce a figure no one agreed to.
 */
public sealed interface ContractStatus extends CodedEnum
		permits ContractStatus.Draft, ContractStatus.PendingApproval, ContractStatus.Approved, ContractStatus.Active,
		ContractStatus.Suspended, ContractStatus.Expired, ContractStatus.Terminated, ContractStatus.Archived {

	/**
	 * Width of {@code contracts.status} in V5.
	 */
	int MAX_CODE_LENGTH = 32;

	/**
	 * Every variant, in lifecycle order.
	 *
	 * <p>A method rather than a constant. Initialising a nested record initialises
	 * the interface it implements, because the interface declares a default method -
	 * so a static field here would read {@code INSTANCE} fields that are not assigned
	 * yet and die with a {@code NullPointerException} from {@code List.of} whenever a
	 * variant constant is the first thing this class is asked for. A method body runs
	 * at call time, when the variants exist.
	 */
	static List<ContractStatus> all() {
		return List.of(Draft.INSTANCE, PendingApproval.INSTANCE, Approved.INSTANCE, Active.INSTANCE,
				Suspended.INSTANCE, Expired.INSTANCE, Terminated.INSTANCE, Archived.INSTANCE);
	}

	/**
	 * Resolves a stored {@code contracts.status} value.
	 *
	 * @throws ValidationException if the value is blank or not a known status
	 */
	static ContractStatus fromCode(String code) {
		String normalized = CodedEnum.normalise(code, "ContractStatus");
		for (ContractStatus candidate : all()) {
			if (candidate.code().equals(normalized)) {
				candidate.requireColumnWidth(MAX_CODE_LENGTH);
				return candidate;
			}
		}
		throw new ValidationException("unknown contract status code: " + code);
	}

	/**
	 * Whether commercial terms from this contract may be used at all.
	 *
	 * <p>{@code EXPIRED} is included deliberately. An expired contract's terms
	 * genuinely did apply while it was in force, and a historical calculation
	 * re-run months later must still be able to price that period. Excluding it
	 * would make past figures unreproducible the moment a contract lapses.
	 *
	 * <p>{@code SUSPENDED}, {@code TERMINATED} and the pre-approval states are
	 * excluded because no invoice may be justified by them, however well the
	 * invoice date happens to fall inside the effective window.
	 */
	default boolean suppliesTerms() {
		return switch (this) {
			case ContractStatus.Approved approved -> true;
			case ContractStatus.Active active -> true;
			case ContractStatus.Expired expired -> true;
			case ContractStatus.Draft draft -> false;
			case ContractStatus.PendingApproval pending -> false;
			case ContractStatus.Suspended suspended -> false;
			case ContractStatus.Terminated terminated -> false;
			case ContractStatus.Archived archived -> false;
		};
	}

	/**
	 * A closed set of records, not an enum, so that a new lifecycle state must be
	 * handled by every {@code switch} over this type before the build succeeds.
	 */
	record Draft() implements ContractStatus {

		public static final Draft INSTANCE = new Draft();

		@Override
		public String code() {
			return "DRAFT";
		}

	}

	record PendingApproval() implements ContractStatus {

		public static final PendingApproval INSTANCE = new PendingApproval();

		@Override
		public String code() {
			return "PENDING_APPROVAL";
		}

	}

	record Approved() implements ContractStatus {

		public static final Approved INSTANCE = new Approved();

		@Override
		public String code() {
			return "APPROVED";
		}

	}

	record Active() implements ContractStatus {

		public static final Active INSTANCE = new Active();

		@Override
		public String code() {
			return "ACTIVE";
		}

	}

	record Suspended() implements ContractStatus {

		public static final Suspended INSTANCE = new Suspended();

		@Override
		public String code() {
			return "SUSPENDED";
		}

	}

	record Expired() implements ContractStatus {

		public static final Expired INSTANCE = new Expired();

		@Override
		public String code() {
			return "EXPIRED";
		}

	}

	record Terminated() implements ContractStatus {

		public static final Terminated INSTANCE = new Terminated();

		@Override
		public String code() {
			return "TERMINATED";
		}

	}

	record Archived() implements ContractStatus {

		public static final Archived INSTANCE = new Archived();

		@Override
		public String code() {
			return "ARCHIVED";
		}

	}

}

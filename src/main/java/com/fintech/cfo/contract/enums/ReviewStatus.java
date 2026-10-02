package com.fintech.cfo.contract.enums;

import java.util.List;

import com.fintech.cfo.shared.exception.ValidationException;

/**
 * Approval state of a proposed contract term.
 *
 * <p>Phase 0 terms arrive as controlled structured data, so a term is proposed
 * first and only becomes a resolution input once a person has approved it. The
 * state lives on the proposal rather than on {@code contract_terms}, because the
 * V5 term tables have no review column; the approval decision itself is an audit
 * event.
 */
public sealed interface ReviewStatus extends CodedEnum
		permits ReviewStatus.Draft, ReviewStatus.PendingReview, ReviewStatus.InReview, ReviewStatus.Approved,
		ReviewStatus.Rejected, ReviewStatus.ChangesRequested {

	/**
	 * Width used for a review state in the audit trail. The V5 term tables have no
	 * such column, so this is the audit table's width.
	 */
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
	static List<ReviewStatus> all() {
		return List.of(Draft.INSTANCE, PendingReview.INSTANCE, InReview.INSTANCE, Approved.INSTANCE,
				Rejected.INSTANCE, ChangesRequested.INSTANCE);
	}

	/**
	 * Resolves a stored review state.
	 *
	 * @throws ValidationException if the value is blank or unknown
	 */
	static ReviewStatus fromCode(String code) {
		String normalized = CodedEnum.normalise(code, "ReviewStatus");
		for (ReviewStatus candidate : all()) {
			if (candidate.code().equals(normalized)) {
				candidate.requireColumnWidth(MAX_CODE_LENGTH);
				return candidate;
			}
		}
		throw new ValidationException("unknown review status code: " + code);
	}

	/**
	 * Whether terms reviewed in this state may be promoted to effective terms.
	 *
	 * <p>Only an explicit approval qualifies. Treating "nobody objected" as approval
	 * is how an unreviewed draft price reaches an invoice, and once it is there
	 * nothing downstream can tell it apart from an agreed one.
	 */
	default boolean isPromotable() {
		return switch (this) {
			case ReviewStatus.Approved approved -> true;
			case ReviewStatus.Draft draft -> false;
			case ReviewStatus.PendingReview pending -> false;
			case ReviewStatus.InReview inReview -> false;
			case ReviewStatus.Rejected rejected -> false;
			case ReviewStatus.ChangesRequested changes -> false;
		};
	}

	record Draft() implements ReviewStatus {

		public static final Draft INSTANCE = new Draft();

		@Override
		public String code() {
			return "DRAFT";
		}

	}

	record PendingReview() implements ReviewStatus {

		public static final PendingReview INSTANCE = new PendingReview();

		@Override
		public String code() {
			return "PENDING_REVIEW";
		}

	}

	record InReview() implements ReviewStatus {

		public static final InReview INSTANCE = new InReview();

		@Override
		public String code() {
			return "IN_REVIEW";
		}

	}

	record Approved() implements ReviewStatus {

		public static final Approved INSTANCE = new Approved();

		@Override
		public String code() {
			return "APPROVED";
		}

	}

	record Rejected() implements ReviewStatus {

		public static final Rejected INSTANCE = new Rejected();

		@Override
		public String code() {
			return "REJECTED";
		}

	}

	record ChangesRequested() implements ReviewStatus {

		public static final ChangesRequested INSTANCE = new ChangesRequested();

		@Override
		public String code() {
			return "CHANGES_REQUESTED";
		}

	}

}

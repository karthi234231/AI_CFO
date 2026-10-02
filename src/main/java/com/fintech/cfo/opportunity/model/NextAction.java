package com.fintech.cfo.opportunity.model;

import java.time.Instant;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import com.fintech.cfo.shared.exception.ValidationException;

/**
 * The next thing somebody has to do about this opportunity
 * ({@code opportunities.recommended_action VARCHAR(2000)}).
 *
 * <p>A structured value rather than a string, because two properties of the advice
 * matter and neither can be read off prose: whether a due date was attached, and
 * whether acting on it needs someone else's authority. A recommendation that quietly
 * requires an approval nobody has to grant is not a recommendation, it is a trap -
 * so the requirement is stated on the record instead of being left to the sentence.
 *
 * @param action                          what to do, in business terms
 * @param dueBy                           when it should be done by, if a date was set
 * @param requiresApprovalBeforeExecution whether acting needs a separate authorisation
 */
public record NextAction(
		String action,
		@Nullable Instant dueBy,
		boolean requiresApprovalBeforeExecution) {

	/** Width of {@code opportunities.recommended_action} in V8. */
	public static final int MAX_ACTION_LENGTH = 2000;

	public NextAction {
		// Only the prose is validated. Whether a due date or an approval requirement
		// was supplied is a fact about the recommendation, not a correctness
		// question, so both stay optional.
		action = requireText(action);
	}

	/**
	 * Whether the recommendation was given a deadline.
	 *
	 * @return true when a due date is attached
	 */
	public boolean hasDueDate() {
		return this.dueBy != null;
	}

	private static String requireText(String value) {
		// Bounded by the V8 recommended_action column: advice that does not fit is
		// advice that cannot be stored, and it would otherwise be truncated into
		// something that reads differently from what was meant.
		Objects.requireNonNull(value, "action must not be null");
		String trimmed = value.trim();
		if (trimmed.isEmpty()) {
			throw new ValidationException("nextAction.action must not be blank");
		}
		if (trimmed.length() > MAX_ACTION_LENGTH) {
			throw new ValidationException("nextAction.action must not exceed " + MAX_ACTION_LENGTH + " characters");
		}
		return trimmed;
	}

}
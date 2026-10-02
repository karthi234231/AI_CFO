package com.fintech.cfo.opportunity.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.fintech.cfo.opportunity.enums.FindingSeverity;
import com.fintech.cfo.opportunity.enums.FindingType;
import com.fintech.cfo.shared.exception.ValidationException;

/**
 * A finding as supplied by a caller, before it is attached to an opportunity.
 *
 * <p>Exists so that detection and review can describe a finding without knowing its
 * identity, its tenant, or when it happened. Those three are the module's to assign
 * in {@link OpportunityFinding#from}, and keeping them out of the caller's hands is
 * what stops a detection pass from writing rows into a tenant it was not asked about.
 *
 * @param findingType         what kind of explanation it is
 * @param severity            how much it constrains trust in the figure
 * @param title               one line stating the finding
 * @param detail              the supporting explanation
 * @param calculationResultId the result row this finding was derived from
 */
public record FindingDraft(
		FindingType findingType,
		FindingSeverity severity,
		String title,
		@Nullable String detail,
		@Nullable UUID calculationResultId) {

	public FindingDraft {
		// Deliberately shallow. The width and blank checks belong to
		// OpportunityFinding, which owns the column definitions; repeating them here
		// would give two places to change when a column width does.
		if (findingType == null) {
			throw new ValidationException("findingType must not be null");
		}
		if (severity == null) {
			throw new ValidationException("severity must not be null");
		}
		Objects.requireNonNull(title, "title must not be null");
	}

	/**
	 * The common case: a finding with no calculation result behind it, used when a
	 * reviewer or a data-quality rule raises something the engine did not calculate.
	 *
	 * @param findingType what kind of explanation it is
	 * @param severity    how much it constrains trust in the figure
	 * @param title       one line stating the finding
	 * @param detail      the supporting explanation, may be null
	 * @return a draft ready to be attached to an opportunity
	 */
	public static FindingDraft of(FindingType findingType, FindingSeverity severity, String title,
			@Nullable String detail) {
		return new FindingDraft(findingType, severity, title, detail, null);
	}

}
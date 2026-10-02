package com.fintech.cfo.opportunity.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.fintech.cfo.opportunity.enums.FindingSeverity;
import com.fintech.cfo.opportunity.enums.FindingType;
import com.fintech.cfo.shared.domain.OrganizationId;
import com.fintech.cfo.shared.exception.ValidationException;

/**
 * One explanation of why an opportunity should be believed
 * ({@code opportunity_findings} in V8).
 *
 * <p>A finding never carries an amount. It explains the composition of the record's
 * impact or limits the inputs behind it, which is why the V8 table has no money
 * column and why none was added here: a finding that could state a figure would be a
 * second, unaudited money column competing with {@code opportunities.impact_amount}.
 *
 * <p>{@code severity} is about trust, not about magnitude - see
 * {@link FindingSeverity#blocksValidation()}, which is what stops a record with an
 * unresolved high-severity finding from being marked validated.
 *
 * @param id                   row identity
 * @param organizationId       tenant boundary, never taken from a request
 * @param opportunityId        the opportunity this finding explains
 * @param findingType          what kind of explanation it is
 * @param severity             how much it constrains trust in the figure
 * @param title                one line stating the finding
 * @param detail               the supporting explanation
 * @param calculationResultId  the result row this finding was derived from
 * @param createdAt            when it was recorded
 */
public record OpportunityFinding(
		UUID id,
		OrganizationId organizationId,
		UUID opportunityId,
		FindingType findingType,
		FindingSeverity severity,
		String title,
		@Nullable String detail,
		@Nullable UUID calculationResultId,
		Instant createdAt) {

	/** Width of {@code opportunity_findings.title} in V8. */
	public static final int MAX_TITLE_LENGTH = 500;

	/** Width of {@code opportunity_findings.detail} in V8. */
	public static final int MAX_DETAIL_LENGTH = 2000;

	public OpportunityFinding {
		// Same rule as every other row in this module: identity and tenancy are
		// refused at construction, because a finding written into the wrong tenant is
		// a disclosure, not a data-quality problem.
		if (id == null) {
			throw new ValidationException("opportunity finding id must not be null");
		}
		if (organizationId == null) {
			throw new ValidationException("opportunity finding organizationId must not be null");
		}
		if (findingType == null) {
			throw new ValidationException("findingType must not be null");
		}
		if (severity == null) {
			throw new ValidationException("severity must not be null");
		}
		title = requireText(title, "title", MAX_TITLE_LENGTH);
		// Optional detail, but never blank and never longer than the column: an
		// empty explanation is worse than none, because a reader cannot tell it
		// apart from a finding whose detail was dropped in transit.
		if (detail != null) {
			detail = detail.trim();
			if (detail.isEmpty()) {
				throw new ValidationException("detail must be null or non-blank");
			}
			if (detail.length() > MAX_DETAIL_LENGTH) {
				throw new ValidationException("detail must not exceed " + MAX_DETAIL_LENGTH + " characters");
			}
		}
		if (createdAt == null) {
			throw new ValidationException("opportunity finding createdAt must not be null");
		}
	}

	/**
	 * Whether an unresolved finding of this severity prevents the record being
	 * presented as validated.
	 *
	 * @return true at {@code HIGH} or above
	 */
	public boolean blocksValidation() {
		return this.severity.blocksValidation();
	}

	/**
	 * Whether this kind of finding caps the confidence of the record's figure.
	 *
	 * @return true for data-quality and term-gap findings, false for a variance
	 *         component, which is arithmetic rather than an input limitation
	 */
	public boolean limitsConfidence() {
		return this.findingType.limitsConfidence();
	}

	private static String requireText(String value, String field, int maxLength) {
		Objects.requireNonNull(value, field + " must not be null");
		String trimmed = value.trim();
		if (trimmed.isEmpty()) {
			throw new ValidationException(field + " must not be blank");
		}
		if (trimmed.length() > maxLength) {
			throw new ValidationException(field + " must not exceed " + maxLength + " characters");
		}
		return trimmed;
	}

}
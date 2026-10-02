package com.fintech.cfo.contract.dto;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.fintech.cfo.contract.model.CommercialRule;

/**
 * Wire representation of a commercial rule.
 *
 * <p>{@code expression} is the authored wording and is served verbatim so that a
 * reviewer can read the rule as it was drafted. It is never evaluated as text by
 * anything that treats it as code: {@code EXPRESSION_THRESHOLD} parses it through a
 * fixed grammar, and if the text is outside that grammar the rule is reported as
 * unevaluable rather than interpreted.
 *
 * <p>{@code requiredParameter} and {@code parameterKind} are derived from
 * {@code ruleType} and included so that a client can show a rule author which
 * single {@code parameters} key the chosen type still needs, without having to
 * hard-code the mapping itself.
 *
 * @param parameters key-sorted, canonical form of the stored {@code parameters}
 * column, so the author's spacing does not reach a client or a checksum
 * @param inForceOnAsOfDate whether this rule covers the date the response is being
 * served for, null when no date is in question
 */
public record CommercialRuleResponse(
		UUID id,
		UUID organizationId,
		@Nullable UUID contractId,
		boolean organizationWide,
		String ruleCode,
		String ruleType,
		@Nullable String expression,
		Map<String, String> parameters,
		String canonicalParameters,
		@Nullable String requiredParameter,
		@Nullable String parameterKind,
		LocalDate effectiveFrom,
		LocalDate effectiveTo,
		boolean openEnded,
		int termVersion,
		long version,
		@Nullable Boolean inForceOnAsOfDate) implements Serializable {

	public static CommercialRuleResponse from(CommercialRule rule) {
		return from(rule, null);
	}

	public static CommercialRuleResponse from(CommercialRule rule, @Nullable LocalDate asOfDate) {
		var ruleType = rule.ruleType();
		var kind = ruleType.parameterKind();
		return new CommercialRuleResponse(rule.id(), rule.organizationId().value(), rule.contractId(),
				rule.isOrganizationWide(), rule.ruleCode(), ruleType.code(), rule.expression(), rule.parameters().values(),
				rule.parameters().canonical(), ruleType.requiredParameter(), kind == null ? null : kind.code(),
				rule.effectiveWindow().effectiveFrom(), rule.effectiveWindow().effectiveTo(),
				rule.effectiveWindow().isOpenEnded(), rule.termVersion(), rule.version(),
				asOfDate == null ? null : rule.effectiveWindow().contains(asOfDate));
	}

}
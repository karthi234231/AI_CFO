package com.fintech.cfo.contract.model;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.fintech.cfo.contract.enums.CommercialRuleType;
import com.fintech.cfo.shared.domain.OrganizationId;
import com.fintech.cfo.shared.exception.ValidationException;

/**
 * Immutable mirror of the V5 {@code commercial_rules} row: a machine-checkable
 * commercial condition, versioned and effective-dated like every other term.
 *
 * <p>{@code rule_code} is unique per tenant ({@code ux_commercial_rules_org_code})
 * while {@code contract_id} is optional, so a null {@code contract_id} means an
 * organisation-wide rule. {@link #isOrganizationWide()} makes that explicit rather
 * than leaving callers to guess.
 *
 * <p>{@code expression} is the authored wording. It is retained verbatim and is
 * never executed as text; the one variant that reads it,
 * {@link CommercialRuleType.ExpressionThreshold}, parses it into a
 * {@link RuleExpression} and evaluates only what is inside that grammar.
 *
 * @param contractId nullable in V5: an organisation-wide rule
 * @param expression nullable in V5; required for
 * {@link CommercialRuleType.ExpressionThreshold} at evaluation time
 */
public record CommercialRule(
		UUID id,
		OrganizationId organizationId,
		@Nullable UUID contractId,
		String ruleCode,
		CommercialRuleType ruleType,
		@Nullable String expression,
		CommercialRuleParameters parameters,
		EffectiveWindow effectiveWindow,
		int termVersion,
		long version) implements VersionedTerm, Serializable {

	/**
	 * Width of {@code commercial_rules.rule_code} in V5.
	 */
	public static final int MAX_RULE_CODE_LENGTH = 64;

	/**
	 * Width of {@code commercial_rules.expression} in V5.
	 */
	public static final int MAX_EXPRESSION_LENGTH = 2000;

	public CommercialRule {
		Objects.requireNonNull(id, "id must not be null");
		Objects.requireNonNull(organizationId, "organizationId must not be null");
		ruleCode = Contract.requireText(ruleCode, "ruleCode", MAX_RULE_CODE_LENGTH);
		Objects.requireNonNull(ruleType, "ruleType must not be null");
		expression = Contract.requireOptionalText(expression, "expression", MAX_EXPRESSION_LENGTH);
		Objects.requireNonNull(parameters, "parameters must not be null");
		Objects.requireNonNull(effectiveWindow, "effectiveWindow must not be null");
		if (termVersion < ContractTerm.MIN_TERM_VERSION) {
			throw new ValidationException("termVersion must be at least " + ContractTerm.MIN_TERM_VERSION);
		}
		if (version < 0) {
			throw new ValidationException("version must not be negative");
		}
	}

	/**
	 * Convenience taking the raw V5 date columns and the raw {@code parameters}
	 * column text.
	 */
	public CommercialRule(UUID id, OrganizationId organizationId, @Nullable UUID contractId, String ruleCode,
			CommercialRuleType ruleType, @Nullable String expression, @Nullable String parameters,
			LocalDate effectiveFrom, @Nullable LocalDate effectiveTo, int termVersion) {
		this(id, organizationId, contractId, ruleCode, ruleType, expression, CommercialRuleParameters.parse(parameters),
				EffectiveWindow.of(effectiveFrom, effectiveTo), termVersion, 0L);
	}

	@Override
	public LocalDate effectiveFrom() {
		return this.effectiveWindow.effectiveFrom();
	}

	/**
	 * Whether the rule applies to the whole tenant rather than to one contract.
	 */
	public boolean isOrganizationWide() {
		return this.contractId == null;
	}

	/**
	 * The expression compiled into an evaluable tree.
	 *
	 * <p>Called once per evaluation rather than cached on the record: a record is a
	 * value, and a mutable parse cache inside it would break the immutability the
	 * input checksum depends on. Parsing is a few microseconds and a rule set is
	 * small.
	 *
	 * @throws ValidationException if this variant reads the expression and the
	 * stored text is outside the supported grammar
	 */
	public RuleExpression compiledExpression() {
		// Null is refused rather than treated as an empty expression: an absent
		// expression is a misconfigured rule, and evaluating it as nothing would
		// yield a threshold of zero that fails every transaction it was meant to
		// protect.
		if (this.expression == null) {
			throw new ValidationException("rule " + this.ruleCode + " has no expression to evaluate");
		}
		// Parsed per call, never cached on the record. A mutable field would make
		// equals/hashCode depend on parse history, which is precisely the value
		// semantics the input checksum relies on.
		return RuleExpression.parse(this.expression);
	}

	@Override
	public String canonical() {
		return CanonicalText.join(this.id, this.organizationId, this.contractId, this.ruleCode, this.ruleType.code(),
				CanonicalText.text(this.expression), this.parameters.canonical(), this.effectiveWindow.canonical(),
				this.termVersion, this.version);
	}

}

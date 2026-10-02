package com.fintech.cfo.contract.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.fintech.cfo.contract.dto.CommercialEvaluationContext;
import com.fintech.cfo.contract.dto.CommercialRuleEvaluation;
import com.fintech.cfo.contract.dto.RuleEvaluationResult;
import com.fintech.cfo.contract.enums.CommercialRuleType;
import com.fintech.cfo.contract.model.CommercialRule;
import com.fintech.cfo.shared.domain.Money;
import com.fintech.cfo.shared.exception.BusinessRuleException;

/**
 * Checks a transaction against the commercial rules in force.
 *
 * <h2>Three outcomes, not two</h2>
 *
 * <p>A rule can be satisfied, breached, or <em>unable to run</em> because the
 * transaction carried none of its inputs. The third is recorded as its own state
 * and never merged into the first two. Collapsing it would be the most dangerous
 * simplification available here: "no gross amount was supplied, so the minimum
 * charge rule could not be checked" reported as "the minimum charge rule passed"
 * turns an assurance gap into a false attestation.
 *
 * <h2>A missing parameter is a failure, never a pass</h2>
 *
 * <p>Every rule type names the one {@code parameters} key it cannot be evaluated
 * without. If that key is absent or unusable, evaluation raises
 * {@link BusinessRuleException} rather than skipping the rule. A rule whose own
 * configuration is incomplete is a defect to fix, not a check to silently omit,
 * and V5 does not validate {@code parameters} contents against {@code rule_type}.
 *
 * <h2>Currency</h2>
 *
 * <p>Monetary comparisons are between amounts already in the transaction's
 * currency. A monetary input in another currency is refused rather than
 * converted: this module has no FX component, and a rule outcome computed at an
 * invented rate is not re-derivable.
 *
 * <h2>Expressions</h2>
 *
 * <p>{@link CommercialRuleType.ExpressionThreshold} reads the rule's
 * {@code expression} column through {@code RuleExpression}: a fixed arithmetic
 * grammar with named functions, bounded in length, depth and node count. There is
 * no {@code eval}, no reflection, no script engine and no dispatch on text, so a
 * stored expression cannot reach anything beyond that arithmetic. Text outside the
 * grammar is refused, not guessed at.
 *
 * <p>Threshold semantics are documented per type; for the expression variant the
 * rule is <em>satisfied when the gross amount does not exceed the computed
 * threshold</em>, which keeps every variant reading the same way: a threshold is a
 * ceiling, not a trigger.
 *
 * <p>Stateless.
 */
public final class CommercialRuleService {

	private final EffectiveTermResolver resolver;

	public CommercialRuleService(EffectiveTermResolver resolver) {
		this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
	}

	/**
	 * Evaluates a rule set that has already been narrowed to one contract and date.
	 */
	public CommercialRuleEvaluation evaluate(List<CommercialRule> rules, CommercialEvaluationContext context) {
		Objects.requireNonNull(rules, "rules must not be null");
		Objects.requireNonNull(context, "context must not be null");
		List<RuleEvaluationResult> results = new ArrayList<>(rules.size());
		for (CommercialRule rule : rules) {
			// Sequential, and in the caller's order. A rule set is small, evaluation
			// is pure, and a fixed order is what lets two runs produce an identical
			// result list that can be compared field by field.
			results.add(evaluate(rule, context));
		}
		return new CommercialRuleEvaluation(results);
	}

	/**
	 * Evaluates every rule in force for the date, in precedence order.
	 *
	 * @throws BusinessRuleException if the contract cannot supply terms on the date
	 */
	public CommercialRuleEvaluation evaluateInForce(List<CommercialRule> rules, CommercialEvaluationContext context,
			boolean contractInForceOnDate) {
		if (!contractInForceOnDate) {
			throw new BusinessRuleException(
					"the contract does not supply commercial terms on " + context.asOfDate());
		}
		return evaluate(this.resolver.inForceOn(rules, context.asOfDate()), context);
	}

	/**
	 * Evaluates one rule. Exhaustive over {@link CommercialRuleType}: adding a
	 * variant fails compilation here rather than at runtime on a stored row.
	 */
	public RuleEvaluationResult evaluate(CommercialRule rule, CommercialEvaluationContext context) {
		Objects.requireNonNull(rule, "rule must not be null");
		Objects.requireNonNull(context, "context must not be null");
		return switch (rule.ruleType()) {
			case CommercialRuleType.MinimumCharge minimum -> minimumCharge(rule, context);
			case CommercialRuleType.PriceFloor floor -> priceFloor(rule, context);
			case CommercialRuleType.PriceCeiling ceiling -> priceCeiling(rule, context);
			case CommercialRuleType.MaximumDiscount maximum -> maximumDiscount(rule, context);
			case CommercialRuleType.PaymentTermDays paymentDays -> paymentTermDays(rule, context);
			case CommercialRuleType.ApprovalRequired approval -> approvalRequired(rule, context);
			case CommercialRuleType.FreeGoodsThreshold freeGoods -> freeGoodsThreshold(rule, context);
			case CommercialRuleType.ExpressionThreshold expression -> expressionThreshold(rule, context);
		};
	}

	/**
	 * The gross amount must reach the threshold. Absent a gross amount the rule
	 * cannot run.
	 */
	private RuleEvaluationResult minimumCharge(CommercialRule rule, CommercialEvaluationContext context) {
		if (!context.hasGrossAmount()) {
			return notApplicable(rule, context, "no gross amount was supplied");
		}
		Money gross = requireCurrency(context.grossAmount(), context, rule);
		Money threshold = threshold(rule, context);
		// "Reached" is >=, so an invoice exactly at the minimum charge passes.
		// The parameter is a floor here even though the same accessor serves
		// ceilings too; the comparison direction is what differs between variants.
		boolean satisfied = gross.compareTo(threshold) >= 0;
		return result(rule, context, satisfied, gross + " vs minimum charge " + threshold);
	}

	/**
	 * The unit price must be at least the floor. A tiered price published only as a
	 * band legitimately has no unit price in the context, which is not the same as
	 * a unit price of zero.
	 */
	private RuleEvaluationResult priceFloor(CommercialRule rule, CommercialEvaluationContext context) {
		if (!context.hasUnitPrice()) {
			return notApplicable(rule, context, "no unit price was supplied");
		}
		Money unitPrice = requireCurrency(context.unitPrice(), context, rule);
		Money floor = threshold(rule, context);
		boolean satisfied = unitPrice.compareTo(floor) >= 0;
		return result(rule, context, satisfied, unitPrice + " vs price floor " + floor);
	}

	/**
	 * The unit price must not exceed the ceiling.
	 */
	private RuleEvaluationResult priceCeiling(CommercialRule rule, CommercialEvaluationContext context) {
		if (!context.hasUnitPrice()) {
			return notApplicable(rule, context, "no unit price was supplied");
		}
		Money unitPrice = requireCurrency(context.unitPrice(), context, rule);
		Money ceiling = threshold(rule, context);
		boolean satisfied = unitPrice.compareTo(ceiling) <= 0;
		return result(rule, context, satisfied, unitPrice + " vs price ceiling " + ceiling);
	}

	/**
	 * The discount already granted must not exceed the contractual ceiling.
	 *
	 * <p>Checks the granted amount rather than the term's percentage, because a
	 * cap that was reached is not a breach: it is the contract working.
	 */
	private RuleEvaluationResult maximumDiscount(CommercialRule rule, CommercialEvaluationContext context) {
		if (!context.hasDiscountAmount()) {
			return notApplicable(rule, context, "no discount amount was supplied");
		}
		Money discount = requireCurrency(context.discountAmount(), context, rule);
		Money ceiling = threshold(rule, context);
		boolean satisfied = discount.compareTo(ceiling) <= 0;
		return result(rule, context, satisfied, discount + " vs maximum discount " + ceiling);
	}

	/**
	 * The agreed payment term must not be longer than the permitted days.
	 */
	private RuleEvaluationResult paymentTermDays(CommercialRule rule, CommercialEvaluationContext context) {
		if (!context.hasPaymentTermDays()) {
			return notApplicable(rule, context, "no payment term was supplied");
		}
		int permitted = rule.parameters()
				.requirePositiveInt(rule.ruleType().requiredParameter(), ruleLabel(rule));
		int agreed = context.paymentTermDays();
		// Longer than permitted is the breach, so satisfaction is the <= branch.
		// Zero permitted days is refused upstream by requirePositiveInt rather than
		// being interpreted as "payment due immediately".
		boolean satisfied = agreed <= permitted;
		return result(rule, context, satisfied, agreed + " days vs permitted " + permitted);
	}

	/**
	 * A gross amount at or above the threshold needs an extra approval.
	 *
	 * <p>Encoded as a satisfied/breached pair rather than a special flag so it
	 * travels through the same path as every other rule: the caller cannot forget to
	 * read a separate boolean.
	 */
	private RuleEvaluationResult approvalRequired(CommercialRule rule, CommercialEvaluationContext context) {
		if (!context.hasGrossAmount()) {
			return notApplicable(rule, context, "no gross amount was supplied");
		}
		Money gross = requireCurrency(context.grossAmount(), context, rule);
		Money threshold = threshold(rule, context);
		boolean needsApproval = gross.compareTo(threshold) >= 0;
		return result(rule, context, !needsApproval,
				gross + " vs approval threshold " + threshold + (needsApproval ? ", approval needed" : ", no approval needed"));
	}

	/**
	 * The quantity must reach the threshold.
	 */
	private RuleEvaluationResult freeGoodsThreshold(CommercialRule rule, CommercialEvaluationContext context) {
		if (!context.hasQuantity()) {
			return notApplicable(rule, context, "no quantity was supplied");
		}
		BigDecimal threshold = rule.parameters()
				.requireDecimal(rule.ruleType().requiredParameter(), ruleLabel(rule));
		BigDecimal quantity = context.quantity();
		boolean satisfied = quantity.compareTo(threshold) >= 0;
		return result(rule, context, satisfied, quantity.toPlainString() + " vs threshold " + threshold.toPlainString());
	}

	/**
	 * The gross amount must not exceed a threshold computed from the rule's own
	 * expression.
	 *
	 * <p>The comparison happens on unrounded decimals on both sides, at amount scale
	 * on the transaction side. Rounding the threshold to four places first would let
	 * a value such as 999.99995 sit on the wrong side of a ceiling for reasons that
	 * are an artifact of rounding rather than of the contract.
	 */
	private RuleEvaluationResult expressionThreshold(CommercialRule rule, CommercialEvaluationContext context) {
		if (!context.hasGrossAmount()) {
			return notApplicable(rule, context, "no gross amount was supplied");
		}
		Money gross = requireCurrency(context.grossAmount(), context, rule);
		BigDecimal threshold = rule.compiledExpression()
				.evaluate(rule.parameters(), MonetaryScale.AMOUNT_SCALE, MonetaryScale.ROUNDING_MODE);
		boolean satisfied = gross.amount().compareTo(threshold) <= 0;
		return result(rule, context, satisfied,
				gross.amount().toPlainString() + " vs computed threshold " + threshold.toPlainString());
	}

	/**
	 * The single monetary threshold this rule type requires, read at amount scale
	 * and pinned to the transaction's currency.
	 */
	private Money threshold(CommercialRule rule, CommercialEvaluationContext context) {
		String key = rule.ruleType().requiredParameter();
		// The key comes from the type, never from the rule's own text, so a stored
		// parameter under a different name cannot silently become the threshold.
		BigDecimal value = rule.parameters().requireDecimal(key, ruleLabel(rule));
		// Pinned to the transaction's currency rather than any currency of its own:
		// the rule stores a bare decimal, and a rule must never introduce an FX
		// rate into a comparison.
		return MonetaryScale.amount(Money.of(value, context.currency()));
	}

	private static Money requireCurrency(Money amount, CommercialEvaluationContext context, CommercialRule rule) {
		if (!context.isInCurrency(amount)) {
			throw new BusinessRuleException("rule " + rule.ruleCode() + " was given an amount in "
					+ amount.currency().value() + " but the transaction is in " + context.currency().value());
		}
		return amount;
	}

	private static RuleEvaluationResult notApplicable(CommercialRule rule, CommercialEvaluationContext context,
			String reason) {
		return RuleEvaluationResult.notApplicable(rule.ruleCode(), rule.ruleType(), rule.id(), rule.termVersion(),
				context.asOfDate(), reason + ", so " + rule.ruleCode() + " could not be checked", parameters(rule));
	}

	private static RuleEvaluationResult result(CommercialRule rule, CommercialEvaluationContext context,
			boolean satisfied, String detail) {
		String explanation = rule.ruleCode() + ": " + detail;
		// The explanation is always populated, including for a breach. A stored
		// violation with no recorded reason is a finding a reviewer cannot act on.
		if (satisfied) {
			return RuleEvaluationResult.satisfied(rule.ruleCode(), rule.ruleType(), rule.id(), rule.termVersion(),
					context.asOfDate(), explanation, parameters(rule));
		}
		return RuleEvaluationResult.violated(rule.ruleCode(), rule.ruleType(), rule.id(), rule.termVersion(),
				context.asOfDate(), explanation, parameters(rule));
	}

	/**
	 * The parameters a result was computed from, in canonical form, so the checksum
	 * attests to the configuration as well as the outcome.
	 */
	private static Map<String, String> parameters(CommercialRule rule) {
		return new LinkedHashMap<>(rule.parameters().values());
	}

	private static String ruleLabel(CommercialRule rule) {
		return "rule " + rule.ruleCode() + " (" + rule.ruleType().code() + ")";
	}

	/**
	 * Rules in force for a date, in precedence order.
	 */
	public List<CommercialRule> rulesInForce(List<CommercialRule> rules, LocalDate asOfDate) {
		return this.resolver.inForceOn(rules, asOfDate);
	}

	/**
	 * The first rule in force of the given type, if any.
	 */
	public Optional<CommercialRule> ruleInForce(List<CommercialRule> rules, CommercialRuleType ruleType,
			LocalDate asOfDate) {
		Objects.requireNonNull(ruleType, "ruleType must not be null");
		return this.resolver.findInForce(rules, asOfDate, rule -> rule.ruleType().equals(ruleType));
	}

}
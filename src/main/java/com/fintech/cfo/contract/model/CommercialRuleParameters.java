package com.fintech.cfo.contract.model;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import org.jspecify.annotations.Nullable;

import com.fintech.cfo.shared.domain.CurrencyCode;
import com.fintech.cfo.shared.domain.Money;
import com.fintech.cfo.shared.exception.BusinessRuleException;
import com.fintech.cfo.shared.exception.ValidationException;

/**
 * Typed view over {@code commercial_rules.parameters TEXT}.
 *
 * <p>The column holds an unstructured {@code key=value;key=value} string.
 * Parsing it into a map is what turns a rule from prose into something that can be
 * evaluated deterministically, and re-rendering it through {@link #canonical()} is
 * what makes the input checksum insensitive to the spacing and trailing zeros the
 * author happened to type.
 *
 * <p>Two strictnesses, deliberately split:
 *
 * <ul>
 * <li><strong>Shape is checked at parse time</strong>
 * ({@link ValidationException}): a string that is not {@code key=value} pairs is
 * not parameters.</li>
 * <li><strong>Applicability is checked at evaluation time</strong>
 * ({@link BusinessRuleException}): a well-formed rule missing the one value its
 * type requires cannot be applied, and skipping it would let through the exact
 * transaction it was written to catch.</li>
 * </ul>
 *
 * <p>Keys are normalised to lower case, so {@code Min_Amount} and
 * {@code min_amount} are one key rather than one key and one missing key. Keys are
 * held in sorted order, so no evaluation or checksum can depend on hash iteration
 * order.
 */
public record CommercialRuleParameters(Map<String, String> values) implements Serializable {

	private static final char PAIR_SEPARATOR = ';';
	private static final char KEY_VALUE_SEPARATOR = '=';

	private static final CommercialRuleParameters EMPTY = new CommercialRuleParameters(Map.of());

	public CommercialRuleParameters {
		Objects.requireNonNull(values, "values must not be null");
		Map<String, String> copy = new TreeMap<>();
		for (Map.Entry<String, String> entry : values.entrySet()) {
			// Key normalised before insertion, so `Min_Amount` and `min_amount` are
			// the same parameter rather than one parameter and one missing one.
			String key = normaliseKey(entry.getKey());
			String value = entry.getValue();
			// A blank value is rejected rather than stored as empty: an empty
			// threshold would fail every transaction it was written to catch, and
			// silently.
			if (value == null || value.isBlank()) {
				throw new ValidationException("commercial rule parameter '" + key + "' has a blank value");
			}
			// The put result detects a collision that normalisation created, which is
			// the only way two distinct author keys can become one.
			if (copy.put(key, value.trim()) != null) {
				throw new ValidationException("duplicate commercial rule parameter: " + key);
			}
		}
		// Unmodifiable and sorted: no evaluation, checksum or wire projection can
		// depend on hash iteration order.
		values = Collections.unmodifiableMap(copy);
	}

	public static CommercialRuleParameters empty() {
		return EMPTY;
	}

	/**
	 * Parses the {@code parameters} column. A null or blank column yields empty
	 * parameters, which is legitimate: V5 allows the column to be null, and only
	 * the rule types that need a value complain later.
	 *
	 * @throws ValidationException if a segment is not {@code key=value}, a value is
	 * blank, or a key repeats
	 */
	public static CommercialRuleParameters parse(@Nullable String raw) {
		if (raw == null || raw.isBlank()) {
			return EMPTY;
		}
		Map<String, String> parsed = new LinkedHashMap<>();
		for (String segment : raw.split(String.valueOf(PAIR_SEPARATOR), -1)) {
			// Blank segments skipped so trailing or doubled `;` are tolerated; the
			// negative limit keeps a trailing separator from being silently dropped
			// by String.split's default behaviour.
			if (segment.isBlank()) {
				continue;
			}
			// indexOf, not a regex: a parameter value is free text and may itself
			// contain an `=`, so only the first one separates.
			int separator = segment.indexOf(KEY_VALUE_SEPARATOR);
			if (separator < 0) {
				throw new ValidationException(
						"commercial rule parameter segment is not key=value: " + segment.trim());
			}
			// The value is stored raw here; trimming, blank-checking and duplicate
			// detection all happen in the compact constructor, so both entry points
			// into this type share one set of rules.
			parsed.put(normaliseKey(segment.substring(0, separator)), segment.substring(separator + 1));
		}
		return new CommercialRuleParameters(parsed);
	}

	public boolean isEmpty() {
		return this.values.isEmpty();
	}

	public Set<String> keys() {
		return this.values.keySet();
	}

	public boolean contains(String key) {
		return this.values.containsKey(normaliseKey(key));
	}

	public Optional<String> get(String key) {
		return Optional.ofNullable(this.values.get(normaliseKey(key)));
	}

	/**
	 * @param key required key
	 * @param ruleContext human-readable identification of the rule, used only in
	 * the failure message
	 * @throws BusinessRuleException if the key is absent
	 */
	public String require(String key, String ruleContext) {
		String value = this.values.get(normaliseKey(key));
		if (value == null) {
			throw new BusinessRuleException(ruleContext + " requires parameter '" + key + "'");
		}
		return value;
	}

	/**
	 * @throws BusinessRuleException if the parameter is absent or is not a decimal
	 */
	public BigDecimal requireDecimal(String key, String ruleContext) {
		String stored = require(key, ruleContext);
		try {
			return new BigDecimal(stored);
		}
		catch (NumberFormatException exception) {
			throw new BusinessRuleException(
					ruleContext + " parameter '" + key + "' is not a decimal: " + stored, exception);
		}
	}

	/**
	 * A monetary parameter, pinned to the evaluation currency.
	 *
	 * <p>The currency is a property of the transaction being evaluated, not of the
	 * rule, so a rule never carries one. A parameter is rejected rather than
	 * converted: this codebase has no FX component, and inventing a rate here would
	 * put an unreproducible number into a monetary result.
	 *
	 * @throws BusinessRuleException if the parameter is absent, unparsable, or
	 * negative, since a negative threshold is never a threshold
	 */
	public Money requireMoney(String key, CurrencyCode currency, String ruleContext) {
		BigDecimal value = requireDecimal(key, ruleContext);
		if (value.signum() < 0) {
			throw new BusinessRuleException(ruleContext + " parameter '" + key + "' must not be negative");
		}
		return Money.of(value, currency);
	}

	/**
	 * A whole number greater than zero, such as a number of payment days.
	 *
	 * @throws BusinessRuleException if the parameter is absent, is not a whole
	 * number greater than zero, or does not fit an int
	 */
	public int requirePositiveInt(String key, String ruleContext) {
		BigDecimal value = requireDecimal(key, ruleContext);
		if (value.signum() <= 0 || value.stripTrailingZeros().scale() > 0) {
			throw new BusinessRuleException(
					ruleContext + " parameter '" + key + "' must be a whole number greater than zero");
		}
		try {
			return value.intValueExact();
		}
		catch (ArithmeticException exception) {
			throw new BusinessRuleException(
					ruleContext + " parameter '" + key + "' is out of range: " + value, exception);
		}
	}

	/**
	 * Canonical text form: keys already sorted, numbers stripped of trailing zeros
	 * so {@code 10.0000} and {@code 10} hash alike. The author's spacing cannot
	 * reach the checksum.
	 */
	public String canonical() {
		StringBuilder builder = new StringBuilder(64);
		for (Map.Entry<String, String> entry : this.values.entrySet()) {
			if (builder.length() > 0) {
				builder.append(PAIR_SEPARATOR);
			}
			builder.append(entry.getKey()).append(KEY_VALUE_SEPARATOR).append(canonicalValue(entry.getValue()));
		}
		return builder.toString();
	}

	private static String canonicalValue(String value) {
		// Numbers are normalised so `10.0000` and `10` hash alike; anything that is
		// not a decimal is passed through unchanged. Stripping is safe here but
		// would not be inside evaluation, where the author's precision may matter.
		try {
			return new BigDecimal(value).stripTrailingZeros().toPlainString();
		}
		catch (NumberFormatException exception) {
			return value;
		}
	}

	private static String normaliseKey(String key) {
		Objects.requireNonNull(key, "parameter key must not be null");
		String normalized = key.trim().toLowerCase(Locale.ROOT);
		if (normalized.isEmpty()) {
			throw new ValidationException("commercial rule parameter key must not be blank");
		}
		return normalized;
	}

}

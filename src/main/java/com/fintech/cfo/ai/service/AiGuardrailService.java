package com.fintech.cfo.ai.service;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stateless guardrail checks applied to AI input and output.
 *
 * <p>Instances are configuration carriers: the input budget is injected so a
 * caller that wants a tighter context can tighten it without subclassing.
 * Everything else is pure function over strings.
 */
public final class AiGuardrailService {

	/** Characters of context the service will forward to a model at all. A cost/latency guardrail. */
	private static final int DEFAULT_MAX_INPUT_CHARS = 8_000;

	/**
	 * A deliberately conservative shape: a run of digits with optional Western
	 * thousands grouping ({@code 4,299.00}) or Indian grouping is accepted, a lone
	 * digit is accepted, and anything the {@link BigDecimal} normaliser rejects
	 * (for example {@code 3.14.15}, which is almost certainly two values mashed
	 * together) is skipped rather than misread.
	 */
	private static final Pattern NUMBER_TOKEN = Pattern.compile("[0-9][0-9.,]*[0-9]|[0-9]");

	/**
	 * Substrings, lower-cased, that read as the model declining to answer. The
	 * set is intentionally broad: a refusal is a strong signal to mark the run
	 * {@link com.fintech.cfo.ai.enums.AiValidationStatus#Rejected} rather than to
	 * trust whatever partial text preceded it.
	 */
	private static final List<String> REFUSAL_PHRASES = List.of(
			"i can't", "i cannot", "i'm not able", "i'm unable to", "as an ai",
			"i'm sorry", "i'm a language model", "i don't have", "unable to",
			"i won't", "i will not", "refuse", "i'm not permitted");

	/**
	 * Substrings, lower-cased, that look like an attempt to rewrite the model's
	 * instructions. Not a security boundary — a model that wants to be hijacked can
	 * usually find a tokenisation the literal list misses — but a cheap first
	 * filter that keeps the obvious prompts out of the model at all.
	 */
	private static final List<String> INJECTION_PHRASES = List.of(
			"ignore previous", "ignore all previous", "disregard the above",
			"disregard your", "forget the above", "forget your", "new instructions:",
			"new prompt:", "you are now", "you are a ", "act as ", "override your",
			"reset your instructions", "disobey", "ignore the system");

	private final int maxInputChars;

	public AiGuardrailService() {
		this(DEFAULT_MAX_INPUT_CHARS);
	}

	public AiGuardrailService(int maxInputChars) {
		this.maxInputChars = maxInputChars;
	}

	/**
	 * @param text raw input text
	 * @return true when the text is too large to send without risking a timeout
	 *         or an over-long bill
	 */
	public boolean isOversized(String text) {
		return text != null && text.length() > this.maxInputChars;
	}

	/**
	 * @param text raw model output
	 * @return true when the output reads as a refusal rather than an answer
	 */
	public boolean looksLikeRefusal(String text) {
		if (text == null) {
			return false;
		}
		String lower = text.toLowerCase(Locale.ROOT);
		for (String phrase : REFUSAL_PHRASES) {
			if (lower.contains(phrase)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * @param text untrusted input text
	 * @return true when the text looks like an attempt to inject system instructions
	 */
	public boolean looksLikeInjection(String text) {
		if (text == null) {
			return false;
		}
		String lower = text.toLowerCase(Locale.ROOT);
		for (String phrase : INJECTION_PHRASES) {
			if (lower.contains(phrase)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Every number a piece of text carries, in a canonical form.
	 *
	 * <p>Numbers are normalised so that {@code 4,299.00}, {@code 4299} and
	 * {@code 4299.0} all collapse to {@code 4299}. Collapsing is what makes the
	 * fence sound: a model that restates a figure it was given in a different
	 * guise is not inventing one.
	 *
	 * @param text the text to scan
	 * @return the canonical numbers found, never null
	 */
	public Set<String> extractNumbers(String text) {
		Set<String> numbers = new HashSet<>();
		if (text == null || text.isEmpty()) {
			return numbers;
		}
		Matcher matcher = NUMBER_TOKEN.matcher(text);
		while (matcher.find()) {
			String canonical = canonicalize(matcher.group());
			if (canonical != null) {
				numbers.add(canonical);
			}
		}
		return numbers;
	}

	/**
	 * The thesis fence. Numbers present in {@code output} that are not among
	 * {@code allowedNumbers}.
	 *
	 * <p>An explanation may only cite figures from the deterministic context it
	 * was shown; an explanation that cites anything else is, by definition,
	 * producing a number rather than describing one, and is marked
	 * {@link com.fintech.cfo.ai.enums.AiValidationStatus#Disputed} by the
	 * caller. The check is deliberately over-cautious: a model that legitimately
	 * references a section or a date that was not in its context trips here and
	 * is reviewed, never surfaced unreviewed.
	 *
	 * @param output          the model's reply
	 * @param allowedNumbers  the canonical numbers extracted from the context it was shown
	 * @return the canonical numbers in {@code output} that were not allowed
	 */
	public Set<String> findForeignNumbers(String output, Set<String> allowedNumbers) {
		Set<String> foreign = new HashSet<>(extractNumbers(output));
		foreign.removeAll(allowedNumbers);
		return foreign;
	}

	private static String canonicalize(String token) {
		// Commas are thousands separators in the locales this serves; the
		// BigDecimal below cannot parse them, so they are stripped first.
		String digits = token.replace(",", "");
		try {
			return new BigDecimal(digits).stripTrailingZeros().toPlainString();
		}
		catch (NumberFormatException | ArithmeticException ex) {
			// A token the pattern matched but the parser rejects (e.g. "3.14.15") is
			// not a number we can reason about, so it is ignored rather than misread.
			return null;
		}
	}

}

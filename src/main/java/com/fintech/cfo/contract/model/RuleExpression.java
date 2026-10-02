package com.fintech.cfo.contract.model;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import com.fintech.cfo.shared.exception.BusinessRuleException;
import com.fintech.cfo.shared.exception.ValidationException;

/**
 * A commercial-rule expression, restricted to a small grammar and evaluated by
 * walking a parsed tree.
 *
 * <p>{@code commercial_rules.expression VARCHAR(2000)} is written by a commercial
 * reader, so for most rules it is prose and nothing more. This type exists for the
 * minority that is genuinely arithmetic - {@code max(min_amount * 0.9,
 * floor_amount)} - and deliberately refuses everything else.
 *
 * <p><strong>No {@code eval}, no reflection, no dynamic dispatch on stored text.</strong>
 * The source is tokenised and parsed once into a sealed tree of records, and
 * evaluation is an exhaustive {@code switch} over that tree. There is no
 * string-to-class lookup, no method resolution on a name read from the column, and
 * no operator the author can name that the parser does not already know. An
 * expression outside the grammar is refused when it is read, because a rule that
 * quietly means something other than it says is worse than no rule at all.
 *
 * <p><strong>Grammar</strong> (whitespace insignificant, ASCII only):
 *
 * <pre>
 * expression := product ( ('+' | '-') product )*
 * product   := unary ( ('*' | '/') unary )*
 * unary     := ('+' | '-') unary | primary
 * primary   := number | parameter | '(' expression ')' | function
 * function  := ('min' | 'max') '(' expression ',' expression ')'
 *            | ('abs' | 'round') '(' expression ')'
 * parameter := [a-z][a-z0-9_]*   -- resolved from the rule's own parameters
 * </pre>
 *
 * <p>Source length, nesting depth and total node count are bounded, so neither a
 * hostile nor a mistaken expression can turn into unbounded work at evaluation
 * time.
 *
 * <p>Division requires an explicit scale and rounding mode, exactly as
 * {@code Money.divide} does, so a rule that derives a threshold by dividing rounds
 * the same way a price does.
 */
public final class RuleExpression implements Serializable {

	private static final long serialVersionUID = 1L;

	/**
	 * Generous next to the {@code VARCHAR(2000)} column but far below it: an
	 * expression of this size is a threshold formula, not a program.
	 */
	public static final int MAX_SOURCE_LENGTH = 1000;

	/**
	 * Bound on nesting, so nested parentheses cannot exhaust the stack.
	 */
	public static final int MAX_DEPTH = 32;

	/**
	 * Bound on the size of the parsed tree, so a long chain of operators cannot
	 * produce an expression that is expensive to evaluate repeatedly.
	 */
	public static final int MAX_NODES = 256;

	private final String source;

	private final Node root;

	private final Set<String> variables;

	private RuleExpression(String source, Node root, Set<String> variables) {
		this.source = source;
		this.root = root;
		this.variables = Set.copyOf(variables);
	}

	/**
	 * Parses an expression, or refuses it.
	 *
	 * @param source authored expression text
	 * @throws ValidationException if the text is blank, too long, too deeply nested,
	 * or contains anything outside the grammar
	 */
	public static RuleExpression parse(String source) {
		Objects.requireNonNull(source, "source must not be null");
		String trimmed = source.trim();
		if (trimmed.isEmpty()) {
			throw new ValidationException("commercial rule expression must not be blank");
		}
		if (trimmed.length() > MAX_SOURCE_LENGTH) {
			throw new ValidationException(
					"commercial rule expression exceeds " + MAX_SOURCE_LENGTH + " characters: " + trimmed.length());
		}
		Parser parser = new Parser(trimmed);
		Node root = parser.parseExpression(0);
		parser.requireEndOfInput();
		Set<String> variables = new LinkedHashSet<>();
		root.collectVariables(variables);
		return new RuleExpression(trimmed, root, variables);
	}

	/**
	 * Whether the text is inside the grammar. Lets a write path validate a row
	 * without keeping a parsed tree.
	 */
	public static boolean isValid(String source) {
		try {
			parse(source);
			return true;
		}
		catch (ValidationException exception) {
			return false;
		}
	}

	/**
	 * Evaluates the expression with the rule's parameters bound.
	 *
	 * @param parameters the rule's {@code commercial_rules.parameters}
	 * @param scale scale of the result
	 * @param roundingMode rounding for division and for the final result
	 * @throws BusinessRuleException if a referenced parameter is absent or is not a
	 * number, or the result divides by zero. Never a partial value: a threshold
	 * that cannot be computed must not degrade to zero, because zero would fail
	 * every transaction that reached it while looking like a real rule.
	 */
	public BigDecimal evaluate(CommercialRuleParameters parameters, int scale, RoundingMode roundingMode) {
		Objects.requireNonNull(parameters, "parameters must not be null");
		Objects.requireNonNull(roundingMode, "roundingMode must not be null");
		return this.root.evaluate(parameters, scale, roundingMode).setScale(scale, roundingMode);
	}

	/**
	 * Parameter names this expression reads, so a rule can be validated against its
	 * stored parameters before it is used.
	 */
	public Set<String> variables() {
		return this.variables;
	}

	/**
	 * The authored text, retained verbatim for display and for the checksum.
	 */
	public String source() {
		return this.source;
	}

	@Override
	public String toString() {
		return this.source;
	}

	/**
	 * The parsed tree. Sealed so that adding a node kind breaks every evaluator that
	 * has to handle it, at compile time rather than in production.
	 */
	private sealed interface Node {

		BigDecimal evaluate(CommercialRuleParameters parameters, int scale, RoundingMode roundingMode);

		void collectVariables(Set<String> into);

		record Constant(BigDecimal value) implements Node {

			@Override
			public BigDecimal evaluate(CommercialRuleParameters parameters, int scale, RoundingMode roundingMode) {
				return this.value;
			}

			@Override
			public void collectVariables(Set<String> into) {
			}

		}

		record Parameter(String name) implements Node {

			@Override
			public BigDecimal evaluate(CommercialRuleParameters parameters, int scale, RoundingMode roundingMode) {
				if (!parameters.contains(this.name)) {
					throw new BusinessRuleException("expression references undefined parameter '" + this.name + "'");
				}
				return parameters.requireDecimal(this.name, "expression");
			}

			@Override
			public void collectVariables(Set<String> into) {
				into.add(this.name);
			}

		}

		record Negated(Node operand) implements Node {

			@Override
			public BigDecimal evaluate(CommercialRuleParameters parameters, int scale, RoundingMode roundingMode) {
				return this.operand.evaluate(parameters, scale, roundingMode).negate();
			}

			@Override
			public void collectVariables(Set<String> into) {
				this.operand.collectVariables(into);
			}

		}

		record Sum(Node left, Node right, boolean additive) implements Node {

			@Override
			public BigDecimal evaluate(CommercialRuleParameters parameters, int scale, RoundingMode roundingMode) {
				BigDecimal left = this.left.evaluate(parameters, scale, roundingMode);
				BigDecimal right = this.right.evaluate(parameters, scale, roundingMode);
				return this.additive ? left.add(right) : left.multiply(right, MathContext.DECIMAL128);
			}

			@Override
			public void collectVariables(Set<String> into) {
				this.left.collectVariables(into);
				this.right.collectVariables(into);
			}

		}

		/**
		 * A {@code /} node, kept separate from {@link Sum} so that division is the
		 * one place that must supply an explicit scale and rounding mode.
		 */
		record Quotient(Node dividend, Node divisor) implements Node {

			@Override
			public BigDecimal evaluate(CommercialRuleParameters parameters, int scale, RoundingMode roundingMode) {
				BigDecimal divisor = this.divisor.evaluate(parameters, scale, roundingMode);
				if (divisor.signum() == 0) {
					throw new BusinessRuleException("expression divides by zero");
				}
				return this.dividend.evaluate(parameters, scale, roundingMode).divide(divisor, scale, roundingMode);
			}

			@Override
			public void collectVariables(Set<String> into) {
				this.dividend.collectVariables(into);
				this.divisor.collectVariables(into);
			}

		}

		record Minimum(Node left, Node right) implements Node {

			@Override
			public BigDecimal evaluate(CommercialRuleParameters parameters, int scale, RoundingMode roundingMode) {
				return this.left.evaluate(parameters, scale, roundingMode)
						.min(this.right.evaluate(parameters, scale, roundingMode));
			}

			@Override
			public void collectVariables(Set<String> into) {
				this.left.collectVariables(into);
				this.right.collectVariables(into);
			}

		}

		record Maximum(Node left, Node right) implements Node {

			@Override
			public BigDecimal evaluate(CommercialRuleParameters parameters, int scale, RoundingMode roundingMode) {
				return this.left.evaluate(parameters, scale, roundingMode)
						.max(this.right.evaluate(parameters, scale, roundingMode));
			}

			@Override
			public void collectVariables(Set<String> into) {
				this.left.collectVariables(into);
				this.right.collectVariables(into);
			}

		}

		record Absolute(Node operand) implements Node {

			@Override
			public BigDecimal evaluate(CommercialRuleParameters parameters, int scale, RoundingMode roundingMode) {
				return this.operand.evaluate(parameters, scale, roundingMode).abs();
			}

			@Override
			public void collectVariables(Set<String> into) {
				this.operand.collectVariables(into);
			}

		}

		/**
		 * Rounds to a whole number of currency units. The only rounding the grammar
		 * exposes, and it rounds with the caller's chosen mode rather than a fixed
		 * one.
		 */
		record Rounded(Node operand) implements Node {

			@Override
			public BigDecimal evaluate(CommercialRuleParameters parameters, int scale, RoundingMode roundingMode) {
				return this.operand.evaluate(parameters, scale, roundingMode).setScale(0, roundingMode);
			}

			@Override
			public void collectVariables(Set<String> into) {
				this.operand.collectVariables(into);
			}

		}

	}

	/**
	 * Recursive-descent parser over a fixed character set. Every method either
	 * consumes what the grammar allows or raises; there is no recovery path, so a
	 * malformed expression can never be partially understood.
	 */
	private static final class Parser {

		private final String source;

		private int position;

		private int nodes;

		private Parser(String source) {
			this.source = source;
		}

		private Node parseExpression(int depth) {
			requireDepth(depth);
			Node left = parseProduct(depth + 1);
			while (peekIs('+') || peekIs('-')) {
				boolean additive = peekIs('+');
				advance();
				left = new Node.Sum(left, parseProduct(depth + 1), additive);
				countNode();
			}
			return left;
		}

		private Node parseProduct(int depth) {
			requireDepth(depth);
			Node left = parseUnary(depth + 1);
			while (peekIs('*') || peekIs('/')) {
				boolean multiplicative = peekIs('*');
				advance();
				Node right = parseUnary(depth + 1);
				// Multiplication reuses the Sum node with a flag rather than adding a
				// third variant; division gets its own node because it is the only
				// operation that needs an explicit scale and rounding mode.
				left = multiplicative ? new Node.Sum(left, right, false) : new Node.Quotient(left, right);
				countNode();
			}
			return left;
		}

		private Node parseUnary(int depth) {
			requireDepth(depth);
			if (peekIs('-')) {
				advance();
				countNode();
				return new Node.Negated(parseUnary(depth + 1));
			}
			if (peekIs('+')) {
				advance();
				return parseUnary(depth + 1);
			}
			return parsePrimary(depth + 1);
		}

		private Node parsePrimary(int depth) {
			requireDepth(depth);
			skipWhitespace();
			if (this.position >= this.source.length()) {
				throw fail("unexpected end of expression");
			}
			char current = this.source.charAt(this.position);
			if (current == '(') {
				advance();
				Node inner = parseExpression(depth + 1);
				requireClosingParenthesis();
				return inner;
			}
			if (isDigit(current) || current == '.') {
				BigDecimal value = parseNumber();
				countNode();
				return new Node.Constant(value);
			}
			if (isNameStart(current)) {
				return parseNameOrFunction(depth + 1);
			}
			throw fail("unexpected character '" + current + "'");
		}

		private Node parseNameOrFunction(int depth) {
			String name = parseName();
			// A name not followed by `(` is a parameter reference resolved against the
			// rule's own stored parameters - there is no name-to-class lookup and no
			// method resolution on an authored word.
			if (!peekIs('(')) {
				countNode();
				return new Node.Parameter(name);
			}
			advance();
			Node first = parseExpression(depth + 1);
			// min and max take two arguments; anything else below is arity-checked by
			// requiring the closing parenthesis immediately after the first argument.
			if (name.equals("min") || name.equals("max")) {
				if (!peekIs(',')) {
					throw fail(name + "() takes two arguments");
				}
				advance();
				Node second = parseExpression(depth + 1);
				requireClosingParenthesis();
				countNode();
				return name.equals("min") ? new Node.Minimum(first, second) : new Node.Maximum(first, second);
			}
			requireClosingParenthesis();
			countNode();
			// An unknown name is refused at parse time. Refusing is the whole safety
			// story: a stored expression can name nothing this switch does not already
			// implement, so there is no path from a column value to arbitrary behaviour.
			return switch (name) {
				case "abs" -> new Node.Absolute(first);
				case "round" -> new Node.Rounded(first);
				default -> throw fail("unknown function '" + name + "'");
			};
		}

		private BigDecimal parseNumber() {
			int start = this.position;
			boolean seenDot = false;
			while (this.position < this.source.length()) {
				char current = this.source.charAt(this.position);
				if (isDigit(current)) {
					this.position++;
				}
				// At most one dot, and it is mandatory-free: `.5` is accepted. A second
				// dot stops the scan rather than being consumed, so the trailing
				// `requireEndOfInput` reports it as unexpected input.
				else if (current == '.' && !seenDot) {
					seenDot = true;
					this.position++;
				}
				else {
					break;
				}
			}
			// Parsed strictly, never via valueOf(double): a threshold that passed
			// through a binary double would not be reproducible.
			try {
				return new BigDecimal(this.source.substring(start, this.position));
			}
			catch (NumberFormatException exception) {
				throw fail("not a number: " + this.source.substring(start, this.position));
			}
		}

		private String parseName() {
			int start = this.position;
			while (this.position < this.source.length() && isNamePart(this.source.charAt(this.position))) {
				this.position++;
			}
			// Lower-cased so the function dispatch below is a single spelling, while
			// the stored source keeps the author's capitalisation for display.
			return this.source.substring(start, this.position).toLowerCase(Locale.ROOT);
		}

		private void requireClosingParenthesis() {
			if (!peekIs(')')) {
				throw fail("expected ')'");
			}
			advance();
		}

		private void requireEndOfInput() {
			skipWhitespace();
			if (this.position < this.source.length()) {
				throw fail("unexpected trailing input");
			}
		}

		private void countNode() {
			// Bounded tree size, so a long operator chain cannot produce an expression
			// that is cheap to parse and expensive to evaluate on every transaction.
			if (++this.nodes > MAX_NODES) {
				throw fail("expression is too complex");
			}
		}

		private void requireDepth(int depth) {
			// Bounded nesting, so deeply parenthesised input cannot exhaust the stack
			// of this recursive-descent parser.
			if (depth > MAX_DEPTH) {
				throw fail("expression is nested more than " + MAX_DEPTH + " levels deep");
			}
		}

		private boolean peekIs(char expected) {
			skipWhitespace();
			return this.position < this.source.length() && this.source.charAt(this.position) == expected;
		}

		private void advance() {
			this.position++;
		}

		private void skipWhitespace() {
			while (this.position < this.source.length()
					&& Character.isWhitespace(this.source.charAt(this.position))) {
				this.position++;
			}
		}

		private ValidationException fail(String reason) {
			return new ValidationException("commercial rule expression is not in the supported grammar at position "
					+ this.position + ": " + reason + " in \"" + this.source + "\"");
		}

	}

	private static boolean isDigit(char value) {
		return value >= '0' && value <= '9';
	}

	/**
	 * Explicit ASCII range rather than {@code Character.isLetter}: the grammar is
	 * documented as ASCII-only, and accepting every Unicode letter would make the
	 * "parameter or function" decision depend on the author's keyboard.
	 */
	private static boolean isNameStart(char value) {
		return value >= 'a' && value <= 'z';
	}

	private static boolean isNamePart(char value) {
		return isNameStart(value) || isDigit(value) || value == '_';
	}

}

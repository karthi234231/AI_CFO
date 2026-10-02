package com.fintech.cfo.ingestion.validator;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import com.fintech.cfo.ingestion.enums.RejectionReason;
import com.fintech.cfo.shared.domain.CurrencyCode;

/**
 * Strict parsing of the three value kinds an ingestion row can carry: a decimal,
 * a date and a currency.
 *
 * <p>Every conversion here is exact and locale-free. Amounts are never parsed
 * through {@code Double}, and grouping separators are rejected rather than
 * guessed at: {@code 1,234.56} and {@code 1.234,56} are two different numbers and
 * neither can be recovered from the string alone, so a value in that shape is a
 * refusal rather than a coin flip (rule 2).
 *
 * <p>Dates are parsed with {@link ResolverStyle#STRICT} so {@code 2024-02-30}
 * fails instead of silently becoming 1 March. Only unambiguous layouts are
 * accepted — day-before-month and year-first. {@code MM/dd/yyyy} is deliberately
 * absent, because accepting both orders for the same string makes the answer
 * depend on the caller rather than the data.
 *
 * <p>Amounts are validated against the storage contract of rule 2,
 * {@code NUMERIC(20,4)}: more than four fraction digits is a refusal, not a
 * rounding. Rounding a source figure during ingestion would change the audited
 * number, and no one downstream would be able to tell.
 */
public final class TypedValueParser {

	public static final int AMOUNT_SCALE = 4;

	public static final int AMOUNT_PRECISION = 20;

	public static final int MAX_INTEGER_DIGITS = AMOUNT_PRECISION - AMOUNT_SCALE;

	/** Day-first and year-first only; month-first is ambiguous and refused. */
	private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
			strict("uuuu-MM-dd"),
			strict("dd/MM/uuuu"),
			strict("dd-MM-uuuu"),
			strict("dd.MM.uuuu"),
			strict("uuuu/MM/dd"),
			strict("dd MMM uuuu"));

	private static final List<DateTimeFormatter> DATE_TIME_FORMATS = List.of(
			DateTimeFormatter.ISO_LOCAL_DATE_TIME,
			strict("uuuu-MM-dd HH:mm:ss"),
			strict("dd/MM/uuuu HH:mm:ss"),
			strict("dd-MM-uuuu HH:mm:ss"));

	private TypedValueParser() {
	}

	/**
	 * @throws ValueFormatException when the text is not a plain decimal
	 */
	public static BigDecimal decimal(String raw) {
		String value = normalise(raw);
		if (value.isEmpty()) {
			throw new ValueFormatException(RejectionReason.MISSING_REQUIRED_VALUE, "the value is empty");
		}
		if (value.charAt(0) == '\'') {
			throw new ValueFormatException(RejectionReason.INVALID_FIELD_FORMAT,
					"the value carries a protective escape prefix and is not a number");
		}
		try {
			return new BigDecimal(value);
		}
		catch (NumberFormatException ex) {
			throw new ValueFormatException(RejectionReason.INVALID_AMOUNT,
					"expected a plain decimal such as 1234.56; grouping separators and symbols are not accepted");
		}
	}

	/**
	 * Parses an amount and checks it fits {@code NUMERIC(20,4)}.
	 *
	 * @throws ValueFormatException when the text is not a decimal, has more than
	 * {@value #AMOUNT_SCALE} fraction digits, or exceeds {@value #AMOUNT_PRECISION}
	 * significant digits
	 */
	public static BigDecimal amount(String raw) {
		BigDecimal value = decimal(raw);
		if (value.scale() > AMOUNT_SCALE) {
			throw new ValueFormatException(RejectionReason.VALUE_OUT_OF_RANGE,
					"the value has " + Math.max(value.scale(), 0) + " fraction digits but an amount stores at most "
							+ AMOUNT_SCALE + "; it is refused rather than silently rounded");
		}
		int integerDigits = integerDigits(value);
		if (integerDigits > MAX_INTEGER_DIGITS) {
			throw new ValueFormatException(RejectionReason.VALUE_OUT_OF_RANGE,
					"the value has " + integerDigits + " integer digits but an amount stores at most "
							+ MAX_INTEGER_DIGITS);
		}
		return value;
	}

	/**
	 * @return the value exactly as parsed by {@link #decimal(String)}, for columns
	 * that are numeric but not monetary and so are not subject to the money scale
	 */
	public static BigDecimal quantity(String raw) {
		BigDecimal value = decimal(raw);
		if (integerDigits(value) > 14) {
			throw new ValueFormatException(RejectionReason.VALUE_OUT_OF_RANGE,
					"the quantity has too many integer digits to fit NUMERIC(20,6)");
		}
		if (value.scale() > 6) {
			throw new ValueFormatException(RejectionReason.VALUE_OUT_OF_RANGE,
					"the quantity has more than 6 fraction digits; it is refused rather than silently rounded");
		}
		return value;
	}

	/**
	 * @throws ValueFormatException when the text is not a supported date layout or
	 * names a date that does not exist
	 */
	public static LocalDate date(String raw) {
		String value = normalise(raw);
		if (value.isEmpty()) {
			throw new ValueFormatException(RejectionReason.MISSING_REQUIRED_VALUE, "the date is empty");
		}
		for (DateTimeFormatter formatter : DATE_FORMATS) {
			try {
				return LocalDate.parse(value, formatter);
			}
			catch (DateTimeParseException ex) {
				// try the next, unambiguous, layout
			}
		}
		throw new ValueFormatException(RejectionReason.INVALID_DATE,
				"expected a date such as 2024-01-31 or 31/01/2024; ambiguous month-first layouts are not accepted");
	}

	public static LocalDateTime dateTime(String raw) {
		String value = normalise(raw);
		if (value.isEmpty()) {
			throw new ValueFormatException(RejectionReason.MISSING_REQUIRED_VALUE, "the timestamp is empty");
		}
		for (DateTimeFormatter formatter : DATE_TIME_FORMATS) {
			try {
				return LocalDateTime.parse(value, formatter);
			}
			catch (DateTimeParseException ex) {
				// try the next layout
			}
		}
		LocalDate date = date(value);
		return date.atStartOfDay();
	}

	/**
	 * @throws ValueFormatException when the text is not a three-letter ISO-4217 code
	 */
	public static CurrencyCode currency(String raw) {
		String value = normalise(raw);
		if (value.isEmpty()) {
			throw new ValueFormatException(RejectionReason.MISSING_REQUIRED_VALUE, "the currency is empty");
		}
		try {
			return CurrencyCode.of(value);
		}
		catch (IllegalArgumentException ex) {
			throw new ValueFormatException(RejectionReason.INVALID_CURRENCY,
					"expected a three-letter ISO-4217 currency code such as INR");
		}
	}

	public static boolean booleanValue(String raw) {
		String value = normalise(raw).toLowerCase(Locale.ROOT);
		if ("true".equals(value) || "yes".equals(value) || "y".equals(value) || "1".equals(value)) {
			return true;
		}
		if ("false".equals(value) || "no".equals(value) || "n".equals(value) || "0".equals(value)) {
			return false;
		}
		throw new ValueFormatException(RejectionReason.INVALID_FIELD_FORMAT, "expected a boolean value");
	}

	/**
	 * @return true when the text parses as a plain decimal, used by header
	 * detection and by the formula-injection test
	 */
	public static boolean isDecimal(String raw) {
		String value = normalise(raw);
		if (value.isEmpty() || value.charAt(0) == '\'') {
			return false;
		}
		try {
			new BigDecimal(value);
			return true;
		}
		catch (NumberFormatException ex) {
			return false;
		}
	}

	private static DateTimeFormatter strict(String pattern) {
		return DateTimeFormatter.ofPattern(pattern, Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);
	}

	private static int integerDigits(BigDecimal value) {
		BigDecimal normalised = value.stripTrailingZeros();
		BigInteger unscaled = normalised.unscaledValue().abs();
		int digits = unscaled.toString().length();
		int integerDigits = digits + normalised.scale();
		return Math.max(integerDigits, 1);
	}

	private static String normalise(String raw) {
		return raw == null ? "" : raw.trim();
	}

	/**
	 * Thrown when a value cannot be read as its declared type.
	 *
	 * <p>Unchecked so the per-row validators can let it travel up to the one place
	 * that knows the row and column, while still carrying the precise reason that
	 * becomes the finding.
	 */
	public static final class ValueFormatException extends RuntimeException {

		private static final long serialVersionUID = 1L;

		private final RejectionReason reason;

		ValueFormatException(RejectionReason reason, String message) {
			super(message);
			this.reason = Objects.requireNonNull(reason, "reason must not be null");
		}

		public RejectionReason reason() {
			return this.reason;
		}

	}

}
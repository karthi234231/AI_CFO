package com.fintech.cfo.shared.validation;

import java.math.BigDecimal;

import org.jspecify.annotations.Nullable;

import com.fintech.cfo.shared.domain.Money;
import com.fintech.cfo.shared.exception.ValidationException;

/**
 * The guard clauses used by every module's compact constructor.
 *
 * <p>Centralised deliberately. These were twenty-six hand-copied private helpers spread over
 * twenty files, and the copies had drifted: {@code requireScale} raised
 * {@code NullPointerException} for a null amount in {@code Invoice} and
 * {@code InvoiceLine} while every neighbouring helper raised {@link ValidationException}, so
 * the same defect produced a 500 in one model and a 400 in another. A guard whose behaviour
 * depends on which file it was pasted into is not a guard.
 *
 * <p>Every failure is a {@link ValidationException} and names the offending field, because the
 * message is what a reviewer reads when a migrated file turns out to violate a constraint the
 * record refuses to represent.
 */
public final class Preconditions {

	/** Utility class: not instantiable, and not intended to be subclassed. */
	private Preconditions() {
	}

	/**
	 * @param value value to check
	 * @param field name used in the failure message
	 * @return {@code value}, or fails if it is {@code null}
	 * @throws ValidationException if {@code value} is null
	 */
	public static <T> T requireNonNull(T value, String field) {
		if (value == null) {
			throw new ValidationException(field + " must not be null");
		}
		return value;
	}

	/**
	 * @param condition invariant that must hold
	 * @param message explanation naming the violated rule
	 * @throws ValidationException if {@code condition} is false
	 */
	public static void require(boolean condition, String message) {
		if (!condition) {
			throw new ValidationException(message);
		}
	}

	/**
	 * @param value text to normalise
	 * @param field name used in the failure message
	 * @return {@code value} trimmed, failing if it is {@code null} or blank
	 * @throws ValidationException if {@code value} is null or blank
	 */
	public static String requireText(String value, String field) {
		if (value == null) {
			throw new ValidationException(field + " must not be null");
		}
		String trimmed = value.trim();
		if (trimmed.isEmpty()) {
			throw new ValidationException(field + " must not be blank");
		}
		return trimmed;
	}

	/**
	 * Returns {@code value} trimmed, failing if it is {@code null}, blank, or wider than the
	 * declared column.
	 *
	 * <p>Trims before measuring, not after: a value that only fits because of padding would be
	 * accepted here and then truncated by the database, leaving the in-memory value and the
	 * stored value disagreeing about the same row.
	 *
	 * @param value     text to normalise
	 * @param field     name used in the failure message
	 * @param maxLength declared column width the value must fit
	 * @return {@code value} trimmed
	 * @throws ValidationException if the value is null, blank, or longer than
	 *                             {@code maxLength}
	 */
	public static String requireText(String value, String field, int maxLength) {
		String trimmed = requireText(value, field);
		if (trimmed.length() > maxLength) {
			throw new ValidationException(field + " exceeds the declared column width of " + maxLength);
		}
		return trimmed;
	}

	/**
	 * Normalises an optional text column to {@code null} when it carries nothing.
	 *
	 * <p>Blank collapses to {@code null} rather than persisting an empty string, so
	 * {@code isPresent()} on the domain type means what it says instead of being true for a
	 * value that renders as nothing.
	 *
	 * @param value     possibly absent text
	 * @param field     name used in the failure message
	 * @param maxLength declared column width the value must fit
	 * @return {@code value} trimmed, or {@code null} when it was null or blank
	 * @throws ValidationException if a non-blank value exceeds {@code maxLength}
	 */
	public static @Nullable String optionalText(@Nullable String value, String field, int maxLength) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		if (trimmed.isEmpty()) {
			return null;
		}
		if (trimmed.length() > maxLength) {
			throw new ValidationException(field + " exceeds the declared column width of " + maxLength);
		}
		return trimmed;
	}

	/**
	 * Verifies a monetary amount fits its {@code NUMERIC(precision, scale)} column.
	 *
	 * <p>Scale is checked first and the digit budget second, because {@code precision} counts
	 * significant digits including those fractional ones: testing a value with too many decimal
	 * places against the precision budget first would accept {@code 0.000001} on a
	 * {@code NUMERIC(20,4)} column and reject it only by accident of how the digits happened to
	 * line up.
	 *
	 * @param value     amount to check
	 * @param field     name used in the failure message
	 * @param precision declared precision of the target column
	 * @param scale     declared scale of the target column
	 * @return {@code value}, unchanged, so this can wrap an assignment inline
	 * @throws ValidationException if the amount cannot be stored as declared
	 */
	public static Money requireNumeric(Money value, String field, int precision, int scale) {
		requireNonNull(value, field);
		requireNumeric(value.amount(), field, precision, scale);
		return value;
	}

	/**
	 * Verifies a decimal fits its {@code NUMERIC(precision, scale)} column.
	 *
	 * <p>Zero is exempt from the scale check: {@code BigDecimal} keeps the scale it was created
	 * with, so {@code BigDecimal.ZERO} is {@code 0E-9} and would otherwise be rejected for
	 * carrying nine decimal places, which no column ever needs to record.
	 *
	 * @param value     decimal to check
	 * @param field     name used in the failure message
	 * @param precision declared precision of the target column
	 * @param scale     declared scale of the target column
	 * @return {@code value}, unchanged, so this can wrap an assignment inline
	 * @throws ValidationException if the value cannot be stored as declared
	 */
	public static BigDecimal requireNumeric(BigDecimal value, String field, int precision, int scale) {
		requireNonNull(value, field);
		if (value.signum() != 0 && value.scale() > scale) {
			throw new ValidationException(field + " must not carry more than " + scale + " decimal places");
		}
		int integerDigits = value.precision() - value.scale();
		if (integerDigits > precision - scale) {
			throw new ValidationException(field
					+ " exceeds the declared column width NUMERIC(" + precision + "," + scale + ")");
		}
		return value;
	}

	/**
	 * @param value   integer to check
	 * @param minimum inclusive lower bound
	 * @param field   name used in the failure message
	 * @return {@code value}, so this can wrap an assignment inline
	 * @throws ValidationException if {@code value} is below the bound
	 */
	public static int requireAtLeast(int value, int minimum, String field) {
		if (value < minimum) {
			throw new ValidationException(field + " must be at least " + minimum + " but was " + value);
		}
		return value;
	}

	/**
	 * @param value   integer to check
	 * @param minimum inclusive lower bound
	 * @param field   name used in the failure message
	 * @return {@code value}, so this can wrap an assignment inline
	 * @throws ValidationException if {@code value} is below the bound
	 */
	public static long requireAtLeast(long value, long minimum, String field) {
		if (value < minimum) {
			throw new ValidationException(field + " must be at least " + minimum + " but was " + value);
		}
		return value;
	}

	/**
	 * Requires a 1-based coordinate, because a 0-based number stored beside a 1-based parser is an off-by-one that looks correct.
	 *
	 * @param value row or line number
	 * @param field name used in the failure message
	 * @return {@code value}, so this can wrap an assignment inline
	 * @throws ValidationException if {@code value} is zero or negative
	 */
	public static long requirePositive(long value, String field) {
		if (value <= 0) {
			throw new ValidationException(field + " must be 1-based and positive but was " + value);
		}
		return value;
	}

}

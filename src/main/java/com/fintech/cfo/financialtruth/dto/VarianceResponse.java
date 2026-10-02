package com.fintech.cfo.financialtruth.dto;

import java.math.BigDecimal;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import com.fintech.cfo.financialtruth.enums.ImpactDirection;
import com.fintech.cfo.financialtruth.enums.VarianceType;
import com.fintech.cfo.financialtruth.model.Variance;
import com.fintech.cfo.shared.exception.ValidationException;

/**
 * A variance as reported to a client.
 *
 * <p>{@code direction} is included so a consumer does not have to re-derive the sign
 * convention from scratch, and {@code percentageOfExpected} is omitted rather than
 * sent as zero when the expected amount is zero - an undefined ratio reported as a
 * number is the kind of small lie that ends up in a board pack.
 *
 * @param percentageOfExpected null when the expected amount is zero and the ratio is
 *                             therefore undefined; never zero standing in for it
 */
public record VarianceResponse(
		AmountResponse amount,
		VarianceType varianceType,
		ImpactDirection direction,
		AmountResponse expected,
		AmountResponse actual,
		@Nullable BigDecimal percentageOfExpected) {

	public VarianceResponse {
		if (amount == null) {
			throw new ValidationException("a reported variance must carry its amount and currency");
		}
		if (varianceType == null) {
			throw new ValidationException("varianceType must not be null");
		}
		if (direction == null) {
			throw new ValidationException("direction must not be null");
		}
	}

	/**
	 * Converts a variance, or returns null when this result carries no variance.
	 *
	 * <p>{@code percentageOfExpected} is carried through as null when the expected amount
	 * is zero. Reporting 0.0 there would state "no material deviation", which is a claim
	 * the underlying figures do not support.
	 */
	public static @Nullable VarianceResponse from(@Nullable Variance variance) {
		if (variance == null) {
			return null;
		}
		return new VarianceResponse(AmountResponse.from(variance.amount()), variance.type(), variance.direction(),
				AmountResponse.from(variance.expected()), AmountResponse.from(variance.actual()),
				variance.percentageOfExpected().orElse(null));
	}

	/** The variance as a percentage of the expected amount, absent when undefined. */
	public Optional<BigDecimal> percentage() {
		return Optional.ofNullable(this.percentageOfExpected);
	}

}
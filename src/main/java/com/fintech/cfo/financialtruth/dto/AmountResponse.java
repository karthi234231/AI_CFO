package com.fintech.cfo.financialtruth.dto;

import java.math.BigDecimal;

import org.jspecify.annotations.Nullable;

import com.fintech.cfo.shared.domain.Money;
import com.fintech.cfo.shared.exception.ValidationException;

/**
 * An amount paired with its currency for transport.
 *
 * <p>The currency is a separate field rather than a formatted string so a client
 * never has to parse {@code "1,234.00 INR"} to find out what it is holding, and
 * cannot mistake a number without a currency for a number with one.
 */
public record AmountResponse(BigDecimal amount, String currency) {

	public AmountResponse {
		if (amount == null) {
			throw new ValidationException("amount must not be null");
		}
		if (currency == null || currency.isBlank()) {
			throw new ValidationException("an amount without a currency is never reported");
		}
	}

	/**
	 * Converts a {@code Money}, or returns null when there is no amount to report.
	 *
	 * <p>Null here means "this module established no figure", which is a genuine
	 * answer for an {@code INCOMPLETE_INPUTS} row. It is deliberately not a
	 * zero-amount response: a client that reads 0.00 as "no variance" would then
	 * report a clean invoice for a line nobody could evaluate.
	 */
	public static @Nullable AmountResponse from(@Nullable Money money) {
		if (money == null) {
			return null;
		}
		return new AmountResponse(money.amount(), money.currency().value());
	}

}
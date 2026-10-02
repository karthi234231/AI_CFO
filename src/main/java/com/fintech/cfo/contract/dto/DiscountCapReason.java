package com.fintech.cfo.contract.dto;

import java.io.Serializable;
import java.util.List;

import com.fintech.cfo.shared.exception.ValidationException;

/**
 * Why a discount was reduced from the value its own terms compute.
 *
 * <p>Both reasons are caps rather than errors, but they mean different things to
 * finance and must not be collapsed: {@link #MAX_DISCOUNT_AMOUNT} is a limit a
 * contract author wrote down, whereas {@link #GROSS_AMOUNT_LIMIT} is a property of
 * the arithmetic that no contract needed to state. A reviewer investigating an
 * over-generous discount needs to tell them apart.
 */
public sealed interface DiscountCapReason {

	/**
	 * Code used in the wire form and in the checksum.
	 */
	String code();

	/** The discount was not reduced. */
	record None() implements DiscountCapReason {

		public static final None INSTANCE = new None();

		@Override
		public String code() {
			return "NONE";
		}

	}

	/** Capped by {@code discount_terms.max_discount_amount}. */
	record MaxDiscountAmount() implements DiscountCapReason {

		public static final MaxDiscountAmount INSTANCE = new MaxDiscountAmount();

		@Override
		public String code() {
			return "MAX_DISCOUNT_AMOUNT";
		}

	}

	/**
	 * Capped at the gross amount, because a discount larger than what is being
	 * billed would turn the invoice into a document the supplier owes money on.
	 */
	record GrossAmountLimit() implements DiscountCapReason {

		public static final GrossAmountLimit INSTANCE = new GrossAmountLimit();

		@Override
		public String code() {
			return "GROSS_AMOUNT_LIMIT";
		}

	}

	/**
	 * Resolves a stored cap reason.
	 *
	 * @throws ValidationException if the value is not a known reason, so a
	 * persisted result can never be misread as a different kind of cap
	 */
	static DiscountCapReason fromCode(String code) {
		if (code == null || code.isBlank()) {
			throw new ValidationException("discount cap reason must not be blank");
		}
		String normalized = code.trim();
		for (DiscountCapReason candidate : List.of(None.INSTANCE, MaxDiscountAmount.INSTANCE, GrossAmountLimit.INSTANCE)) {
			if (candidate.code().equals(normalized)) {
				return candidate;
			}
		}
		throw new ValidationException("unknown discount cap reason: " + code);
	}

	/**
	 * Whether this reason represents any reduction at all.
	 */
	default boolean isCapped() {
		return switch (this) {
			case None none -> false;
			case MaxDiscountAmount max -> true;
			case GrossAmountLimit gross -> true;
		};
	}

}

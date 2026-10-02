package com.fintech.cfo.financialtruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.fintech.cfo.shared.domain.CurrencyCode;
import com.fintech.cfo.shared.domain.Money;

/**
 * Contract tests for {@link Money}, the type every financial result is built
 * from.
 *
 * <p>Pure unit tests: no Spring context and no database.
 *
 * <p><b>Why this suite is load-bearing.</b> {@code Money} is the only type the
 * platform is allowed to hold an amount in, so a defect here is not a defect in
 * one feature - it silently reappears in every variance, every total and every
 * report, and it does so plausibly: a dropped paisa in a large invoice still
 * looks like a number. The three properties locked down below are the ones whose
 * failure would be invisible downstream:
 *
 * <ul>
 * <li><b>Currency safety.</b> Arithmetic and comparison across two currencies
 * throw instead of returning a figure. There is no FX component in this
 * codebase, so a silent conversion here would invent a rate nobody audited and
 * every later number would inherit it.</li>
 * <li><b>Exact decimal arithmetic.</b> Every operation is {@link BigDecimal}.
 * The explicit assertions on {@code 0.1 * 0.2} and on amounts far past the range
 * of a {@code double} exist because the floating-point failure mode produces a
 * number that is wrong in the last digits and right everywhere else.</li>
 * <li><b>Explicit scale.</b> Division and rounding take an explicit scale and
 * {@link RoundingMode} and never a default. Two callers rounding the same figure
 * differently is the difference between a reproducible calculation and one that
 * cannot be re-derived months later.</li>
 * </ul>
 */
class MoneyTest {

	private static final CurrencyCode INR = CurrencyCode.inr();

	private static final CurrencyCode USD = CurrencyCode.usd();

	@Nested
	@DisplayName("currency safety")
	class CurrencySafety {

		@Test
		void rejectsArithmeticAcrossCurrencies() {
			Money rupees = Money.of("100.00", INR);
			Money dollars = Money.of("100.00", USD);

			assertThatThrownBy(() -> rupees.add(dollars))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("currency mismatch");
		}

		@Test
		void rejectsComparisonAcrossCurrencies() {
			Money rupees = Money.of("100.00", INR);
			Money dollars = Money.of("1.00", USD);

			assertThatThrownBy(() -> rupees.compareTo(dollars)).isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		void normalizesCaseAndPaddingOnConstruction() {
			assertThat(CurrencyCode.of(" inr ")).isEqualTo(INR);
		}

		@Test
		void rejectsMalformedCurrencyCode() {
			assertThatThrownBy(() -> CurrencyCode.of("RUPEE")).isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> CurrencyCode.of("IN")).isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Nested
	@DisplayName("arithmetic")
	class Arithmetic {

		@Test
		void addsAndSubtractsExactly() {
			Money base = Money.of("1000.0001", INR);

			assertThat(base.add(Money.of("0.0002", INR)).amount()).isEqualByComparingTo("1000.0003");
			assertThat(base.subtract(Money.of("0.0001", INR)).amount()).isEqualByComparingTo("1000.0000");
		}

		@Test
		void multipliesWithoutLosingPrecision() {
			// 0.1 * 0.2 is famously inexact in binary floating point; the exact
			// decimal result must survive here.
			Money result = Money.of("0.1", INR).multiply(new BigDecimal("0.2"));

			assertThat(result.amount()).isEqualByComparingTo("0.02");
			assertThat(result.amount().toPlainString()).isEqualTo("0.02");
		}

		@Test
		void divisionRequiresExplicitScaleAndRounding() {
			Money amount = Money.of("10.00", INR);

			assertThat(amount.divide(new BigDecimal("3"), 2, RoundingMode.HALF_UP).amount())
				.isEqualByComparingTo("3.33");
			assertThat(amount.divide(new BigDecimal("3"), 2, RoundingMode.DOWN).amount()).isEqualByComparingTo("3.33");
			assertThat(amount.divide(new BigDecimal("3"), 4, RoundingMode.HALF_UP).amount())
				.isEqualByComparingTo("3.3333");
		}

		@Test
		void rejectsDivisionByZeroAndNegativeScale() {
			Money amount = Money.of("10.00", INR);

			assertThatThrownBy(() -> amount.divide(BigDecimal.ZERO, 2, RoundingMode.HALF_UP))
				.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> amount.divide(new BigDecimal("2"), -1, RoundingMode.HALF_UP))
				.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		void computesLargeAmountsExactly() {
			// Well beyond the range where a double would silently lose cents.
			Money quantity = Money.of("10000000000.000000", INR);
			Money unitPrice = Money.of("920.00", INR);

			Money total = quantity.multiply(unitPrice.amount());

			assertThat(total.amount()).isEqualByComparingTo("9200000000000.000000");
			// Scale is the sum of the operand scales (6 + 2 = 8), so the value is
			// exact but not yet at the storage scale. Callers must normalise with
			// withScale(...) before persisting to a NUMERIC(20,4) column.
			assertThat(total.withScale(4, RoundingMode.HALF_UP).amount().toPlainString())
				.isEqualTo("9200000000000.0000");
		}

		@Test
		void multiplicationScaleIsTheSumOfOperandScales() {
			Money total = Money.of("10.000", INR).multiply(new BigDecimal("2.00"));

			assertThat(total.amount().scale()).isEqualTo(5);
			assertThat(total.amount().toPlainString()).isEqualTo("20.00000");
		}
	}

	@Nested
	@DisplayName("sign and comparison")
	class SignAndComparison {

		@Test
		void reportsSign() {
			assertThat(Money.of("-0.01", INR).isNegative()).isTrue();
			assertThat(Money.of("0.01", INR).isPositive()).isTrue();
			assertThat(Money.zero(INR).isZero()).isTrue();
		}

		@Test
		void ignoresTrailingZerosForEquality() {
			// Equality must be numeric, not textual, or 100.00 and 100.000 become
			// different amounts and every checksum, dedup and total breaks.
			assertThat(Money.of("100.00", INR)).isEqualTo(Money.of("100.000", INR));
			assertThat(Money.of("100.00", INR)).hasSameHashCodeAs(Money.of("100.000", INR));
		}

		@Test
		void distinguishesDifferentCurrenciesWithEqualAmounts() {
			// 100.00 INR is not 100.00 USD. This is the arithmetic rule from above
			// applied to equality, where a wrong answer would be a wrong total.
			assertThat(Money.of("100.00", INR)).isNotEqualTo(Money.of("100.00", USD));
		}

		@Test
		void negatesAndAbsolutes() {
			assertThat(Money.of("-42.50", INR).negate().amount()).isEqualByComparingTo("42.50");
			assertThat(Money.of("-42.50", INR).abs().amount()).isEqualByComparingTo("42.50");
		}
	}

	@Nested
	@DisplayName("scale control")
	class ScaleControl {

		@Test
		void appliesExplicitRounding() {
			Money amount = Money.of("10.005", INR);

			assertThat(amount.withScale(2, RoundingMode.HALF_UP).amount().toPlainString()).isEqualTo("10.01");
			assertThat(amount.withScale(2, RoundingMode.DOWN).amount().toPlainString()).isEqualTo("10.00");
			assertThat(amount.withScale(2, RoundingMode.UP).amount().toPlainString()).isEqualTo("10.01");
		}

		@Test
		void storesValueWithoutExponentNotation() {
			// toPlainString, not toString: BigDecimal.toString would render this as
			// 1E-8, and that form does not round-trip through a NUMERIC(20,4) column
			// or through a CSV export the way a finance analyst expects.
			assertThat(Money.of("0.00000001", INR).amount().toPlainString()).isEqualTo("0.00000001");
		}
	}

}
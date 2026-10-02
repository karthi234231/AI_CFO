package com.fintech.cfo.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.fintech.cfo.contract.dto.DiscountCapReason;
import com.fintech.cfo.contract.dto.DiscountEvaluation;
import com.fintech.cfo.contract.dto.EffectiveTerms;
import com.fintech.cfo.contract.dto.EffectiveTermsQuery;
import com.fintech.cfo.contract.dto.ResolvedPrice;
import com.fintech.cfo.contract.enums.ContractStatus;
import com.fintech.cfo.contract.enums.ContractTermType;
import com.fintech.cfo.contract.enums.CommercialRuleType;
import com.fintech.cfo.contract.enums.DiscountType;
import com.fintech.cfo.contract.enums.PricingType;
import com.fintech.cfo.contract.model.CommercialRule;
import com.fintech.cfo.contract.model.CommercialRuleParameters;
import com.fintech.cfo.contract.model.Contract;
import com.fintech.cfo.contract.model.ContractTerm;
import com.fintech.cfo.contract.model.DiscountTerm;
import com.fintech.cfo.contract.model.EffectiveWindow;
import com.fintech.cfo.contract.model.PricingTerm;
import com.fintech.cfo.contract.service.ContractService;
import com.fintech.cfo.contract.service.DiscountService;
import com.fintech.cfo.contract.service.EffectiveTermResolver;
import com.fintech.cfo.contract.service.PriceResolutionService;
import com.fintech.cfo.contract.service.TermSelector;
import com.fintech.cfo.shared.domain.CurrencyCode;
import com.fintech.cfo.shared.domain.Money;
import com.fintech.cfo.shared.domain.OrganizationId;
import com.fintech.cfo.shared.exception.BusinessRuleException;

/**
 * Pure unit tests for commercial-term resolution. No Spring context, no
 * database, no clock: every as-of date is passed in explicitly, which is the
 * property these tests exist to protect.
 *
 * <p><strong>How these tests name the production taxonomy.</strong>
 * {@code ContractStatus}, {@code PricingType}, {@code DiscountType},
 * {@code ContractTermType}, {@code CommercialRuleType} and
 * {@code DiscountCapReason} are sealed interfaces over records rather than Java
 * enums, so their members are {@code Foo.INSTANCE} constants, not
 * {@code FOO} enum constants. They are named here exactly as production names
 * them; the code that matters for a stored row is the {@code code()} each of
 * them returns, and that is what the assertions on failure messages check.
 *
 * <p><strong>Lookups raise, they do not return null.</strong>
 * {@code ContractService.pricingTermInForce} throws
 * {@link BusinessRuleException} when the contract supplies nothing, so a test
 * that wants "no term here" asserts the throw. Returning null would oblige the
 * caller to invent a price, which is the failure this module exists to prevent.
 */
class ContractServiceTest {

	private static final OrganizationId ORGANIZATION = OrganizationId
			.fromString("11111111-1111-1111-1111-111111111111");

	private static final UUID CONTRACT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
	private static final UUID PRODUCT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
	private static final UUID OTHER_PRODUCT_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
	private static final UUID CUSTOMER_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
	private static final UUID OTHER_CUSTOMER_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");

	private static final CurrencyCode INR = CurrencyCode.inr();
	private static final CurrencyCode USD = CurrencyCode.usd();

	private static final LocalDate Q1_START = LocalDate.of(2024, 1, 1);
	private static final LocalDate Q1_END = LocalDate.of(2024, 3, 31);
	private static final LocalDate Q2_START = LocalDate.of(2024, 4, 1);
	private static final LocalDate MID_Q2 = LocalDate.of(2024, 6, 1);

	// The collaborators are built in dependency order, exactly as ContractService's
	// own no-argument constructor builds them: the selector is derived from the
	// resolver, and pricing/discount resolution are handed the same selector, so
	// these tests exercise the production collaborator graph rather than four
	// independently wired objects.
	private final EffectiveTermResolver resolver = new EffectiveTermResolver();
	private final TermSelector selector = new TermSelector(resolver);
	private final DiscountService discountService = new DiscountService(selector);
	private final PriceResolutionService priceService = new PriceResolutionService(resolver, selector);
	private final ContractService service = new ContractService();

	// ---------------------------------------------------------------- fixtures

	private static Contract activeContract() {
		return new Contract(CONTRACT_ID, ORGANIZATION, CUSTOMER_ID, "C-2024-001", "Master supply agreement",
				ContractStatus.Active.INSTANCE, INR, Q1_START, null, null, "file://contracts/2024/c-2024-001.pdf", 3L);
	}

	/**
	 * Deterministic ids derived from a seed so repeated runs of this test file
	 * compare like with like.
	 */
	private static UUID id(String seed) {
		return UUID.nameUUIDFromBytes(("contract-test:" + seed).getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * A contract-wide default {@code FIXED_UNIT} price in INR, which is the shape
	 * most of these tests want and the shape a V5 row takes when neither scope
	 * column is set.
	 */
	private static PricingTerm pricingTerm(String seed, int termVersion, LocalDate from, LocalDate to,
			Money unitPrice, Money minimum, Money maximum) {
		return pricingTerm(seed, termVersion, from, to, PricingType.FixedUnit.INSTANCE, unitPrice, minimum, maximum);
	}

	/**
	 * Same, with the pricing type stated: a {@code TIERED} row is a band rather
	 * than a published price, and several tests below are only meaningful for
	 * that type.
	 */
	private static PricingTerm pricingTerm(String seed, int termVersion, LocalDate from, LocalDate to, PricingType type,
			Money unitPrice, Money minimum, Money maximum) {
		return pricingTerm(seed, termVersion, from, to, type, unitPrice, minimum, maximum, INR);
	}

	/**
	 * Same, in a currency of its own. The pricing type and the currency are
	 * independent columns, so a row may legitimately be, say, a tiered band stated
	 * in a currency the contract does not use.
	 */
	private static PricingTerm pricingTerm(String seed, int termVersion, LocalDate from, LocalDate to, PricingType type,
			Money unitPrice, Money minimum, Money maximum, CurrencyCode currency) {
		return pricingTerm(seed, termVersion, from, to, null, null, type, unitPrice, minimum, maximum, currency);
	}

	/**
	 * The full row: scope columns, pricing type and currency. Used by the tests
	 * that turn on specificity or on currency rather than on the amount.
	 */
	private static PricingTerm pricingTerm(String seed, int termVersion, LocalDate from, LocalDate to, UUID productId,
			UUID customerId, PricingType type, Money unitPrice, Money minimum, Money maximum, CurrencyCode currency) {
		return new PricingTerm(id(seed), ORGANIZATION, CONTRACT_ID, productId, customerId, type, unitPrice, minimum,
				maximum, currency, EffectiveWindow.of(from, to), termVersion, 0L);
	}

	/**
	 * A {@code PERCENTAGE} discount row. Called through the outer class name
	 * everywhere, because one of the test methods below is itself called
	 * {@code percentageDiscount()} and would otherwise shadow this fixture
	 * helper for the whole nested class.
	 *
	 * <p>The currency column is null for a percentage with no cap and, when a
	 * {@code max_discount_amount} is supplied, the cap's own currency: a
	 * {@code max_discount_amount} is an amount, and V5 has no second currency
	 * column for it, so the row's currency is what pins it. A capped percentage
	 * row that left the column null would be a cap nobody could say what it was
	 * denominated in.
	 */
	private static DiscountTerm percentageDiscount(String seed, int termVersion, LocalDate from, LocalDate to,
			String percentage, Money maxDiscountAmount) {
		CurrencyCode currency = maxDiscountAmount == null ? null : maxDiscountAmount.currency();
		return new DiscountTerm(id(seed), ORGANIZATION, CONTRACT_ID, null, null, DiscountType.Percentage.INSTANCE,
				new BigDecimal(percentage), maxDiscountAmount, currency,
				EffectiveWindow.of(from, to), termVersion, 0L);
	}

	private static DiscountTerm fixedDiscount(String seed, int termVersion, LocalDate from, LocalDate to,
			String amount, CurrencyCode currency, Money maxDiscountAmount) {
		return new DiscountTerm(id(seed), ORGANIZATION, CONTRACT_ID, null, null, DiscountType.FixedAmount.INSTANCE,
				new BigDecimal(amount), maxDiscountAmount, currency,
				EffectiveWindow.of(from, to), termVersion, 0L);
	}

	private static Money inr(String amount) {
		return Money.of(new BigDecimal(amount), INR);
	}

	// ----------------------------------------------------------- effective-term

	@Nested
	@DisplayName("effective-term resolution")
	class EffectiveTermResolution {

		@Test
		@DisplayName("a window is inclusive on both ends")
		void windowIsInclusiveOnBothEnds() {
			PricingTerm term = pricingTerm("q1", 1, Q1_START, Q1_END, inr("920"), null, null);

			assertThat(resolver.inForceOn(List.of(term), Q1_START)).containsExactly(term);
			assertThat(resolver.inForceOn(List.of(term), Q1_END)).containsExactly(term);
		}

		@Test
		@DisplayName("the day after effective_to is already out of force")
		void dayAfterEffectiveToIsOutOfForce() {
			PricingTerm term = pricingTerm("q1", 1, Q1_START, Q1_END, inr("920"), null, null);

			assertThat(resolver.inForceOn(List.of(term), Q2_START)).isEmpty();
			// The service-level form of the same question raises rather than answering
			// null: a caller handed null would have to substitute a price of its own,
			// and an invented price is indistinguishable from an agreed one on re-run.
			assertThatThrownBy(() -> service.pricingTermInForce(activeContract(), List.of(term), null, null, Q2_START))
					.isInstanceOf(BusinessRuleException.class)
					.hasMessageContaining("no pricing term in force");
		}

		@Test
		@DisplayName("the day before effective_from is not yet in force")
		void dayBeforeEffectiveFromIsNotInForce() {
			PricingTerm term = pricingTerm("q1", 1, Q1_START, Q1_END, inr("920"), null, null);

			assertThat(resolver.inForceOn(List.of(term), Q1_START.minusDays(1))).isEmpty();
		}

		@Test
		@DisplayName("an open-ended window never expires")
		void openEndedWindowNeverExpires() {
			PricingTerm term = pricingTerm("open", 1, Q1_START, null, inr("920"), null, null);

			assertThat(term.effectiveWindow().isOpenEnded()).isTrue();
			assertThat(resolver.inForceOn(List.of(term), LocalDate.of(2099, 12, 31))).containsExactly(term);
		}

		@Test
		@DisplayName("an open-ended window loses to a bounded one that supersedes it on the same version")
		void openEndedWindowLosesToBoundedWindowAtEqualVersion() {
			PricingTerm openEnded = pricingTerm("open", 1, Q1_START, null, inr("920"), null, null);
			PricingTerm bounded = pricingTerm("bounded", 1, MID_Q2, Q2_START.plusMonths(2), inr("1000"), null, null);

			assertThat(resolver.findInForce(List.of(openEnded, bounded), MID_Q2)).contains(bounded);
			assertThat(resolver.findInForce(List.of(bounded, openEnded), MID_Q2)).contains(bounded);
		}

		@Test
		@DisplayName("highest term_version wins an overlap, whatever the list order")
		void highestTermVersionWinsOverlap() {
			PricingTerm v1 = pricingTerm("v1", 1, Q1_START, null, inr("920"), null, null);
			PricingTerm v2 = pricingTerm("v2", 2, Q1_START, null, inr("950"), null, null);

			assertThat(resolver.findInForce(List.of(v1, v2), MID_Q2)).contains(v2);
			assertThat(resolver.findInForce(List.of(v2, v1), MID_Q2)).contains(v2);
		}

		@Test
		@DisplayName("at equal version the latest effective_from wins")
		void latestEffectiveFromWinsAtEqualVersion() {
			PricingTerm wide = pricingTerm("wide", 1, Q1_START, null, inr("920"), null, null);
			PricingTerm correction = pricingTerm("correction", 1, MID_Q2, null, inr("1000"), null, null);

			assertThat(resolver.findInForce(List.of(wide, correction), MID_Q2)).contains(correction);
			assertThat(resolver.findInForce(List.of(correction, wide), MID_Q2)).contains(correction);
		}

		@Test
		@DisplayName("a full tie is broken by row id, deterministically")
		void fullTieIsBrokenByRowId() {
			PricingTerm lowerId = pricingTerm("tie-a", 3, Q1_START, Q2_START.plusMonths(2), inr("920"), null, null);
			PricingTerm higherId = pricingTerm("tie-b", 3, Q1_START, Q2_START.plusMonths(2), inr("1000"), null, null);
			// The two rows agree on version, start and end, so the only discriminator
			// left is the row id. Pinned here so the fixture cannot drift into a case
			// that is decided by something else.
			assertThat(lowerId.id().compareTo(higherId.id())).isNegative();

			assertThat(resolver.findInForce(List.of(higherId, lowerId), MID_Q2)).contains(lowerId);
			assertThat(resolver.findInForce(List.of(lowerId, higherId), MID_Q2)).contains(lowerId);
		}

		@Test
		@DisplayName("both rows of an ambiguous overlap remain visible to the caller")
		void ambiguousOverlapExposesEveryCandidate() {
			PricingTerm v1 = pricingTerm("v1", 1, Q1_START, null, inr("920"), null, null);
			PricingTerm v2 = pricingTerm("v2", 2, Q1_START, null, inr("950"), null, null);

			assertThat(resolver.inForceOn(List.of(v1, v2), MID_Q2)).containsExactly(v2, v1);
		}

		@Test
		@DisplayName("a bespoke product price beats the contract-wide default even at a lower version")
		void productScopedTermBeatsContractWideDefault() {
			PricingTerm contractWide = pricingTerm("default", 5, Q1_START, null, inr("920"), null, null);
			// Scoped to this product and to no customer, which is the "product only"
			// rank of specificity. The version is deliberately lower than the default's:
			// ranking specificity before version is what stops an agreed bespoke price
			// from silently reverting to list when the default is amended.
			PricingTerm forProduct = pricingTerm("product", 1, Q1_START, null, PRODUCT_ID, null,
					PricingType.FixedUnit.INSTANCE, inr("880"), null, null, INR);

			assertThat(service.pricingTermInForce(activeContract(), List.of(contractWide, forProduct), PRODUCT_ID,
					CUSTOMER_ID, MID_Q2)).isEqualTo(forProduct);
		}

		@Test
		@DisplayName("a term scoped to another product or another customer is not a candidate")
		void foreignScopedTermsAreExcluded() {
			PricingTerm otherProduct = pricingTerm("other-product", 9, Q1_START, null, OTHER_PRODUCT_ID, null,
					PricingType.FixedUnit.INSTANCE, inr("1"), null, null, INR);
			PricingTerm otherCustomer = pricingTerm("other-customer", 9, Q1_START, null, null, OTHER_CUSTOMER_ID,
					PricingType.FixedUnit.INSTANCE, inr("1"), null, null, INR);

			// Both are in force on the date, so the resolver alone would return them:
			// it is scope, not the window, that excludes them. They rank version 9
			// precisely so that a version-only comparison would have selected one of
			// them and priced the invoice at 1.
			assertThat(resolver.inForceOn(List.of(otherProduct, otherCustomer), MID_Q2)).hasSize(2);
			assertThatThrownBy(() -> service.pricingTermInForce(activeContract(), List.of(otherProduct, otherCustomer),
					PRODUCT_ID, CUSTOMER_ID, MID_Q2))
					.isInstanceOf(BusinessRuleException.class)
					.hasMessageContaining("no pricing term in force");
		}

		@Test
		@DisplayName("missing terms raise rather than defaulting")
		void missingTermsRaise() {
			assertThatThrownBy(
					() -> service.pricingTermInForce(activeContract(), List.of(), PRODUCT_ID, CUSTOMER_ID, MID_Q2))
					.isInstanceOf(BusinessRuleException.class)
					.hasMessageContaining("no pricing term in force");

			assertThat(service.discountTermInForce(activeContract(), List.of(), PRODUCT_ID, CUSTOMER_ID, MID_Q2))
					.isEmpty();
		}

		@Test
		@DisplayName("a suspended contract supplies no terms even inside its window")
		void suspendedContractSuppliesNoTerms() {
			Contract suspended = new Contract(CONTRACT_ID, ORGANIZATION, CUSTOMER_ID, "C-2024-001", null,
					ContractStatus.Suspended.INSTANCE, INR, Q1_START, null, null, null, 0L);

			assertThatThrownBy(() -> service.pricingTermInForce(suspended,
					List.of(pricingTerm("any", 1, Q1_START, null, inr("920"), null, null)), null, null, MID_Q2))
					.isInstanceOf(BusinessRuleException.class)
					.hasMessageContaining("SUSPENDED");
		}

		@Test
		@DisplayName("an expired contract still supplies its historical terms")
		void expiredContractStillSuppliesTerms() {
			Contract expired = new Contract(CONTRACT_ID, ORGANIZATION, CUSTOMER_ID, "C-2024-001", null,
					ContractStatus.Expired.INSTANCE, INR, Q1_START, Q1_END, null, null, 0L);

			assertThat(service.pricingTermInForce(expired,
					List.of(pricingTerm("q1", 1, Q1_START, Q1_END, inr("920"), null, null)), null, null, Q1_END))
					.isNotNull();
		}

		@Test
		@DisplayName("the contract window bounds every term it carries")
		void contractWindowBoundsItsTerms() {
			Contract ended = new Contract(CONTRACT_ID, ORGANIZATION, CUSTOMER_ID, "C-2024-001", null,
					ContractStatus.Active.INSTANCE, INR, Q1_START, Q1_END, null, null, 0L);
			List<PricingTerm> terms = List.of(pricingTerm("open", 1, Q1_START, null, inr("920"), null, null));

			assertThatThrownBy(() -> service.pricingTermInForce(ended, terms, null, null, Q2_START))
					.isInstanceOf(BusinessRuleException.class)
					.hasMessageContaining("not effective");
		}

		@Test
		@DisplayName("the clause in force is selected by type as well as by date")
		void clauseSelectedByTypeAndDate() {
			List<ContractTerm> terms = List.of(
					new ContractTerm(id("ct-delivery-v1"), ORGANIZATION, CONTRACT_ID,
							ContractTermType.DeliveryTerms.INSTANCE, "ex-works", Q1_START, Q1_END, 1),
					new ContractTerm(id("ct-delivery-v2"), ORGANIZATION, CONTRACT_ID,
							ContractTermType.DeliveryTerms.INSTANCE, "dap", Q2_START, null, 2),
					new ContractTerm(id("ct-payment"), ORGANIZATION, CONTRACT_ID, ContractTermType.PaymentTerms.INSTANCE,
							"net 30", Q1_START, null, 1));

			assertThat(
					service.contractTermInForce(activeContract(), terms, ContractTermType.DeliveryTerms.INSTANCE, Q1_END))
					.get()
					.extracting(ContractTerm::description)
					.isEqualTo("ex-works");
			assertThat(
					service.contractTermInForce(activeContract(), terms, ContractTermType.DeliveryTerms.INSTANCE, Q2_START))
					.get()
					.extracting(ContractTerm::description)
					.isEqualTo("dap");
			// The payment clause is in force on both dates and must not be reachable
			// through a DELIVERY_TERMS lookup: type is a filter, not a preference.
			assertThat(
					service.contractTermInForce(activeContract(), terms, ContractTermType.PaymentTerms.INSTANCE, Q1_END))
					.get()
					.extracting(ContractTerm::description)
					.isEqualTo("net 30");
			assertThat(service.contractTermInForce(activeContract(), terms, ContractTermType.Renewal.INSTANCE, Q1_END))
					.isEmpty();
		}
	}

	// ------------------------------------------------------------------ pricing

	@Nested
	@DisplayName("price resolution and clamping")
	class PriceResolutionAndClamping {

		@Test
		@DisplayName("the published unit price is handed out normalised to NUMERIC(20,6)")
		void publishedPriceIsNormalised() {
			PricingTerm term = pricingTerm("fixed", 1, Q1_START, null, inr("920.5"), null, null);

			// No candidate offered: the term publishes its own price, which is the
			// path that has to hand out 920.5 at the price scale rather than at the
			// amount scale.
			ResolvedPrice price = service.resolveUnitPrice(activeContract(), List.of(term), null, null, MID_Q2, null);

			assertThat(price.unitPrice()).isEqualTo(inr("920.5"));
			assertThat(price.unitPrice().amount().scale()).isEqualTo(6);
			assertThat(price.declaredUnitPrice()).isEqualTo(inr("920.5"));
			assertThat(price.clamped()).isFalse();
			assertThat(price.clampedAmount()).isNull();
			assertThat(price.termVersion()).isEqualTo(1);
			assertThat(price.pricingTermId()).isEqualTo(term.id());
		}

		@Test
		@DisplayName("a tiered candidate below price_minimum is clamped up to the floor")
		void candidateBelowFloorIsClampedUp() {
			PricingTerm tiered = pricingTerm("tiered", 4, Q1_START, null, PricingType.Tiered.INSTANCE, inr("880"),
					inr("900"), inr("950"));

			ResolvedPrice price = service.resolveUnitPrice(activeContract(), List.of(tiered), null, null, MID_Q2,
					inr("880"));

			assertThat(price.unitPrice()).isEqualTo(inr("900"));
			assertThat(price.clamped()).isTrue();
			assertThat(price.clampedAmount()).isEqualTo(inr("20"));
		}

		@Test
		@DisplayName("a tiered candidate above price_maximum is clamped down to the ceiling")
		void candidateAboveCeilingIsClampedDown() {
			PricingTerm tiered = pricingTerm("tiered", 4, Q1_START, null, PricingType.Tiered.INSTANCE, inr("1200"),
					inr("900"), inr("950"));

			ResolvedPrice price = service.resolveUnitPrice(activeContract(), List.of(tiered), null, null, MID_Q2,
					inr("1200"));

			assertThat(price.unitPrice()).isEqualTo(inr("950"));
			assertThat(price.clamped()).isTrue();
			assertThat(price.clampedAmount()).isEqualTo(inr("-250"));
		}

		@Test
		@DisplayName("a candidate already inside the bounds is handed back untouched")
		void candidateInsideBoundsIsUntouched() {
			PricingTerm tiered = pricingTerm("tiered", 4, Q1_START, null, PricingType.Tiered.INSTANCE, inr("925"),
					inr("900"), inr("950"));

			ResolvedPrice price = service.resolveUnitPrice(activeContract(), List.of(tiered), null, null, MID_Q2,
					inr("925"));

			assertThat(price.unitPrice()).isEqualTo(inr("925"));
			assertThat(price.clamped()).isFalse();
		}

		@Test
		@DisplayName("a single-sided bound clamps only that side")
		void singleSidedBoundClampsOnlyThatSide() {
			PricingTerm floorOnly = pricingTerm("floor", 1, Q1_START, null, PricingType.Tiered.INSTANCE, inr("500"),
					inr("900"), null);
			PricingTerm ceilingOnly = pricingTerm("ceiling", 1, Q1_START, null, PricingType.Tiered.INSTANCE, inr("5000"),
					null, inr("950"));

			assertThat(service.resolveUnitPrice(activeContract(), List.of(floorOnly), null, null, MID_Q2, inr("500"))
					.unitPrice()).isEqualTo(inr("900"));
			assertThat(service.resolveUnitPrice(activeContract(), List.of(ceilingOnly), null, null, MID_Q2, inr("5000"))
					.unitPrice()).isEqualTo(inr("950"));
		}

		@Test
		@DisplayName("a candidate may not override a published fixed price")
		void candidateMayNotOverrideFixedPrice() {
			PricingTerm fixed = pricingTerm("fixed", 1, Q1_START, null, PricingType.FixedUnit.INSTANCE, inr("920"),
					null, null);

			// Only TIERED accepts a candidate. A FIXED_UNIT row is an agreed price, so
			// an offered price against it is a request to charge something nobody
			// agreed to, and it is refused rather than clamped.
			assertThatThrownBy(
					() -> service.resolveUnitPrice(activeContract(), List.of(fixed), null, null, MID_Q2, inr("800")))
					.isInstanceOf(BusinessRuleException.class)
					.hasMessageContaining("FIXED_UNIT");
		}

		@Test
		@DisplayName("a tiered term with neither a price nor bounds cannot be priced")
		void unpriceableTieredTermRaises() {
			PricingTerm tiered = pricingTerm("tiered-empty", 1, Q1_START, null, PricingType.Tiered.INSTANCE, null,
					null, null);

			assertThatThrownBy(
					() -> service.resolveUnitPrice(activeContract(), List.of(tiered), null, null, MID_Q2, null))
					.isInstanceOf(BusinessRuleException.class);
		}

		@Test
		@DisplayName("inconsistent bounds are rejected before they can be used")
		void inconsistentBoundsAreRejected() {
			assertThatThrownBy(() -> pricingTerm("bad", 1, Q1_START, null, inr("900"), inr("950"), inr("800")))
					.isInstanceOf(com.fintech.cfo.shared.exception.ValidationException.class)
					.hasMessageContaining("priceMinimum");
		}

		@Test
		@DisplayName("price bounds stated in another currency cannot be mixed into a term")
		void priceBoundsMustMatchTermCurrency() {
			assertThatThrownBy(() -> pricingTerm("mixed", 1, Q1_START, null, PricingType.Tiered.INSTANCE, inr("500"),
					inr("500"), Money.of("900", USD), INR))
					.isInstanceOf(com.fintech.cfo.shared.exception.ValidationException.class)
					.hasMessageContaining("priceMaximum");
		}
	}

	// ----------------------------------------------------------------- discount

	@Nested
	@DisplayName("discount evaluation")
	class DiscountEvaluationBehaviour {

		@Test
		@DisplayName("a percentage discount is the gross amount scaled by the rate")
		void percentageDiscount() {
			DiscountEvaluation result = discountService.evaluate(inr("10000.00"),
					ContractServiceTest.percentageDiscount("pct10", 1, Q1_START, null, "10", null), MID_Q2);

			assertThat(result.hasDiscountTerm()).isTrue();
			assertThat(result.discountAmount()).isEqualTo(inr("1000"));
			assertThat(result.netAmount()).isEqualTo(inr("9000"));
			assertThat(result.discountType()).isEqualTo(DiscountType.Percentage.INSTANCE);
			assertThat(result.wasCapped()).isFalse();
			assertThat(result.capReason()).isEqualTo(DiscountCapReason.None.INSTANCE);
		}

		@Test
		@DisplayName("a percentage is applied once and rounded once, HALF_UP, at NUMERIC(20,4)")
		void percentageRoundingIsHalfUp() {
			DiscountEvaluation result = discountService.evaluate(inr("999.99"),
					ContractServiceTest.percentageDiscount("pct33", 1, Q1_START, null, "33.33", null), MID_Q2);

			assertThat(result.discountAmount()).isEqualTo(inr("333.2967"));
			assertThat(result.discountAmount().amount().scale()).isEqualTo(4);
			assertThat(result.netAmount()).isEqualTo(inr("666.6933"));
		}

		@Test
		@DisplayName("an exact half rounds up, not to even")
		void exactHalfRoundsUp() {
			// 0.002 x 12.5% is exactly 0.00025, a half at the fourth place. HALF_UP
			// hands out 0.0003 and HALF_EVEN would have handed out 0.0002. The whole
			// point is that no intermediate step may round the value before the
			// hand-out, or the half disappears and the answer becomes 0.0000.
			DiscountEvaluation result = discountService.evaluate(inr("0.002"),
					ContractServiceTest.percentageDiscount("pct12half", 1, Q1_START, null, "12.5", null), MID_Q2);

			assertThat(result.discountAmount()).isEqualTo(inr("0.0003"));
			assertThat(result.netAmount()).isEqualTo(inr("0.0017"));
		}

		@Test
		@DisplayName("a fixed-amount discount is taken as stated")
		void fixedAmountDiscount() {
			DiscountEvaluation result = discountService.evaluate(inr("10000.00"),
					fixedDiscount("flat500", 1, Q1_START, null, "500", INR, null), MID_Q2);

			assertThat(result.discountAmount()).isEqualTo(inr("500"));
			assertThat(result.netAmount()).isEqualTo(inr("9500"));
			assertThat(result.discountType()).isEqualTo(DiscountType.FixedAmount.INSTANCE);
			assertThat(result.wasCapped()).isFalse();
		}

		@Test
		@DisplayName("max_discount_amount caps a percentage discount")
		void maximumDiscountAmountCapsPercentage() {
			DiscountEvaluation result = discountService.evaluate(inr("100000"),
					ContractServiceTest.percentageDiscount("pct10capped", 2, Q1_START, null, "10", inr("2500")), MID_Q2);

			assertThat(result.discountAmount()).isEqualTo(inr("2500"));
			assertThat(result.capAmount()).isEqualTo(inr("2500"));
			assertThat(result.capReason()).isEqualTo(DiscountCapReason.MaxDiscountAmount.INSTANCE);
			assertThat(result.netAmount()).isEqualTo(inr("97500"));
		}

		@Test
		@DisplayName("max_discount_amount below the computed discount leaves the computed discount alone")
		void capBelowComputedDiscountIsIgnored() {
			DiscountEvaluation result = discountService.evaluate(inr("100000"),
					ContractServiceTest.percentageDiscount("pct10uncapped", 2, Q1_START, null, "10", inr("50000")), MID_Q2);

			assertThat(result.discountAmount()).isEqualTo(inr("10000"));
			assertThat(result.wasCapped()).isFalse();
			assertThat(result.capAmount()).isNull();
		}

		@Test
		@DisplayName("a fixed discount larger than the gross is capped at the gross, not at the term cap")
		void fixedDiscountCannotExceedGross() {
			DiscountEvaluation result = discountService.evaluate(inr("1000"),
					fixedDiscount("flat5000", 1, Q1_START, null, "5000", INR, null), MID_Q2);

			assertThat(result.discountAmount()).isEqualTo(inr("1000"));
			assertThat(result.netAmount()).isEqualTo(inr("0"));
			assertThat(result.capReason()).isEqualTo(DiscountCapReason.GrossAmountLimit.INSTANCE);
		}

		@Test
		@DisplayName("no discount term in force yields an explicit no-discount result, not a default")
		void noDiscountTermYieldsExplicitNone() {
			DiscountEvaluation result = discountService.evaluate(inr("2500"), null, MID_Q2);

			assertThat(result.hasDiscountTerm()).isFalse();
			assertThat(result.discountTermId()).isNull();
			assertThat(result.termVersion()).isZero();
			assertThat(result.discountAmount()).isEqualTo(Money.zero(INR));
			assertThat(result.netAmount()).isEqualTo(inr("2500"));
		}

		@Test
		@DisplayName("a zero gross amount discounts to zero and never negative")
		void zeroGrossYieldsZeroDiscount() {
			DiscountEvaluation result = discountService.evaluate(Money.zero(INR),
					ContractServiceTest.percentageDiscount("pct25", 1, Q1_START, null, "25", null), MID_Q2);

			assertThat(result.discountAmount()).isEqualTo(Money.zero(INR));
			assertThat(result.netAmount()).isEqualTo(Money.zero(INR));
			// Nothing was reduced, so nothing is reported as capped. A cap reason here
			// would send a reviewer looking for a reduction that never happened, which
			// is the reporting failure a cap reason exists to avoid.
			assertThat(result.wasCapped()).isFalse();
			assertThat(result.capReason()).isEqualTo(DiscountCapReason.None.INSTANCE);
			assertThat(result.capAmount()).isNull();
		}

		@Test
		@DisplayName("a percentage outside (0, 100] is rejected at construction")
		void invalidPercentageIsRejected() {
			assertThatThrownBy(() -> ContractServiceTest.percentageDiscount("pct101", 1, Q1_START, null, "101", null))
					.isInstanceOf(com.fintech.cfo.shared.exception.ValidationException.class);
			assertThatThrownBy(() -> ContractServiceTest.percentageDiscount("pct0", 1, Q1_START, null, "0", null))
					.isInstanceOf(com.fintech.cfo.shared.exception.ValidationException.class);
		}

		@Test
		@DisplayName("a fixed discount stated in another currency is rejected, never converted")
		void fixedDiscountCurrencyMismatchIsRejected() {
			DiscountTerm usdDiscount = fixedDiscount("flat-usd", 1, Q1_START, null, "100", USD, null);

			assertThatThrownBy(() -> discountService.evaluate(inr("1000"), usdDiscount, MID_Q2))
					.isInstanceOf(BusinessRuleException.class)
					.hasMessageContaining("USD");
		}

		@Test
		@DisplayName("a contract in one currency ignores a fixed discount stated in another")
		void foreignCurrencyDiscountIsSkippedByTheService() {
			List<DiscountTerm> terms = List.of(fixedDiscount("flat-usd", 1, Q1_START, null, "100", USD, null),
					ContractServiceTest.percentageDiscount("pct5", 1, Q1_START, null, "5", null));

			DiscountEvaluation result = service.evaluateDiscount(activeContract(), terms, PRODUCT_ID, CUSTOMER_ID,
					MID_Q2, inr("1000"));

			// The USD row is a perfectly well-formed term in force on the date; it is
			// skipped rather than allowed to mask the percentage ranked beside it, and
			// no FX rate is invented for it.
			assertThat(result.discountType()).isEqualTo(DiscountType.Percentage.INSTANCE);
			assertThat(result.discountAmount()).isEqualTo(inr("50"));
		}
	}

	// -------------------------------------------------------------- multi-currency

	@Nested
	@DisplayName("multi-currency")
	class MultiCurrency {

		@Test
		@DisplayName("a price term in another currency is not used")
		void priceTermInForeignCurrencyIsNotUsed() {
			PricingTerm usdPricing = pricingTerm("usd-price", 1, Q1_START, null, PricingType.FixedUnit.INSTANCE,
					Money.of("920", USD), null, null, USD);

			assertThatThrownBy(() -> service.pricingTermInForce(activeContract(), List.of(usdPricing), null, null,
					MID_Q2))
					.isInstanceOf(BusinessRuleException.class)
					.hasMessageContaining("INR");
		}

		@Test
		@DisplayName("a candidate price in another currency is rejected rather than converted")
		void candidatePriceCurrencyMismatchIsRejected() {
			PricingTerm tiered = pricingTerm("tiered", 1, Q1_START, null, PricingType.Tiered.INSTANCE, inr("900"),
					inr("900"), inr("950"));

			// clampTo(term, offeredPrice, contractCurrency, asOfDate): the offered
			// price is the amount being checked, and the currency it has to agree with
			// is the contract's. A USD offer against an INR contract is refused with a
			// reason naming both currencies, not converted and not allowed to blow up
			// inside a comparison.
			assertThatThrownBy(() -> priceService.clampTo(tiered, Money.of("920", USD), INR, MID_Q2))
					.isInstanceOf(BusinessRuleException.class)
					.hasMessageContaining("does not match term currency");
		}

		@Test
		@DisplayName("amounts of different currencies are never added")
		void crossCurrencyArithmeticIsRefusedByMoney() {
			assertThatThrownBy(() -> inr("100").add(Money.of("100", USD))).isInstanceOf(IllegalArgumentException.class);
		}
	}

	// -------------------------------------------------------- reproducibility

	@Nested
	@DisplayName("reproducibility")
	class Reproducibility {

		@Test
		@DisplayName("the same as-of date re-derives the identical result")
		void sameDateReproducesIdenticalResult() {
			List<PricingTerm> terms = List.of(pricingTerm("v1", 1, Q1_START, Q1_END, inr("920"), null, null),
					pricingTerm("v2", 2, Q2_START, null, inr("950"), null, null));
			LocalDate historical = LocalDate.of(2024, 2, 15);

			ResolvedPrice first = service.resolveUnitPrice(activeContract(), terms, null, null, historical, null);
			ResolvedPrice second = service.resolveUnitPrice(activeContract(), terms, null, null, historical, null);

			assertThat(first).isEqualTo(second);
			assertThat(first.unitPrice()).isEqualTo(inr("920"));
		}

		@Test
		@DisplayName("a later as-of date picks up the later term version")
		void laterDatePicksUpLaterVersion() {
			List<PricingTerm> terms = List.of(pricingTerm("v1", 1, Q1_START, Q1_END, inr("920"), null, null),
					pricingTerm("v2", 2, Q2_START, null, inr("950"), null, null));

			assertThat(service.resolveUnitPrice(activeContract(), terms, null, null, Q1_END, null).unitPrice())
					.isEqualTo(inr("920"));
			assertThat(service.resolveUnitPrice(activeContract(), terms, null, null, Q2_START, null).unitPrice())
					.isEqualTo(inr("950"));
		}

		@Test
		@DisplayName("the input checksum ignores list order but not content")
		void inputChecksumIsOrderIndependent() {
			PricingTerm first = pricingTerm("v1", 1, Q1_START, Q1_END, inr("920"), null, null);
			PricingTerm second = pricingTerm("v2", 2, Q2_START, null, inr("950"), null, null);
			DiscountTerm discount = ContractServiceTest.percentageDiscount("pct10", 1, Q1_START, null, "10", null);

			EffectiveTermsQuery forwards = new EffectiveTermsQuery(activeContract(), List.of(),
					List.of(first, second), List.of(discount), List.of(), PRODUCT_ID, CUSTOMER_ID, MID_Q2);
			EffectiveTermsQuery backwards = new EffectiveTermsQuery(activeContract(), List.of(),
					List.of(second, first), List.of(discount), List.of(), PRODUCT_ID, CUSTOMER_ID, MID_Q2);

			EffectiveTerms resolvedForwards = service.resolveEffectiveTerms(forwards);
			EffectiveTerms resolvedBackwards = service.resolveEffectiveTerms(backwards);

			assertThat(resolvedBackwards.inputChecksum()).isEqualTo(resolvedForwards.inputChecksum());
			// 64 hex characters: a SHA-256 digest, not a truncated or padded hash of
			// something weaker.
			assertThat(resolvedForwards.inputChecksum()).hasSize(64);
		}

		@Test
		@DisplayName("changing any input changes the checksum")
		void checksumDetectsAChangedInput() {
			// Open-ended on purpose: the same row has to resolve on both dates below,
			// or the later-date query would fail on a missing price rather than
			// demonstrating that a different question hashes differently.
			PricingTerm term = pricingTerm("v1", 1, Q1_START, null, inr("920"), null, null);
			PricingTerm restated = pricingTerm("v1", 1, Q1_START, null, inr("921"), null, null);

			String original = service
					.resolveEffectiveTerms(new EffectiveTermsQuery(activeContract(), List.of(), List.of(term), List.of(),
							List.of(), PRODUCT_ID, CUSTOMER_ID, MID_Q2))
					.inputChecksum();
			String changed = service
					.resolveEffectiveTerms(new EffectiveTermsQuery(activeContract(), List.of(), List.of(restated),
							List.of(), List.of(), PRODUCT_ID, CUSTOMER_ID, MID_Q2))
					.inputChecksum();
			String laterDate = service
					.resolveEffectiveTerms(new EffectiveTermsQuery(activeContract(), List.of(), List.of(term), List.of(),
							List.of(), PRODUCT_ID, CUSTOMER_ID, Q2_START))
					.inputChecksum();

			assertThat(changed).isNotEqualTo(original);
			assertThat(laterDate).isNotEqualTo(original);
		}

		@Test
		@DisplayName("the effective-terms record carries the winning terms and their versions")
		void effectiveTermsRecordCarriesItsEvidence() {
			PricingTerm pricing = pricingTerm("v2", 2, Q2_START, null, inr("950"), null, null);
			DiscountTerm discount = ContractServiceTest.percentageDiscount("pct10", 3, Q1_START, null, "10", null);
			ContractTerm clause = new ContractTerm(id("ct-payment"), ORGANIZATION, CONTRACT_ID,
					ContractTermType.PaymentTerms.INSTANCE, "net 30", Q1_START, null, 1);
			// The canonical form takes already-parsed parameters and a window; the
			// convenience overload takes the raw `parameters` column text instead.
			// `threshold_amount` is the parameter APPROVAL_REQUIRED cannot be
			// evaluated without, so the rule is one that could actually be applied.
			CommercialRule rule = new CommercialRule(id("rule-approval"), ORGANIZATION, CONTRACT_ID, "APPROVAL-100K",
					CommercialRuleType.ApprovalRequired.INSTANCE, "approval over 1,00,000",
					CommercialRuleParameters.parse("threshold_amount=100000"), EffectiveWindow.of(Q1_START, null), 1,
					0L);

			EffectiveTerms resolved = service.resolveEffectiveTerms(new EffectiveTermsQuery(activeContract(),
					List.of(clause), List.of(pricing), List.of(discount), List.of(rule), PRODUCT_ID, CUSTOMER_ID,
					MID_Q2));

			assertThat(resolved.asOfDate()).isEqualTo(MID_Q2);
			assertThat(resolved.pricingTerm()).isEqualTo(pricing);
			assertThat(resolved.pricingTerm().termVersion()).isEqualTo(2);
			assertThat(resolved.hasDiscountTerm()).isTrue();
			assertThat(resolved.discountTerm().termVersion()).isEqualTo(3);
			assertThat(resolved.contractTerm(ContractTermType.PaymentTerms.INSTANCE)).get()
					.extracting(ContractTerm::description)
					.isEqualTo("net 30");
			assertThat(resolved.commercialRules()).containsExactly(rule);
			assertThat(resolved.inputChecksum()).isNotBlank();
		}

		@Test
		@DisplayName("resolving terms without a pricing term raises instead of defaulting")
		void effectiveTermsWithoutPricingRaise() {
			assertThatThrownBy(() -> service.resolveEffectiveTerms(new EffectiveTermsQuery(activeContract(),
					List.of(), List.of(), List.of(), List.of(), PRODUCT_ID, CUSTOMER_ID, MID_Q2)))
					.isInstanceOf(BusinessRuleException.class)
					.hasMessageContaining("no pricing term in force");
		}

		@Test
		@DisplayName("repeating a calculation on the same inputs reproduces it exactly")
		void repeatedCalculationReproduces() {
			// A TIERED band, because a band is the shape that accepts a candidate
			// price: the 880 offered here is below the 900 floor and is clamped up,
			// which is the only arithmetic worth reproducing.
			List<PricingTerm> terms = List.of(pricingTerm("v1", 1, Q1_START, null, PricingType.Tiered.INSTANCE,
					inr("920"), inr("900"), inr("950")));
			LocalDate historical = LocalDate.of(2024, 2, 15);

			for (int run = 0; run < 5; run++) {
				ResolvedPrice price = service.resolveUnitPrice(activeContract(), terms, PRODUCT_ID, CUSTOMER_ID,
						historical, inr("880"));
				// A 900 unit price over 1000 units is a 900,000 gross; a 10% discount of
				// that is 90,000, leaving exactly 810,000. Both figures are exact at
				// NUMERIC(20,4), so any extra rounding introduced anywhere in the path
				// would move the net and fail here.
				DiscountEvaluation discount = service.evaluateDiscount(activeContract(),
						List.of(ContractServiceTest.percentageDiscount("pct10", 1, Q1_START, null, "10", null)), PRODUCT_ID, CUSTOMER_ID,
						historical, price.unitPrice().multiply(new BigDecimal("1000")));

				assertThat(price.unitPrice()).isEqualTo(inr("900"));
				assertThat(discount.netAmount()).isEqualTo(inr("810000"));
			}
		}
	}

}

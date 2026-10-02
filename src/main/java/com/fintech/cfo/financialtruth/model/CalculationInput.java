package com.fintech.cfo.financialtruth.model;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.fintech.cfo.financialtruth.enums.CalculationType;
import com.fintech.cfo.shared.domain.CurrencyCode;
import com.fintech.cfo.shared.domain.DateRange;
import com.fintech.cfo.shared.domain.Money;
import com.fintech.cfo.shared.domain.OrganizationId;
import com.fintech.cfo.shared.domain.UserId;
import com.fintech.cfo.shared.exception.ValidationException;
import com.fintech.cfo.shared.util.HashUtils;

/**
 * A frozen snapshot of everything a calculation is allowed to read.
 *
 * <h2>Why an explicit snapshot</h2>
 * The engine is handed this object and nothing else. It cannot reach a repository,
 * cannot read a clock and cannot ask "what is the current price". Everything that
 * influences the answer is here, together with the terms' own effective dates. That
 * is what makes {@code calculation_runs.input_checksum} a meaningful fingerprint
 * rather than a decorative hash.
 *
 * <h2>Determinism guarantees</h2>
 * <ul>
 * <li>The as-of date is mandatory. Terms are selected against it, never against a
 * system clock, so re-running the same snapshot selects the same terms.</li>
 * <li>Lines keep their given order. Totals sum already-rounded components, so order
 * cannot change the answer, and iteration order is reproducible for the checksum.</li>
 * <li>Term selection has an explicit, total ordering (see
 * {@link #effectivePricingTerm}), so a contract carrying two overlapping versions
 * still resolves the same way every time.</li>
 * <li>{@link #checksum()} hashes a canonical string, never an object's identity.</li>
 * </ul>
 */
public record CalculationInput(
		OrganizationId organizationId,
		CalculationType calculationType,
		String invoiceNumber,
		LocalDate invoiceDate,
		LocalDate asOfDate,
		List<InvoiceLineInput> lines,
		List<PricingTerm> pricingTerms,
		List<DiscountTerm> discountTerms,
		UserId triggeredBy) {

	/**
	 * Tie-break order for overlapping pricing terms: highest version wins, then the
	 * most recently effective, then the term id. Total and deterministic, so an
	 * ambiguous contract cannot make the engine non-reproducible.
	 */
	private static final Comparator<PricingTerm> PRICING_PRECEDENCE = Comparator
			.comparingInt(PricingTerm::termVersion).reversed()
			.thenComparing(PricingTerm::effectiveFrom, Comparator.reverseOrder())
			.thenComparing(PricingTerm::termId);

	/**
	 * Order in which multiple effective discount terms are applied: earliest window
	 * first, then lowest version, then term id. Fixed so a contract granting two
	 * stacked discounts always stacks them in the same sequence.
	 */
	private static final Comparator<DiscountTerm> DISCOUNT_APPLICATION_ORDER = Comparator
			.comparing(DiscountTerm::effectiveFrom)
			.thenComparingInt(DiscountTerm::termVersion)
			.thenComparing(DiscountTerm::termId);

	public CalculationInput {
		if (organizationId == null) {
			throw new ValidationException("organizationId must not be null");
		}
		if (calculationType == null) {
			throw new ValidationException("calculationType must not be null");
		}
		invoiceNumber = requireText(invoiceNumber, "invoiceNumber");
		if (invoiceDate == null) {
			throw new ValidationException("invoiceDate must not be null");
		}
		if (asOfDate == null) {
			throw new ValidationException("asOfDate must not be null; a calculation must never read a system clock");
		}
		if (asOfDate.isBefore(invoiceDate)) {
			// A run cannot reconcile as of a date before the invoice existed; the period
			// would run backwards and no contract term could be selected for it.
			throw new ValidationException("asOfDate " + asOfDate + " must not be before invoiceDate " + invoiceDate);
		}
		// Defensive copies. The snapshot must be immutable for the life of the run, since
		// its checksum is hashed once and compared against a much later replay.
		lines = lines == null ? List.of() : List.copyOf(lines);
		pricingTerms = pricingTerms == null ? List.of() : List.copyOf(pricingTerms);
		discountTerms = discountTerms == null ? List.of() : List.copyOf(discountTerms);
		if (lines.isEmpty()) {
			// A total of zero from no lines is indistinguishable from a clean invoice,
			// which is precisely the false assurance this module must not give.
			throw new ValidationException("an invoice with no lines cannot be reconciled; refusing to report zero");
		}
	}

	/** Every currency the lines are denominated in, deduplicated and ordered. */
	public List<CurrencyCode> currencies() {
		return this.lines.stream().map(InvoiceLineInput::currency).distinct().sorted().toList();
	}

	/** Reporting period: the invoice date up to the as-of date the run was performed. */
	public DateRange period() {
		return DateRange.of(this.invoiceDate, this.asOfDate);
	}

	/**
	 * The contract price that was in force for this product on the as-of date, or
	 * {@code null} if the contract says nothing about it then.
	 *
	 * <p>A {@code null} result is meaningful and must be acted on: the caller cannot
	 * establish an expected price and therefore must not invent one.
	 *
	 * <p>A term with no {@code productKey} is treated as the contract-wide default
	 * price for the customer and matches any product, but a product-specific term
	 * always wins over a contract-wide one.
	 */
	public PricingTerm effectivePricingTerm(String productKey, LocalDate asOf) {
		return this.pricingTerms.stream()
				// Product match first, then effective window. Both are pure predicates over
				// the snapshot, so term selection is a total function of (snapshot, asOf).
				.filter(term -> matchesProduct(term.productKey(), productKey))
				.filter(term -> term.isEffectiveOn(asOf))
				// sorted, not max: the comparator is a total order (id breaks every tie),
				// so the winner does not depend on which term the stream saw first.
				.sorted(PRICING_PRECEDENCE)
				.findFirst()
				.orElse(null);
	}

	/**
	 * Every discount that was in force for this product on the as-of date, in the
	 * fixed application order.
	 *
	 * <p>All matches are returned, not just the winner: stacked discounts are a real
	 * commercial arrangement, and collapsing them to one would understate the
	 * entitlement.
	 */
	public List<DiscountTerm> effectiveDiscountTerms(String productKey, LocalDate asOf) {
		return this.discountTerms.stream()
				.filter(term -> matchesProduct(term.productKey(), productKey))
				.filter(term -> term.isEffectiveOn(asOf))
				.sorted(DISCOUNT_APPLICATION_ORDER)
				.toList();
	}

	/**
	 * SHA-256 over {@link #canonicalForm()}.
	 *
	 * <p>This is the value that belongs in {@code calculation_runs.input_checksum}.
	 * It is content-addressed and scale-insensitive, so re-reading identical values
	 * from the database produces the identical digest and a genuine replay is
	 * provable rather than asserted.
	 */
	public String checksum() {
		return HashUtils.sha256(this.canonicalForm());
	}

	public String canonicalForm() {
		StringBuilder text = new StringBuilder();
		// Version-prefixed so a future change to the canonical layout cannot silently
		// collide with digests produced by this one.
		text.append("calculationInput/v1")
				.append("|org=").append(this.organizationId.value())
				.append("|type=").append(this.calculationType.name())
				.append("|invoice=").append(this.invoiceNumber)
				.append("|invoiceDate=").append(RoundingPolicy.canonicalDate(this.invoiceDate))
				.append("|asOf=").append(RoundingPolicy.canonicalDate(this.asOfDate))
				.append("|triggeredBy=").append(RoundingPolicy.canonicalText(
						this.triggeredBy == null ? null : this.triggeredBy.value().toString()))
				.append("|lines=");
		for (InvoiceLineInput line : this.lines) {
			text.append(line.canonicalForm());
		}
		text.append("|pricingTerms=");
		for (PricingTerm term : sortedPricingForCanonicalForm(this.pricingTerms)) {
			text.append(term.toEvaluation().canonicalForm()).append(pricingValueOf(term)).append(';');
		}
		text.append("|discountTerms=");
		for (DiscountTerm term : sortedDiscountForCanonicalForm(this.discountTerms)) {
			text.append(term.toEvaluation().canonicalForm()).append(discountValueOf(term)).append(';');
		}
		return text.toString();
	}

	/**
	 * The financial content of a pricing term, appended to its lineage.
	 *
	 * <p>{@link PricingTerm#toEvaluation()} records which term, which version and which
	 * window - the provenance a result needs. That alone is not enough to fingerprint an
	 * input: a contract can be amended in place, keeping the same term id and version
	 * while the price changes, and two snapshots differing only in that price are not
	 * the same input. Hashing the value as well makes the checksum genuinely
	 * content-addressed rather than an identifier in disguise.
	 */
	private static String pricingValueOf(PricingTerm term) {
		return "|price=" + RoundingPolicy.canonicalMoney(
				term.unitPrice() == null ? null : Money.of(term.unitPrice(), term.currency()))
				+ "|minPrice=" + RoundingPolicy.canonicalNumber(term.minimumUnitPrice())
				+ "|maxPrice=" + RoundingPolicy.canonicalNumber(term.maximumUnitPrice())
				+ "|ccy=" + RoundingPolicy.canonicalText(term.currency().value());
	}

	/** The financial content of a discount term; the currency may be absent by design. */
	private static String discountValueOf(DiscountTerm term) {
		return "|value=" + RoundingPolicy.canonicalNumber(term.discountValue())
				+ "|cap=" + RoundingPolicy.canonicalNumber(term.maxDiscountAmount())
				+ "|ccy=" + RoundingPolicy.canonicalText(term.currency() == null ? null : term.currency().value());
	}

	/**
	 * Terms are sorted before hashing because the caller decides the order they were
	 * loaded in, and that order carries no financial meaning. Hashing a
	 * caller-dependent order would report an input change where none occurred.
	 */
	private static List<PricingTerm> sortedPricingForCanonicalForm(List<PricingTerm> terms) {
		List<PricingTerm> copy = new ArrayList<>(terms);
		copy.sort(Comparator.comparing(PricingTerm::termId).thenComparingInt(PricingTerm::termVersion));
		return copy;
	}

	private static List<DiscountTerm> sortedDiscountForCanonicalForm(List<DiscountTerm> terms) {
		List<DiscountTerm> copy = new ArrayList<>(terms);
		copy.sort(Comparator.comparing(DiscountTerm::termId).thenComparingInt(DiscountTerm::termVersion));
		return copy;
	}

	private static boolean matchesProduct(String termProductKey, String lineProductKey) {
		if (termProductKey == null) {
			// A null product key on the term is the contract-wide default. It matches any
			// line, but PRICING_PRECEDENCE still ranks it below a product-specific term.
			return true;
		}
		return termProductKey.equals(lineProductKey);
	}

	private static String requireText(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new ValidationException(field + " must not be blank");
		}
		// Trimmed before it enters the checksum: "INV-42" and "INV-42 " are the same
		// invoice number and must not produce two different digests.
		return value.trim();
	}

}
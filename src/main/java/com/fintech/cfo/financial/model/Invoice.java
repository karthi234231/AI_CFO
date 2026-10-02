package com.fintech.cfo.financial.model;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.fintech.cfo.financial.enums.InvoiceStatus;
import com.fintech.cfo.financial.enums.SourceSystem;
import com.fintech.cfo.financial.normalization.EntityResolutionKey;
import com.fintech.cfo.shared.domain.CurrencyCode;
import com.fintech.cfo.shared.domain.Money;
import com.fintech.cfo.shared.domain.OrganizationId;
import com.fintech.cfo.shared.domain.SourceReference;
import com.fintech.cfo.shared.exception.ValidationException;
import com.fintech.cfo.shared.validation.Preconditions;
import com.fintech.cfo.shared.validation.Preconditions;

/**
 * Immutable mirror of the V4 {@code invoices} row.
 *
 * <p>The V4 schema stores {@code subtotal_amount}, {@code tax_amount}, and
 * {@code total_amount} as {@code NUMERIC(20,4)}. The canonical invoice carries
 * those three values as {@link Money} in a single currency. The compact
 * constructor does <em>not</em> enforce the identity {@code total == subtotal + tax}
 * (a real source may drift slightly). That arithmetic relationship is recomputed
 * and verified by the invoice reconciliation (e.g. {@link ReconciliationStatus})
 * rather than being a hard invariant of the persisted value.
 *
 * <p>{@code external_key} is nullable and participates in the partial unique
 * index {@code ux_invoices_org_source_key} in effect via source+number for
 * invoices (V4 has {@code ux_invoices_org_source_number}). Deduplication by
 * external key is only possible when non-null; {@link #resolutionKey()} returns
 * {@code null} if {@code externalKey} is absent.
 */
public record Invoice(
		UUID id,
		OrganizationId organizationId,
		UUID customerId,
		@Nullable UUID accountingPeriodId,
		String invoiceNumber,
		LocalDate invoiceDate,
		@Nullable LocalDate dueDate,
		InvoiceStatus status,
		Money subtotalAmount,
		Money taxAmount,
		Money totalAmount,
		@Nullable String externalKey,
		SourceReference source,
		long version) implements Serializable {

	/** {@code invoices.invoice_number VARCHAR(120)}. */
	public static final int MAX_INVOICE_NUMBER_LENGTH = 120;

	/** {@code invoices.external_key VARCHAR(255)}. */
	public static final int MAX_EXTERNAL_KEY_LENGTH = 255;

	/** Scale of {@code NUMERIC(20,4)} amount columns in V4. */
	public static final int AMOUNT_SCALE = 4;

	/** Integer digits for {@code NUMERIC(20,4)}. */
	public static final int AMOUNT_INTEGER_DIGITS = 16;

	public Invoice {
		Preconditions.requireNonNull(id, "id");
		// Tenancy is a constructor invariant, so every repository query built from
		// this record is scoped by construction.
		Preconditions.requireNonNull(organizationId, "organizationId");
		// The billed counterparty is required, not optional: an invoice with no
		// customer is a receivable nobody owns. The invoice's own
		// accountingPeriodId IS optional, because a source row arrives before the
		// period it belongs to has been resolved.
		Preconditions.requireNonNull(customerId, "customerId");
		// The invoice number is the natural half of
		// ux_invoices_org_source_number (organization_id, source_system,
		// invoice_number). It is trimmed but otherwise never reformatted - rewriting
		// it would break dedup on the next import of the same file.
		invoiceNumber = Preconditions.requireText(invoiceNumber, "invoiceNumber", MAX_INVOICE_NUMBER_LENGTH);
		// invoiceDate is the business date the period is resolved from.
		Preconditions.requireNonNull(invoiceDate, "invoiceDate");
		// dueDate stays optional: a null due date means "no stated payment terms" and
		// must not be defaulted to invoiceDate plus N days, which would invent a
		// collection deadline and silently drive an aging report.
		if (dueDate != null && dueDate.isBefore(invoiceDate)) {
			throw new ValidationException("dueDate must not be before invoiceDate");
		}
		// The lifecycle status is required because requiresSettlement() is what
		// decides whether the total is a receivable.
		Preconditions.requireNonNull(status, "status");
		// The three reported amounts are source facts. Each is constrained to
		// NUMERIC(20,4): precision 20 = 16 integer digits + scale 4.
		//
		// What is deliberately NOT enforced here is total == subtotal + tax. A real
		// source drifts, and overwriting the reported total with the recomputed one
		// would destroy the evidence of that drift. The identity is instead
		// recomputed and classified as a ReconciliationStatus.
		subtotalAmount = Preconditions.requireNumeric(subtotalAmount, "subtotalAmount", AMOUNT_INTEGER_DIGITS + AMOUNT_SCALE, AMOUNT_SCALE);
		taxAmount = Preconditions.requireNumeric(taxAmount, "taxAmount", AMOUNT_INTEGER_DIGITS + AMOUNT_SCALE, AMOUNT_SCALE);
		totalAmount = Preconditions.requireNumeric(totalAmount, "totalAmount", AMOUNT_INTEGER_DIGITS + AMOUNT_SCALE, AMOUNT_SCALE);
		// One currency across the header, proved pairwise rather than against a
		// separate column: there is no rate in this system that could legitimately
		// reconcile two currencies, so a mixed header is refused, never converted.
		requireSameCurrency(subtotalAmount, taxAmount);
		// The following six lines repeat checks already made above. They are
		// redundant rather than contradictory, and are left in place so this diff
		// stays comment-only.
		Preconditions.requireNonNull(id, "id");
		Preconditions.requireNonNull(organizationId, "organizationId");
		Preconditions.requireNonNull(customerId, "customerId");
		Preconditions.requireNonNull(invoiceDate, "invoiceDate");
		// NOTE: this contradicts the @Nullable dueDate declared on the component and
		// the null-tolerant check above. In its current state every invoice without a
		// stated due date is rejected at construction, although the column is nullable
		// in V4 and an absent due date is a legitimate source fact. Flagged, not
		// changed: the fix is to delete this line, which is outside a comment-only edit.
		Preconditions.requireNonNull(dueDate, "dueDate");
		Preconditions.requireNonNull(status, "status");
		// Second half of the same currency proof, closing the transitive relation
		// across all three amounts.
		requireSameCurrency(subtotalAmount, totalAmount);
		// externalKey may be null; that is the "not deduplicable by external key" case
		// which resolutionKey() reports as null.
		externalKey = Preconditions.optionalText(externalKey, "externalKey", MAX_EXTERNAL_KEY_LENGTH);
		// Lineage is mandatory: an invoice nobody can trace to an uploaded row is not
		// evidence of a receivable.
		Preconditions.requireNonNull(source, "source");
		if (version < 0) {
			throw new ValidationException("version must not be negative");
		}
	}

	/**
	 * Source system this invoice was resolved from.
	 *
	 * @return the lineage's source system as a closed-set constant
	 * @throws ValidationException if the lineage carries a code outside
	 *                              {@code SourceSystem}
	 */
	public SourceSystem sourceSystem() {
		return SourceSystem.fromCode(this.source.sourceSystem());
	}

	/**
	 * Currency of the invoice amounts.
	 *
	 * @return the currency of {@link #subtotalAmount()}; valid for all three
	 *         amounts because the compact constructor proved they agree
	 */
	public CurrencyCode currency() {
		return this.subtotalAmount.currency();
	}

	/**
	 * Key for deduplication if an external key exists; otherwise {@code null}.
	 * Note: V4 enforces uniqueness by (org, source_system, invoice_number) as
	 * {@code ux_invoices_org_source_number}. That is a natural key from the source;
	 * external_key is a separate nullable column. This method returns a
	 * resolution key only when external_key is present, matching the partial-key
	 * style used by Customer/Product.
	 *
	 * <p>The consequence for ingestion is that an invoice may be fully dedupable by
	 * the natural key while having no resolution key at all, because the source
	 * supplied a number and no external key. The repository must expose both
	 * lookups; using one where the other was meant either duplicates a re-numbered
	 * invoice or merges two invoices that share a number.
	 *
	 * @return the tenant/source/external-key triple, or {@code null} when
	 *         {@code externalKey} is absent
	 */
	public @Nullable EntityResolutionKey resolutionKey() {
		if (this.externalKey == null) {
			return null;
		}
		return EntityResolutionKey.of(this.organizationId, sourceSystem().code(), this.externalKey);
	}



	/**
	 * Proves two amounts share one currency before any comparison of them.
	 *
	 * @param left  first amount
	 * @param right second amount
	 * @throws ValidationException when the currencies differ; there is no
	 *                              conversion path here by design
	 */
	private static void requireSameCurrency(Money left, Money right) {
		if (!left.currency().equals(right.currency())) {
			throw new ValidationException("invoice amounts must share one currency: " + left.currency() + " vs "
					+ right.currency());
		}
	}
}

package com.fintech.cfo.financial.model;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.fintech.cfo.financial.enums.SourceSystem;
import com.fintech.cfo.financial.enums.TransactionType;
import com.fintech.cfo.financial.normalization.EntityResolutionKey;
import com.fintech.cfo.shared.domain.CurrencyCode;
import com.fintech.cfo.shared.domain.Money;
import com.fintech.cfo.shared.domain.OrganizationId;
import com.fintech.cfo.shared.domain.SourceReference;
import com.fintech.cfo.shared.exception.ValidationException;
import com.fintech.cfo.shared.validation.Preconditions;

/**
 * Immutable mirror of the V4 {@code financial_transactions} row.
 *
 * <p>Lineage is captured via {@link SourceReference} (which contains
 * source system, record type, record id, file id, row number). The schema's
 * {@code source_system} and {@code source_file_id}/row are preserved in the
 * source reference; an explicit {@link #sourceSystem()} accessor is provided for
 * convenience.
 *
 * <p>The {@code external_key} participates in the partial unique index
 * {@code ux_financial_tx_org_source_key (organization_id, source_system, external_key)
 * WHERE external_key IS NOT NULL}. A transaction with no external key cannot be
 * deduplicated by that index; {@link #resolutionKey()} returns {@code null} in
 * that case.
 */
public record FinancialTransaction(
		UUID id,
		OrganizationId organizationId,
		@Nullable UUID invoiceId,
		@Nullable UUID customerId,
		@Nullable UUID accountingPeriodId,
		LocalDate transactionDate,
		TransactionType transactionType,
		Money amount,
		@Nullable String description,
		@Nullable String externalKey,
		SourceReference source,
		long version) implements Serializable {

	/** {@code financial_transactions.description VARCHAR(1000)}. */
	public static final int MAX_DESCRIPTION_LENGTH = 1000;

	/** {@code financial_transactions.external_key VARCHAR(255)}. */
	public static final int MAX_EXTERNAL_KEY_LENGTH = 255;

	/** Scale of {@code NUMERIC(20,4)} amount columns in V4. */
	public static final int AMOUNT_SCALE = 4;

	/** Integer digits for {@code NUMERIC(20,4)}. */
	public static final int AMOUNT_INTEGER_DIGITS = 16;

	public FinancialTransaction {
		Preconditions.requireNonNull(id, "id");
		// Tenancy is a constructor invariant, so every repository query built from
		// this record is scoped by construction rather than by caller discipline.
		Preconditions.requireNonNull(organizationId, "organizationId");
		// The three ids below are deliberately NOT required non-null. A bank feed
		// transaction has no invoice and no period; a periodless posting is
		// legitimate input and is resolved (or rejected) by the service layer
		// against AccountingPeriod, not hidden here.
		// transactionDate is the business date and is required: without it the row
		// cannot be placed in a period at all.
		Preconditions.requireNonNull(transactionDate, "transactionDate");
		// The type, not the sign, says what happened. Requiring it here stops a
		// cash-flow total from having to infer classification from an amount.
		Preconditions.requireNonNull(transactionType, "transactionType");
		// Money carries the currency, so amount and currency cannot drift apart.
		Preconditions.requireNonNull(amount, "amount");
		// Lineage is mandatory: a monetary movement that cannot be traced to an
		// uploaded row is not evidence.
		Preconditions.requireNonNull(source, "source");
		description = Preconditions.optionalText(description, "description", MAX_DESCRIPTION_LENGTH);
		// externalKey may be null; that is the "not deduplicable" case, not a
		// missing value to be defaulted.
		externalKey = Preconditions.optionalText(externalKey, "externalKey", MAX_EXTERNAL_KEY_LENGTH);
		// Constrained to NUMERIC(20,4): precision 20 = 16 integer digits + scale 4.
		// The sign is preserved exactly as the source supplied it - an inverted
		// sign is an audit finding, never a formatting detail to fix on the way in.
		amount = Preconditions.requireNumeric(amount, "amount", AMOUNT_INTEGER_DIGITS + AMOUNT_SCALE, AMOUNT_SCALE);
		if (version < 0) {
			throw new ValidationException("version must not be negative");
		}
	}

	/**
	 * Source system this transaction was resolved from.
	 *
	 * @return the lineage's source system as a closed-set constant
	 * @throws ValidationException if the lineage carries a code outside
	 *                              {@code SourceSystem}
	 */
	public SourceSystem sourceSystem() {
		return SourceSystem.fromCode(this.source.sourceSystem());
	}

	/**
	 * The currency of the transaction amount.
	 *
	 * @return the currency of the stored {@link Money}, not a currency column of
	 *         its own - V4 stores both but they cannot disagree here
	 */
	public CurrencyCode currency() {
		return this.amount.currency();
	}

	/**
	 * Key for deduplication under the partial unique index, or {@code null} when
	 * no external key is supplied.
	 *
	 * <p>A {@code null} return is the load-bearing case: it tells the ingestion
	 * layer that this row must not be matched against anything. Falling back to a
	 * date-and-amount match would silently collapse two identical payments on the
	 * same day into one.
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


}

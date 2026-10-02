package com.fintech.cfo.financialtruth.model;

import java.util.List;

import com.fintech.cfo.shared.domain.Money;
import com.fintech.cfo.shared.domain.SourceReference;
import com.fintech.cfo.shared.exception.ValidationException;

/**
 * What was actually charged, plus the source rows it came from.
 *
 * <p>The source references are mandatory lineage: a monetary figure that cannot be
 * walked back to the invoice row that produced it is not shippable.
 */
public record ActualValue(Money amount, List<SourceReference> sources, String derivation) {

	public ActualValue {
		if (amount == null) {
			throw new ValidationException("actual amount must not be null");
		}
		sources = sources == null ? List.of() : List.copyOf(sources);
	}

	/**
	 * Single-source convenience factory.
	 *
	 * @param amount     what was charged, read straight from the invoice
	 * @param source     the invoice row this came from; null yields an empty list rather
	 *                   than being rejected, because lineage is enforced by the caller
	 *                   that assembles the snapshot
	 * @param derivation plain statement of where the figure came from
	 */
	public static ActualValue of(Money amount, SourceReference source, String derivation) {
		return new ActualValue(amount, source == null ? List.of() : List.of(source), derivation);
	}

	/**
	 * Fixed-order form of the amount and its source rows.
	 *
	 * <p>Sources are appended in the order supplied and never sorted, because the list
	 * is lineage rather than data: reordering it would change the fingerprint without
	 * changing anything a reader could observe about the figure.
	 */
	public String canonicalForm() {
		StringBuilder text = new StringBuilder();
		text.append("actual=").append(RoundingPolicy.canonicalMoney(this.amount)).append("|sources=");
		for (SourceReference source : this.sources) {
			text.append(source.toString()).append(';');
		}
		return text.toString();
	}

}
package com.fintech.cfo.financialtruth.model;

import java.util.ArrayList;
import java.util.List;

import com.fintech.cfo.financialtruth.enums.CalculationType;
import com.fintech.cfo.shared.domain.Money;
import com.fintech.cfo.shared.exception.ValidationException;

/**
 * What the contract says should have been charged, plus the terms that produced it.
 *
 * <p>Deliberately not just a {@link Money}: the amount alone cannot be defended.
 * The derivation states how the figure was reached and the evaluated terms state
 * which contract versions were consulted, so the number can be re-derived rather
 * than merely recomputed.
 */
public record ExpectedValue(
		Money amount,
		CalculationType basis,
		List<TermEvaluation> evaluatedTerms,
		String derivation) {

	public ExpectedValue {
		if (amount == null) {
			throw new ValidationException("expected amount must not be null");
		}
		if (basis == null) {
			throw new ValidationException("basis must not be null");
		}
		evaluatedTerms = evaluatedTerms == null ? List.of() : List.copyOf(evaluatedTerms);
	}

	/**
	 * @param amount      what the contract entitles, already rounded
	 * @param basis       which calculation produced it
	 * @param derivation  plain statement of how the figure was reached
	 * @param terms       the contract versions consulted; varargs so a caller cannot
	 *                    forget them by omitting the argument
	 */
	public static ExpectedValue of(Money amount, CalculationType basis, String derivation,
			TermEvaluation... terms) {
		return new ExpectedValue(amount, basis, new ArrayList<>(List.of(terms)), derivation);
	}

	/**
	 * Fixed-order form of the amount, its basis and the terms consulted.
	 *
	 * <p>The derivation is deliberately excluded: it is prose, so including it would
	 * make the fingerprint sensitive to wording rather than to the figure.
	 */
	public String canonicalForm() {
		StringBuilder text = new StringBuilder();
		text.append("expected=").append(RoundingPolicy.canonicalMoney(this.amount))
				.append("|basis=").append(this.basis.name())
				.append("|terms=");
		for (TermEvaluation term : this.evaluatedTerms) {
			text.append(term.canonicalForm()).append(';');
		}
		return text.toString();
	}

}
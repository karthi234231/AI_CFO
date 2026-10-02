package com.fintech.cfo.contract.extraction;

import java.util.List;
import java.util.UUID;

/**
 * The port for reading commercial clauses out of a contract document.
 *
 * <p>Declared here, in the consumer, so that the deterministic
 * {@link ContractTermExtractionService} and any later document-interpreting
 * adapter are interchangeable to a caller. Rule 11 keeps the outbound model call
 * out of this module: the interface is the whole of the contract, and no
 * implementation here opens a network connection.
 *
 * <p>Two properties every implementation owes its caller, and which are why the
 * port returns proposals rather than terms:
 *
 * <ul>
 * <li><b>Nothing is trusted.</b> A result is an {@link ExtractedContractTerm}, not
 * a stored {@code ContractTerm}. Accepting one is a separate, human decision.</li>
 * <li><b>Nothing is silent.</b> A document with no recognisable clause yields an
 * empty list, never a fabricated default. An empty list and a guess look
 * identical in a price, and only one of them is defensible.</li>
 * </ul>
 *
 * <p>Implementations are pure: the same text always yields the same candidates, so
 * a re-run reproduces what was proposed the first time.
 */
public interface ContractTermExtractor {

	/**
	 * @param contractId the contract the text belongs to, carried on every
	 * candidate so a proposal can be filed against its contract without the caller
	 * having to re-associate it
	 * @param documentText the document body, as text; interpreting binary formats is
	 * the caller's concern, not this port's
	 * @return the candidates found, in the order they appear in the document; never
	 * {@code null}
	 */
	List<ExtractedContractTerm> extract(UUID contractId, String documentText);

}
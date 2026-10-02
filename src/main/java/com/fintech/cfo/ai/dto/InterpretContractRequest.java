package com.fintech.cfo.ai.dto;

import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.fintech.cfo.shared.validation.Preconditions;

/**
 * Request body for "interpret a contract": extract its commercial terms.
 *
 * <p>The document is addressed by reference, not carried as bytes in the body.
 * A controller stores the upload through
 * {@link com.fintech.cfo.platform.storage.ObjectStoragePort}, then passes the
 * resulting key here so the interpreter can fetch and parse it. If the caller
 * already has plain text, {@link #contractText()} short-circuits the fetch and
 * the parse, which is how the tests drive the layer without OpenPDF.
 *
 * @param contractId         the contract these terms belong to
 * @param documentReference  object key to fetch the source document by, or null
 * @param contractText       pre-extracted clause text, or null to fetch the document
 */
public record InterpretContractRequest(
		UUID contractId,
		@Nullable String documentReference,
		@Nullable String contractText) {

	public InterpretContractRequest {
		Preconditions.requireNonNull(contractId, "contractId");
		// One source of text is mandatory: either a reference to fetch, or text on
		// hand. Accepting both as null would leave the interpreter with nothing to
		// read and the failure would surface much later as an empty extraction.
		Preconditions.require(documentReference != null || contractText != null,
				"either documentReference or contractText must be supplied");
	}

}

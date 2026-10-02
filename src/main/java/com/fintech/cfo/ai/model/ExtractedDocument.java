package com.fintech.cfo.ai.model;

/**
 * Text pulled from a source document, accompanied by the checksum and page
 * count that make the extraction reproducible and traceable.
 *
 * <p>Returned by {@link com.fintech.cfo.ai.client.DocumentExtractionPort}, so it
 * is a port DTO rather than a persisted domain type: the text is transient and
 * is not stored on an {@code ai} artefact, only its checksum is.
 *
 * @param text      the extracted page text
 * @param sha256    SHA-256 of the extracted text, for change detection
 * @param pageCount number of pages the source reported
 */
public record ExtractedDocument(String text, String sha256, int pageCount) {

	public ExtractedDocument {
		if (text == null || text.isEmpty()) {
			throw new IllegalArgumentException("ExtractedDocument text must not be empty");
		}
		if (sha256 == null || sha256.isEmpty()) {
			throw new IllegalArgumentException("ExtractedDocument sha256 must not be empty");
		}
		if (pageCount < 0) {
			throw new IllegalArgumentException("pageCount must not be negative");
		}
	}

}

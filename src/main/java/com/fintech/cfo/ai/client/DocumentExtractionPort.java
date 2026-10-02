package com.fintech.cfo.ai.client;

import com.fintech.cfo.ai.model.ExtractedDocument;

/**
 * Port behind which the document-extraction client sits.
 *
 * <p>Turns a raw document (a PDF byte stream today) into text plus a checksum and
 * page count. The local {@link com.fintech.cfo.ai.pdf.PdfExtractionService}
 * implements this with OpenPDF; the remote {@link DocumentExtractionClient}
 * stub will be an alternative adapter wired in the transport pass. Both return
 * the same {@link ExtractedDocument}, so the orchestrators never know which
 * parser ran — and never need to.
 *
 * @param content the raw document bytes
 * @return the extracted text, its SHA-256, and the page count
 */
public interface DocumentExtractionPort {

	ExtractedDocument extract(byte[] content);

}

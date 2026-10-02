package com.fintech.cfo.ai.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

import com.fintech.cfo.ai.client.DocumentExtractionPort;
import com.fintech.cfo.ai.dto.ContractInterpretationResponse;
import com.fintech.cfo.ai.dto.InterpretContractRequest;
import com.fintech.cfo.ai.extraction.ContractExtractionService;
import com.fintech.cfo.ai.extraction.ExtractionResult;
import com.fintech.cfo.ai.model.AiAnalysis;
import com.fintech.cfo.platform.storage.ObjectStoragePort;
import com.fintech.cfo.shared.domain.OrganizationId;
import com.fintech.cfo.shared.domain.SourceReference;
import com.fintech.cfo.shared.exception.NotFoundException;
import com.fintech.cfo.shared.util.DateTimeUtils;

/**
 * Orchestrates a contract-interpretation request end to end.
 *
 * <p>This is the use-case layer the controllers delegate to: it resolves the
 * document (a stored reference or an inline text), turns the bytes into text
 * through the {@link DocumentExtractionPort}, and hands the text to the
 * extraction engine. What it returns is an {@link AiAnalysis} — the verifiable
 * artefact with provenance and timestamp — not the raw model output.
 */
public final class ContractInterpretationService {

	private final ObjectStoragePort storage;
	private final DocumentExtractionPort documentExtraction;
	private final ContractExtractionService extraction;
	private final DateTimeUtils clock;

	public ContractInterpretationService(ObjectStoragePort storage, DocumentExtractionPort documentExtraction,
			ContractExtractionService extraction, DateTimeUtils clock) {
		this.storage = storage;
		this.documentExtraction = documentExtraction;
		this.extraction = extraction;
		this.clock = clock;
	}

	/**
	 * Interprets a contract, returning its extracted commercial terms.
	 *
	 * @param organizationId tenant scoping the artefact
	 * @param request        what to interpret and where the source document lives
	 * @return the interpreted contract and its provenance
	 */
	public ContractInterpretationResponse interpret(OrganizationId organizationId, InterpretContractRequest request) {
		// The reference points back at the source contract; file id is the upload
		// key when the document was fetched, null when text was supplied inline.
		SourceReference source = SourceReference.of("CONTRACT", "CONTRACT", request.contractId().toString(),
				request.documentReference(), null);

		String documentText = resolveDocumentText(request);
		ExtractionResult run = this.extraction.extract(organizationId, source, documentText);

		// The extraction run id doubles as the analysis id: for this flow one
		// extraction maps to one interpretation, so correlating them by name is
		// cheaper than minting a second identifier and keeping the two in step.
		AiAnalysis analysis = new AiAnalysis(run.runId(), run.terms(), null, run.processingStatus(),
				run.validationStatus(), run.inputChecksum(), run.source(), this.clock.now());
		return new ContractInterpretationResponse(request.contractId(), analysis.terms(), analysis.explanation(),
				analysis.validationStatus(), analysis.inputChecksum(), analysis.analysisId());
	}

	private String resolveDocumentText(InterpretContractRequest request) {
		if (request.contractText() != null && !request.contractText().isBlank()) {
			return request.contractText();
		}
		// No inline text: fetch the uploaded document by reference. The stream is
		// read and discarded within this call, so the full document is never held
		// on the artefact — only its checksum is (§5).
		String key = request.documentReference();
		try (InputStream in = this.storage.retrieve(key)) {
			if (in == null) {
				throw new NotFoundException("document not found: " + key);
			}
			byte[] content = in.readAllBytes();
			return this.documentExtraction.extract(content).text();
		}
		catch (IOException ex) {
			throw new IllegalStateException("could not read document " + key, ex);
		}
	}

}

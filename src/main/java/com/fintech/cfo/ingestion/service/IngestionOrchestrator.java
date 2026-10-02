package com.fintech.cfo.ingestion.service;

import java.io.ByteArrayInputStream;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.fintech.cfo.ingestion.enums.FileType;
import com.fintech.cfo.ingestion.enums.RejectionReason;
import com.fintech.cfo.ingestion.model.FileParseResult;
import com.fintech.cfo.ingestion.model.IngestionRequest;
import com.fintech.cfo.ingestion.model.UploadMetadata;
import com.fintech.cfo.ingestion.parser.CsvFileParser;
import com.fintech.cfo.ingestion.parser.ExcelFileParser;
import com.fintech.cfo.ingestion.parser.FileParser;
import com.fintech.cfo.ingestion.parser.ParseOutcome;
import com.fintech.cfo.ingestion.parser.ParseRequest;

/**
 * Routes an admitted upload to the reader for its declared type and hands back a
 * {@link ParseOutcome}.
 *
 * <p>The parser set is an {@link EnumMap} keyed by {@link FileType}, chosen from
 * the declared type rather than by switching on it here, so adding a format is a
 * new {@link FileParser} on the constructor and nothing else. Selection is by
 * enumeration, not by {@code instanceof}, so no parser can ever be reached for a
 * type it did not claim.
 *
 * <p>Routing reads only the already-admitted {@link UploadMetadata}, never the
 * original filename: the name that reaches a {@link ParseRequest} is the sanitised
 * one, so a row coordinate can never quote a traversal path back to a client.
 *
 * <p>No exception escapes for bad input. A type with no registered reader, or a
 * parser that throws something unexpected, becomes a stated refusal — the
 * orchestrator's contract is that it always gets an outcome back.
 */
public final class IngestionOrchestrator {

	private final Map<FileType, FileParser> parsers;

	public IngestionOrchestrator() {
		this(List.of(new CsvFileParser(), new ExcelFileParser()));
	}

	public IngestionOrchestrator(List<FileParser> parsers) {
		Objects.requireNonNull(parsers, "parsers must not be null");
		EnumMap<FileType, FileParser> byType = new EnumMap<>(FileType.class);
		for (FileParser parser : parsers) {
			Objects.requireNonNull(parser, "parsers must not contain null");
			FileParser previous = byType.put(parser.supportedType(), parser);
			if (previous != null) {
				throw new IllegalArgumentException("two parsers claim " + parser.supportedType() + ": "
						+ previous.getClass().getName() + " and " + parser.getClass().getName());
			}
		}
		this.parsers = Map.copyOf(byType);
	}

	/** @return the types that can actually be read, in enum order */
	public Set<FileType> supportedTypes() {
		return this.parsers.keySet();
	}

	/**
	 * @param request  the upload, already through the security gate
	 * @param metadata what the gate proved about it
	 * @return what the reader made of it; never {@code null} and never throwing
	 */
	public ParseOutcome parse(IngestionRequest request, UploadMetadata metadata) {
		Objects.requireNonNull(request, "request must not be null");
		Objects.requireNonNull(metadata, "metadata must not be null");

		FileType type = metadata.declaredFileType();
		FileParser parser = this.parsers.get(type);
		if (parser == null) {
			return ParseOutcome.of(FileParseResult.failed(type, RejectionReason.UNSUPPORTED_FILE_TYPE,
					"no reader is registered for " + type.code() + "; CSV and XLSX are the readable formats"));
		}

		ParseRequest parseRequest = ParseRequest.of(new ByteArrayInputStream(request.contentUnsafe()),
				metadata.fileId(), metadata.displayName(), metadata.contentType(), request.asOfDate(),
				request.limits());
		try {
			return ParseOutcome.of(parser.parse(parseRequest));
		}
		catch (RuntimeException ex) {
			// A reader is supposed to refuse bad input rather than throw. If one does
			// not, the upload is still refused and the reason still says what happened;
			// the class name is included because it is a diagnostic, not file content.
			return ParseOutcome.of(FileParseResult.failed(type, RejectionReason.ROW_READ_FAILED,
					"the reader failed unexpectedly (" + ex.getClass().getSimpleName() + ")"));
		}
	}

	/**
	 * @return the reader for a type, for callers that want to inspect the dialect
	 * in force (a diagnostic export, a test)
	 */
	public Optional<FileParser> readerFor(FileType type) {
		return Optional.ofNullable(this.parsers.get(Objects.requireNonNull(type, "type must not be null")));
	}

}
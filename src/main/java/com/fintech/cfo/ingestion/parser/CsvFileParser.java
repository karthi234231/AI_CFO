package com.fintech.cfo.ingestion.parser;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

import org.apache.commons.csv.CSVException;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import com.fintech.cfo.ingestion.enums.FieldType;
import com.fintech.cfo.ingestion.enums.FileType;
import com.fintech.cfo.ingestion.enums.HeaderMode;
import com.fintech.cfo.ingestion.enums.IngestionErrorType;
import com.fintech.cfo.ingestion.enums.ParseStatus;
import com.fintech.cfo.ingestion.enums.RejectionReason;
import com.fintech.cfo.ingestion.enums.ValidationSeverity;
import com.fintech.cfo.ingestion.model.FileParseResult;
import com.fintech.cfo.ingestion.model.IngestionLimits;
import com.fintech.cfo.ingestion.model.ParsedCell;
import com.fintech.cfo.ingestion.model.ParsedRow;
import com.fintech.cfo.ingestion.model.RejectedRow;
import com.fintech.cfo.ingestion.model.RowCoordinate;
import com.fintech.cfo.ingestion.model.ValidationFinding;

/**
 * Delimited-text reader built on Apache Commons CSV.
 *
 * <p>Quoting, embedded delimiters, embedded newlines, CRLF and BOM handling all
 * belong to Commons CSV; this class does not re-implement any of it. What it adds
 * is the policy a financial export actually needs:
 *
 * <ol>
 * <li><b>Headerless tokenisation.</b> The file is tokenised without registering a
 * header with Commons CSV, so a record of the wrong width comes back as a plain
 * list instead of throwing. That is what makes ragged rows recoverable instead of
 * fatal, and it is why one bad row cannot abort a 100k-row file.</li>
 * <li><b>Row-level isolation.</b> Every record is materialised inside its own
 * try/catch. A wrong field count, a cell past the column limit, or a cell past
 * the text limit becomes a {@link RejectedRow} carrying its 1-based record number,
 * and reading continues with the next record.</li>
 * <li><b>Honest lexical failure.</b> A fault inside the lexer — an unterminated
 * quote in the middle of a file — cannot be resumed from, because the token
 * stream has already been consumed. Rather than pretend otherwise, the records
 * read up to that point are kept, the status becomes {@code PARTIAL}, and the
 * fault is reported with a content-free reason. Nothing is dropped silently and
 * nothing is claimed to have been read that was not.</li>
 * </ol>
 *
 * <p>Record numbers are 1-based and count the header, matching what a person
 * counting rows in the file would say — which is what makes them usable as
 * evidence.
 *
 * <p>Cells arrive typed as {@link FieldType#STRING} even when they look numeric.
 * A delimited file carries no type information, so typing a cell here would bake
 * an inference into the evidence; {@code DataTypeValidator} types each column
 * against the declared schema instead.
 */
public final class CsvFileParser implements FileParser {

	private static final String NO_SHEET = "";

	private final CsvParseOptions options;

	public CsvFileParser() {
		this(CsvParseOptions.defaults());
	}

	public CsvFileParser(CsvParseOptions options) {
		this.options = Objects.requireNonNull(options, "options must not be null");
	}

	public CsvParseOptions options() {
		return this.options;
	}

	@Override
	public FileType supportedType() {
		return FileType.CSV;
	}

	@Override
	public FileParseResult parse(ParseRequest request) {
		Objects.requireNonNull(request, "request must not be null");
		byte[] raw;
		try {
			raw = request.readContent();
		}
		catch (ParseRequest.ContentTooLargeException ex) {
			return FileParseResult.failed(FileType.CSV, RejectionReason.SIZE_LIMIT_EXCEEDED, ex.getMessage());
		}
		catch (IOException ex) {
			return FileParseResult.failed(FileType.CSV, RejectionReason.CORRUPT_FILE,
					"the upload stream could not be read (" + ex.getClass().getSimpleName() + ")");
		}

		if (raw.length == 0) {
			return FileParseResult.failed(FileType.CSV, RejectionReason.EMPTY_FILE, "the file contains no bytes");
		}

		TextDecoding.Decoded decoded = TextDecoding.decode(raw, this.options.defaultCharset());
		if (!TextDecoding.decodesCleanly(raw, decoded.bodyOffset(), decoded.charset())) {
			return FileParseResult.failed(FileType.CSV, RejectionReason.CORRUPT_FILE,
					"the content is not valid " + decoded.charset().name()
							+ " text, so it is not a readable delimited file");
		}

		String text;
		try {
			text = TextDecoding.toText(raw, decoded.bodyOffset(), decoded.charset());
		}
		catch (CharacterCodingException ex) {
			return FileParseResult.failed(FileType.CSV, RejectionReason.CORRUPT_FILE,
					"the content could not be decoded as " + decoded.charset().name() + " text");
		}

		Tokenisation tokenisation = tokenise(text);
		FileParseResult.Builder result = FileParseResult.builder(FileType.CSV);
		result.sheetName(NO_SHEET);

		List<List<String>> records = tokenisation.records();
		if (records.stream().allMatch(CsvFileParser::isBlankRecord)) {
			return FileParseResult.failed(FileType.CSV, RejectionReason.EMPTY_FILE,
					"the file contains no data beyond whitespace");
		}
		if (records.isEmpty()) {
			return FileParseResult.failed(FileType.CSV, RejectionReason.EMPTY_FILE, "the file contains no records");
		}
		if (tokenisation.fault != null) {
			result.add(ValidationFinding.file(IngestionErrorType.PARSE, tokenisation.fault.reason(),
					tokenisation.fault.detail()));
		}

		IngestionLimits limits = request.limits();
		int headerIndex = resolveHeaderIndex(records, limits.headerScanWindow());
		List<String> columnNames;
		int dataStartIndex;
		if (headerIndex >= 0) {
			// Recorded before HeaderDetector rewrites repeats, so the schema gate can
			// still see that the header carried the same label twice.
			result.rawHeader(records.get(headerIndex));
			columnNames = HeaderDetector.normalise(records.get(headerIndex));
			dataStartIndex = headerIndex + 1;
			result.headerRowNumber(headerIndex + 1);
			for (int index = 0; index < headerIndex; index++) {
				result.skipRows(1);
				result.add(ValidationFinding.file(ValidationSeverity.INFO, IngestionErrorType.SCHEMA,
						RejectionReason.MISSING_HEADER,
						"row " + (index + 1) + " precedes the header row and was not treated as data"));
			}
		}
		else {
			int width = 0;
			for (List<String> record : records) {
				width = Math.max(width, record.size());
			}
			columnNames = HeaderDetector.positionalNames(width);
			dataStartIndex = 0;
			result.headerRowNumber(-1);
		}
		result.columns(columnNames);

		int accepted = 0;
		boolean rowLimitReached = false;
		for (int index = dataStartIndex; index < records.size(); index++) {
			List<String> cells = records.get(index);
			long rowNumber = index + 1L;

			if (isBlankRecord(cells)) {
				result.skipRows(1);
				if (!this.options.ignoreEmptyLines()) {
					result.add(ValidationFinding.file(ValidationSeverity.INFO, IngestionErrorType.PARSE,
							RejectionReason.BLANK_ROW, "row " + rowNumber + " is blank and was skipped"));
				}
				continue;
			}
			if (accepted >= limits.maxRowsPerFile()) {
				rowLimitReached = true;
				result.add(ValidationFinding.file(IngestionErrorType.LIMIT_EXCEEDED, RejectionReason.MAX_ROWS_EXCEEDED,
						"reading stopped at the configured maximum of " + limits.maxRowsPerFile()
								+ " rows; the remainder of the file was not read"));
				break;
			}

			RowCoordinate coordinate = new RowCoordinate(request.sourceFileId(), request.fileName(), rowNumber);
			RowOutcome outcome = materialise(coordinate, cells, columnNames, limits);
			if (outcome.row() != null) {
				result.add(outcome.row());
				accepted++;
			}
			else {
				result.reject(outcome.rejection());
			}
		}

		if (tokenisation.fault != null || rowLimitReached) {
			result.status(ParseStatus.PARTIAL);
			result.failureReason(tokenisation.fault != null ? tokenisation.fault.detail()
					: "reading stopped at the configured row limit");
		}
		return result.build();
	}

	/**
	 * Builds the row or the rejection for it, and returns exactly one of the two.
	 *
	 * <p>This method is the whole of row-level isolation: it can only ever produce
	 * a {@link ParsedRow} or a {@link RejectedRow} for the record it was given, so
	 * there is no third outcome in which a bad row vanishes without a trace.
	 */
	private RowOutcome materialise(RowCoordinate coordinate, List<String> cells, List<String> columnNames,
			IngestionLimits limits) {
		if (cells.size() > limits.maxColumns()) {
			return RowOutcome.rejected(RejectedRow.of(coordinate, RejectionReason.FIELD_COUNT_MISMATCH,
					"the record has " + cells.size() + " fields but at most " + limits.maxColumns()
							+ " columns are permitted"));
		}
		if (cells.size() != columnNames.size()) {
			return RowOutcome.rejected(RejectedRow.of(coordinate, RejectionReason.FIELD_COUNT_MISMATCH,
					"the record has " + cells.size() + " fields but the header declares " + columnNames.size()));
		}
		List<ParsedCell> parsed = new ArrayList<>(cells.size());
		for (int index = 0; index < cells.size(); index++) {
			String columnName = columnNames.get(index);
			String value = cells.get(index);
			if (isBlank(value)) {
				parsed.add(ParsedCell.blank(columnName, index));
				continue;
			}
			if (value.length() > limits.maxCellTextLength()) {
				return RowOutcome.rejected(RejectedRow.ofField(coordinate, columnName, RejectionReason.VALUE_TOO_LONG,
						"the value is " + value.length() + " characters long; the limit is "
								+ limits.maxCellTextLength(),
						null));
			}
			parsed.add(new ParsedCell(columnName, index, FieldType.STRING, value, value));
		}
		return RowOutcome.accepted(ParsedRow.of(coordinate, FileType.CSV, parsed));
	}

	private int resolveHeaderIndex(List<List<String>> records, int scanWindow) {
		return switch (this.options.headerMode()) {
			case NONE -> -1;
			case FIRST_RECORD -> firstNonBlankIndex(records);
			case AUTO -> HeaderDetector.detect(records, scanWindow);
		};
	}

	private static int firstNonBlankIndex(List<List<String>> records) {
		for (int index = 0; index < records.size(); index++) {
			if (!isBlankRecord(records.get(index))) {
				return index;
			}
		}
		return -1;
	}

	/**
	 * Tokenises with Commons CSV in headerless mode and captures a lexer fault
	 * instead of propagating it, so the records already read survive.
	 */
	private Tokenisation tokenise(String text) {
		List<List<String>> records = new ArrayList<>();
		CSVFormat format = CSVFormat.DEFAULT.builder()
			.setDelimiter(this.options.delimiter())
			.setQuote(this.options.quote())
			.setEscape(this.options.escape())
			.setIgnoreEmptyLines(this.options.ignoreEmptyLines())
			.setIgnoreSurroundingSpaces(false)
			.setTrim(this.options.trimUnquoted())
			.setLenientEof(this.options.lenientEndOfFile())
			.build();

		try (StringReader reader = new StringReader(text); CSVParser parser = format.parse(reader)) {
			Iterator<CSVRecord> iterator = parser.iterator();
			while (iterator.hasNext()) {
				CSVRecord record = iterator.next();
				List<String> cells = new ArrayList<>(record.size());
				for (int index = 0; index < record.size(); index++) {
					cells.add(record.get(index));
				}
				records.add(cells);
			}
			return new Tokenisation(records, null);
		}
		catch (CSVException ex) {
			return new Tokenisation(records, new ParseFault(RejectionReason.TRUNCATED_RECORD,
					"the delimited text could not be tokenised past record " + records.size() + " ("
							+ ex.getClass().getSimpleName() + ")"));
		}
		catch (UncheckedIOException ex) {
			// WHY a CSVException cause is a truncated record and not a corrupt file:
			// Commons CSV reaches end-of-input inside an unterminated quoted field by
			// throwing CSVException, which the iterator wraps in an UncheckedIOException.
			// That is the single most common real-world CSV malformation and the result
			// is already PARTIAL with every record read so far kept, so reporting it as
			// CORRUPT_FILE would tell finance the whole file is untrustworthy when only
			// the remainder of the last record is missing. Any other cause is a genuine
			// I/O failure and keeps the corrupt classification.
			return new Tokenisation(records,
					ex.getCause() instanceof CSVException
							? new ParseFault(RejectionReason.TRUNCATED_RECORD,
									"the delimited text could not be tokenised past record " + records.size()
											+ " (" + ex.getCause().getClass().getSimpleName()
											+ "); the rest of the file was not read")
							: new ParseFault(RejectionReason.CORRUPT_FILE,
									"the delimited text could not be read past record " + records.size()));
		}
		catch (IOException ex) {
			return new Tokenisation(records, new ParseFault(RejectionReason.CORRUPT_FILE,
					"the delimited text stream failed at record " + records.size()));
		}
		catch (RuntimeException ex) {
			return new Tokenisation(records, new ParseFault(RejectionReason.UNREADABLE_CELL,
					"the delimited text could not be tokenised past record " + records.size() + " ("
							+ ex.getClass().getSimpleName() + ")"));
		}
	}

	private static boolean isBlank(String value) {
		return value == null || value.trim().isEmpty();
	}

	private static boolean isBlankRecord(List<String> cells) {
		return cells.stream().allMatch(CsvFileParser::isBlank);
	}

	/** Records recovered, plus the lexer fault that stopped tokenisation, if any. */
	private record Tokenisation(List<List<String>> records, ParseFault fault) {
	}

	/** Content-free description of a fault inside the CSV lexer (rule 5). */
	private record ParseFault(RejectionReason reason, String detail) {
	}

	/** Exactly one of {@code row} / {@code rejection} is non-null. */
	private record RowOutcome(ParsedRow row, RejectedRow rejection) {

		static RowOutcome accepted(ParsedRow row) {
			return new RowOutcome(Objects.requireNonNull(row, "row must not be null"), null);
		}

		static RowOutcome rejected(RejectedRow rejection) {
			return new RowOutcome(null, Objects.requireNonNull(rejection, "rejection must not be null"));
		}

	}

}
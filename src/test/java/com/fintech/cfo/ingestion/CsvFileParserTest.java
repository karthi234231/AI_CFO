package com.fintech.cfo.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.fintech.cfo.ingestion.enums.ColumnType;
import com.fintech.cfo.ingestion.enums.FieldType;
import com.fintech.cfo.ingestion.enums.FileType;
import com.fintech.cfo.ingestion.enums.HeaderMode;
import com.fintech.cfo.ingestion.enums.ParseStatus;
import com.fintech.cfo.ingestion.enums.RejectionReason;
import com.fintech.cfo.ingestion.model.ColumnSchema;
import com.fintech.cfo.ingestion.model.FileParseResult;
import com.fintech.cfo.ingestion.model.IngestionLimits;
import com.fintech.cfo.ingestion.model.IngestionSchema;
import com.fintech.cfo.ingestion.model.ParsedRow;
import com.fintech.cfo.ingestion.model.RejectedRow;
import com.fintech.cfo.ingestion.model.ValidationFinding;
import com.fintech.cfo.ingestion.model.ValidationResult;
import com.fintech.cfo.ingestion.parser.CsvFileParser;
import com.fintech.cfo.ingestion.parser.CsvParseOptions;
import com.fintech.cfo.ingestion.parser.ParseOutcome;
import com.fintech.cfo.ingestion.parser.ParseRequest;
import com.fintech.cfo.ingestion.service.FileValidationService;

/**
 * Reader tests for the delimited-text path.
 *
 * <p>Every fixture is inline and every assertion is on a value a person can check
 * by eye from the source string. No file on disk, no clock, no database — the point
 * of the parser being pure is that its behaviour is legible from the test alone.
 *
 * <p>The cases chosen are the ones where a hand-rolled reader is usually wrong:
 * embedded delimiters and newlines inside quoted fields, ragged records, a byte
 * order mark, alternative delimiters, and a formula payload.
 */
class CsvFileParserTest {

	private static final LocalDate AS_OF = LocalDate.of(2024, 6, 30);

	private static final String FILE_ID = "6b1f2f36-1c1e-4a5d-9a1a-7f6b2f0f0a11";

	private static final String FILE_NAME = "ledger-june.csv";

	// WHY referenced without a `this.` prefix from the @Nested class below: `this`
	// inside a nested class is the *nested* instance, and this field lives on the
	// enclosing instance. The simple name still resolves to the outer field.
	private final CsvFileParser parser = new CsvFileParser();

	private FileParseResult parse(String content) {
		return parse(content, CsvParseOptions.defaults());
	}

	// WHY a byte[] overload exists: the BOM and encoding fixtures below are not
	// expressible as a String, because re-encoding the String would re-create the
	// very bytes under test. They therefore have to reach the reader as raw bytes.
	private FileParseResult parse(byte[] content) {
		return new CsvFileParser().parse(request(content));
	}

	private FileParseResult parse(String content, CsvParseOptions options) {
		return new CsvFileParser(options).parse(request(content));
	}

	private ParseRequest request(String content) {
		return request(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	private ParseRequest request(byte[] content) {
		return ParseRequest.of(new java.io.ByteArrayInputStream(content), FILE_ID, FILE_NAME, "text/csv", AS_OF,
				IngestionLimits.defaults());
	}

	private FileParseResult parse(String content, CsvParseOptions options, IngestionLimits limits) {
		byte[] bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		return new CsvFileParser(options)
			.parse(ParseRequest.of(new java.io.ByteArrayInputStream(bytes), FILE_ID, FILE_NAME, "text/csv", AS_OF,
					limits));
	}

	@Nested
	@DisplayName("quoting and dialect")
	class QuotingAndDialect {

		@Test
		@DisplayName("a quoted field may hold the delimiter and a newline without shifting the row number")
		void handlesDelimitersAndNewlinesInsideQuotedFields() {
			FileParseResult result = parse("vendor,description,amount\n"
					+ "\"Acme, Inc.\",\"Widget, large\",1000.00\n"
					+ "\"Multi\nLine Co\",\"line one\nline two\",250.00\n");

			assertThat(result.status()).isEqualTo(ParseStatus.SUCCESS);
			assertThat(result.acceptedRowCount()).isEqualTo(2);

			ParsedRow first = result.rows().get(0);
			assertThat(first.text("vendor")).isEqualTo("Acme, Inc.");
			assertThat(first.text("description")).isEqualTo("Widget, large");
			assertThat(first.coordinate().rowNumber()).isEqualTo(2L);

			ParsedRow second = result.rows().get(1);
			assertThat(second.text("vendor")).isEqualTo("Multi\nLine Co");
			// The embedded newline is inside the field, so this row is still record 3.
			assertThat(second.coordinate().rowNumber()).isEqualTo(3L);
		}

		@Test
		@DisplayName("an escaped quote inside a quoted field is read as one literal quote")
		void readsEscapedQuotes() {
			FileParseResult result = parse("narration,amount\n\"the \"\"best\"\" deal\",42.00\n");

			assertThat(result.acceptedRowCount()).isEqualTo(1);
			assertThat(result.rows().get(0).text("narration")).isEqualTo("the \"best\" deal");
		}

		@Test
		@DisplayName("a semicolon or tab delimiter is honoured when the options ask for it")
		void readsAlternativeDelimiters() {
			FileParseResult semicolons = parse("vendor;amount\nAcme;1000.00\n",
					CsvParseOptions.semicolonSeparated());
			assertThat(semicolons.acceptedRowCount()).isEqualTo(1);
			assertThat(semicolons.rows().get(0).text("amount")).isEqualTo("1000.00");

			FileParseResult tabs = parse("vendor\tamount\nAcme\t1000.00\n", CsvParseOptions.tabSeparated());
			assertThat(tabs.acceptedRowCount()).isEqualTo(1);
			assertThat(tabs.rows().get(0).text("vendor")).isEqualTo("Acme");
		}

		@Test
		@DisplayName("CRLF line endings are read as record separators, not as field content")
		void readsWindowsLineEndings() {
			FileParseResult result = parse("vendor,amount\r\nAcme,1000.00\r\nTata,250.00\r\n");

			assertThat(result.acceptedRowCount()).isEqualTo(2);
			assertThat(result.rows().get(1).text("vendor")).isEqualTo("Tata");
			assertThat(result.rows().get(1).text("amount")).isEqualTo("250.00");
		}

		@Test
		@DisplayName("a UTF-8 byte order mark is stripped instead of becoming part of the first column name")
		void stripsTheByteOrderMark() {
			byte[] content = concat(new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF },
					"vendor,amount\nAcme,1000.00\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));

			FileParseResult result = parse(content);

			assertThat(result.columnNames()).containsExactly("vendor", "amount");
			assertThat(result.acceptedRowCount()).isEqualTo(1);
			assertThat(result.rows().get(0).text("vendor")).isEqualTo("Acme");
		}

		@Test
		@DisplayName("a CRLF file exported as UTF-16LE is decoded, not rejected as corrupt")
		void decodesUtf16WithABom() {
			String source = "vendor,amount\nAcme,1000.00\n";
			byte[] body = source.getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
			byte[] content = concat(new byte[] { (byte) 0xFF, (byte) 0xFE }, body);

			FileParseResult result = parse(content);

			assertThat(result.acceptedRowCount()).isEqualTo(1);
			assertThat(result.rows().get(0).text("amount")).isEqualTo("1000.00");
		}

		@Test
		@DisplayName("a byte sequence that is not valid text is refused as corrupt, not mangled")
		void refusesBytesThatAreNotText() {
			FileParseResult result = parse(new byte[] { (byte) 0xC3, (byte) 0x28, (byte) 0xA9 });

			assertThat(result.status()).isEqualTo(ParseStatus.FAILED);
			assertThat(result.findings()).extracting(ValidationFinding::reason)
				.containsExactly(RejectionReason.CORRUPT_FILE);
			assertThat(result.rows()).isEmpty();
		}

		@Test
		@DisplayName("non-ASCII vendor names survive the round trip unchanged")
		void preservesNonAsciiText() {
			FileParseResult result = parse("vendor,city\n\"Büro Nord\",\"München\"\n\"Café Ltd\",\"Zürich\"\n");

			assertThat(result.acceptedRowCount()).isEqualTo(2);
			assertThat(result.rows().get(0).text("vendor")).isEqualTo("Büro Nord");
			assertThat(result.rows().get(1).text("city")).isEqualTo("Zürich");
		}

	}

	@Nested
	@DisplayName("header resolution")
	class HeaderResolution {

		@Test
		@DisplayName("the header row is consumed, not treated as data")
		void consumesTheHeaderRow() {
			FileParseResult result = parse("vendor,amount\nAcme,1000.00\nTata,250.00\n");

			assertThat(result.headerRowNumber()).isEqualTo(1);
			assertThat(result.acceptedRowCount()).isEqualTo(2);
			assertThat(result.rows().get(0).coordinate().rowNumber()).isEqualTo(2L);
		}

		@Test
		@DisplayName("a preamble above the header is skipped and reported rather than parsed as data")
		void reportsPreambleRowsAboveTheHeader() {
			FileParseResult result = parse("Generated by ERP export\n2024-06-30\nvendor,amount\nAcme,1000.00\n");

			assertThat(result.headerRowNumber()).isEqualTo(3);
			assertThat(result.acceptedRowCount()).isEqualTo(1);
			assertThat(result.skippedRows()).isEqualTo(2);
			assertThat(result.findings()).extracting(ValidationFinding::reason)
				.contains(RejectionReason.MISSING_HEADER);
		}

		@Test
		@DisplayName("a headerless numeric export keeps its first row instead of promoting it to a header")
		void doesNotPromoteADataRowToAHeader() {
			FileParseResult result = parse("INV-1,2024-01-31,100.50\nINV-2,2024-01-32,200.75\n");

			assertThat(result.headerRowNumber()).isEqualTo(-1);
			assertThat(result.acceptedRowCount()).isEqualTo(2);
			assertThat(result.columnNames()).containsExactly("column_1", "column_2", "column_3");
			assertThat(result.rows().get(0).text("column_1")).isEqualTo("INV-1");
			assertThat(result.rows().get(0).coordinate().rowNumber()).isEqualTo(1L);
		}

		@Test
		@DisplayName("HeaderMode.NONE reads every record as data, header included")
		void readsEveryRecordWhenHeaderDetectionIsDisabled() {
			FileParseResult result = parse("vendor,amount\nAcme,1000.00\n",
					CsvParseOptions.defaults().withHeaderMode(HeaderMode.NONE));

			assertThat(result.headerRowNumber()).isEqualTo(-1);
			assertThat(result.acceptedRowCount()).isEqualTo(2);
			assertThat(result.rows().get(0).text("column_1")).isEqualTo("vendor");
		}

		@Test
		@DisplayName("a repeated header name is made unique so it stays addressable")
		void makesDuplicateHeaderNamesUnique() {
			FileParseResult result = parse("amount,amount,currency\n1.00,2.00,INR\n");

			assertThat(result.columnNames()).containsExactly("amount", "amount_2", "currency");
			assertThat(result.rows().get(0).text("amount_2")).isEqualTo("2.00");
		}

	}

	@Nested
	@DisplayName("row-level isolation")
	class RowLevelIsolation {

		@Test
		@DisplayName("one short row is refused and the well-formed rows around it are still read")
		void aRaggedRowDoesNotAbortTheFile() {
			FileParseResult result = parse("vendor,amount,currency\n"
					+ "Acme,1000.00,INR\n"
					+ "Broken,500.00\n"
					+ "Tata,250.00,USD\n");

			assertThat(result.status()).isEqualTo(ParseStatus.SUCCESS);
			assertThat(result.acceptedRowCount()).isEqualTo(2);
			assertThat(result.rejectedRowCount()).isEqualTo(1);

			RejectedRow rejected = result.rejectedRows().get(0);
			assertThat(rejected.reason()).isEqualTo(RejectionReason.FIELD_COUNT_MISMATCH);
			assertThat(rejected.coordinate().rowNumber()).isEqualTo(3L);
			assertThat(rejected.detail()).contains("2").contains("3");

			// The row after the bad one was still read: the failure did not truncate.
			assertThat(result.rows().get(1).text("vendor")).isEqualTo("Tata");
		}

		@Test
		@DisplayName("accepted, rejected and skipped account for every record in the file")
		void theRowAccountingIsTotal() {
			// WHY the third record is "Broken" and not "Broken,500": the header declares
			// two columns, so a two-field record is well-formed and would be accepted.
			// The ragged row this case is about has to actually be short.
			FileParseResult result = parse("vendor,amount\n"
					+ "Acme,1000.00\n"
					+ "\n"
					+ "Broken\n"
					+ "Tata,250.00\n");

			assertThat(result.acceptedRowCount()).isEqualTo(2);
			assertThat(result.rejectedRowCount()).isEqualTo(1);
			assertThat(result.skippedRows()).isEqualTo(1);
			assertThat(result.totalRowCount()).isEqualTo(4);
		}

		@Test
		@DisplayName("a blank row is skipped and counted, not silently ignored")
		void countsBlankRows() {
			FileParseResult result = parse("vendor,amount\nAcme,1000.00\n   \nTata,250.00\n");

			assertThat(result.acceptedRowCount()).isEqualTo(2);
			assertThat(result.skippedRows()).isEqualTo(1);
			assertThat(result.findings()).extracting(ValidationFinding::reason).contains(RejectionReason.BLANK_ROW);
		}

		@Test
		@DisplayName("a cell longer than the limit refuses its row and names the column")
		void refusesAnOverlongCell() {
			String longNarration = "x".repeat(200);
			FileParseResult result = parse("narration,amount\n\"" + longNarration + "\",1000.00\nAcme,10.00\n",
					CsvParseOptions.defaults(), withMaxCellTextLength(64));

			assertThat(result.acceptedRowCount()).isEqualTo(1);
			assertThat(result.rejectedRowCount()).isEqualTo(1);
			assertThat(result.rejectedRows().get(0).reason()).isEqualTo(RejectionReason.VALUE_TOO_LONG);
			assertThat(result.rejectedRows().get(0).columnName()).isEqualTo("narration");
		}

		@Test
		@DisplayName("the configured row limit stops the read and reports it as a partial parse")
		void stopsAtTheConfiguredRowLimit() {
			FileParseResult result = new CsvFileParser(CsvParseOptions.defaults())
				.parse(ParseRequest.of(new java.io.ByteArrayInputStream(
						"vendor,amount\nAcme,1.00\nTata,2.00\nInfo,3.00\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
						FILE_ID, FILE_NAME, "text/csv", AS_OF, withMaxRows(2)));

			assertThat(result.status()).isEqualTo(ParseStatus.PARTIAL);
			assertThat(result.acceptedRowCount()).isEqualTo(2);
			assertThat(result.findings()).extracting(ValidationFinding::reason)
				.contains(RejectionReason.MAX_ROWS_EXCEEDED);
		}

		@Test
		@DisplayName("an unterminated quoted field keeps the rows read so far and reports the file as partial")
		void reportsALexicalFaultAsPartialRatherThanFailingTheFile() {
			FileParseResult result = parse("vendor,amount\nAcme,1000.00\n\"Broken,500.00\nTata,250.00\n");

			assertThat(result.status()).isEqualTo(ParseStatus.PARTIAL);
			assertThat(result.acceptedRowCount()).isGreaterThanOrEqualTo(1);
			assertThat(result.findings()).extracting(ValidationFinding::reason)
				.contains(RejectionReason.TRUNCATED_RECORD);
			assertThat(result.failureReason()).isNotBlank();
		}

	}

	@Nested
	@DisplayName("file-level refusals")
	class FileLevelRefusals {

		@Test
		@DisplayName("an empty file is refused with a stated reason")
		void refusesAnEmptyFile() {
			FileParseResult result = parse("");

			assertThat(result.status()).isEqualTo(ParseStatus.FAILED);
			assertThat(result.findings()).extracting(ValidationFinding::reason)
				.containsExactly(RejectionReason.EMPTY_FILE);
		}

		@Test
		@DisplayName("a file of whitespace and newlines is refused as empty rather than read as no rows")
		void refusesAWhitespaceOnlyFile() {
			FileParseResult result = parse("\n\n   \n");

			assertThat(result.status()).isEqualTo(ParseStatus.FAILED);
			assertThat(result.findings()).extracting(ValidationFinding::reason)
				.containsExactly(RejectionReason.EMPTY_FILE);
		}

		@Test
		@DisplayName("a file over the size ceiling is refused before it is parsed")
		void refusesAFileOverTheSizeCeiling() {
			FileParseResult result = new CsvFileParser()
				.parse(ParseRequest.of(new java.io.ByteArrayInputStream("vendor,amount\nAcme,1.00\n"
						.getBytes(java.nio.charset.StandardCharsets.UTF_8)), FILE_ID, FILE_NAME, "text/csv", AS_OF,
						withMaxBytes(8L)));

			assertThat(result.status()).isEqualTo(ParseStatus.FAILED);
			assertThat(result.findings()).extracting(ValidationFinding::reason)
				.containsExactly(RejectionReason.SIZE_LIMIT_EXCEEDED);
		}

		@Test
		@DisplayName("a header with no data rows reports empty, not success")
		void reportsAHeaderOnlyFileAsEmpty() {
			FileParseResult result = parse("vendor,amount,currency\n");

			assertThat(result.status()).isEqualTo(ParseStatus.EMPTY);
			assertThat(result.acceptedRowCount()).isZero();
		}

	}

	@Nested
	@DisplayName("typed cells and security interplay")
	class TypedCellsAndSecurity {

		@Test
		@DisplayName("delimited cells arrive untyped, because a delimited file carries no type information")
		void delimitedCellsCarryNoInferredType() {
			FileParseResult result = parse("amount,when\n1000.00,2024-01-31\n");

			ParsedRow row = result.rows().get(0);
			assertThat(row.typeOf("amount")).isEqualTo(FieldType.STRING);
			assertThat(row.text("amount")).isEqualTo("1000.00");
		}

		@Test
		@DisplayName("a formula payload is preserved by the parser and neutralised by the validation gate")
		void formulaPayloadIsNeutralisedAfterParsing() {
			String payload = "=cmd|'/c calc'!A0";
			FileParseResult parsed = parse("narration,amount\n\"" + payload + "\",1000.00\nAcme,10.00\n");

			// The parser is a reader, not a filter: it must not destroy evidence.
			assertThat(parsed.rows().get(0).text("narration")).isEqualTo(payload);

			ValidationResult validated = new FileValidationService()
				.validateRows(parsed, IngestionSchema.empty(), AS_OF);

			assertThat(validated.acceptedRows()).hasSize(2);
			assertThat(validated.acceptedRows().get(0).text("narration"))
				.isEqualTo("'" + payload);
			assertThat(validated.findings()).extracting(ValidationFinding::reason)
				.contains(RejectionReason.FORMULA_INJECTION_NEUTRALISED);
		}

		@Test
		@DisplayName("a signed amount is left alone, because it is a value and not a formula trigger")
		void leavesSignedAmountsUntouched() {
			FileParseResult parsed = parse("amount,currency\n-1000.00,INR\n+4.50,INR\n");

			ValidationResult validated = new FileValidationService()
				.validateRows(parsed, IngestionSchema.of(List.of(ColumnSchema.required("amount", ColumnType.AMOUNT),
						ColumnSchema.required("currency", ColumnType.CURRENCY))), AS_OF);

			assertThat(validated.acceptedRows()).hasSize(2);
			assertThat(validated.acceptedRows().get(0).text("amount")).isEqualTo("-1000.00");
			assertThat(validated.acceptedRows().get(1).text("amount")).isEqualTo("+4.50");
			assertThat(validated.findings()).extracting(ValidationFinding::reason)
				.doesNotContain(RejectionReason.FORMULA_INJECTION_NEUTRALISED);
		}

		@Test
		@DisplayName("every row names the file it came from, so a reported figure can be traced back")
		void everyRowCarriesItsSourceCoordinates() {
			FileParseResult result = parse("vendor,amount\nAcme,1000.00\nTata,250.00\n");

			assertThat(result.rows()).allSatisfy(row -> {
				assertThat(row.coordinate().sourceFileId()).isEqualTo(FILE_ID);
				assertThat(row.coordinate().sourceFileName()).isEqualTo(FILE_NAME);
				assertThat(row.coordinate().rowNumber()).isGreaterThanOrEqualTo(1L);
				assertThat(row.coordinate().sheetName()).isEmpty();
			});
		}

		@Test
		@DisplayName("the parse outcome distinguishes a clean read from a partial one")
		void reportsTheOutcomeAsASealedState() {
			ParseOutcome complete = ParseOutcome.of(parse("vendor,amount\nAcme,1000.00\n"));
			assertThat(complete).isInstanceOf(ParseOutcome.Succeeded.class);
			assertThat(complete.rows()).hasSize(1);
			assertThat(complete.refusal()).isEmpty();

			ParseOutcome refused = ParseOutcome.of(parse(""));
			assertThat(refused).isInstanceOf(ParseOutcome.Refused.class);
			assertThat(refused.rows()).isEmpty();
			assertThat(refused.refusal()).get()
				.extracting(rejection -> rejection.reason())
				.isEqualTo(RejectionReason.EMPTY_FILE);
		}

		@Test
		@DisplayName("the parser reports the type it supports")
		void reportsItsSupportedType() {
			assertThat(parser.supportedType()).isEqualTo(FileType.CSV);
		}

		@Test
		@DisplayName("a duplicate column name in the header still yields addressable rows")
		void duplicateColumnsRemainAddressable() {
			FileParseResult result = parse("amount,amount\n1.00,2.00\n3.00,4.00\n");

			assertThat(result.acceptedRowCount()).isEqualTo(2);
			assertThat(result.rows().get(0).text("amount")).isEqualTo("1.00");
			assertThat(result.rows().get(0).text("amount_2")).isEqualTo("2.00");
		}

	}

	private static byte[] concat(byte[] first, byte[] second) {
		byte[] joined = new byte[first.length + second.length];
		System.arraycopy(first, 0, joined, 0, first.length);
		System.arraycopy(second, 0, joined, first.length, second.length);
		return joined;
	}

	private static IngestionLimits withMaxCellTextLength(int limit) {
		IngestionLimits limits = IngestionLimits.defaults();
		return new IngestionLimits(limits.maxFileBytes(), limits.maxRowsPerFile(), limits.maxColumns(),
				limits.maxSheets(), limits.maxUncompressedBytes(), limits.maxArchiveEntries(),
				limits.maxCompressionRatio(), limits.ratioCheckMinBytes(), limit, limits.headerScanWindow());
	}

	private static IngestionLimits withMaxRows(int rows) {
		IngestionLimits limits = IngestionLimits.defaults();
		return new IngestionLimits(limits.maxFileBytes(), rows, limits.maxColumns(), limits.maxSheets(),
				limits.maxUncompressedBytes(), limits.maxArchiveEntries(), limits.maxCompressionRatio(),
				limits.ratioCheckMinBytes(), limits.maxCellTextLength(), limits.headerScanWindow());
	}

	private static IngestionLimits withMaxBytes(long bytes) {
		IngestionLimits limits = IngestionLimits.defaults();
		return new IngestionLimits(bytes, limits.maxRowsPerFile(), limits.maxColumns(), limits.maxSheets(),
				limits.maxUncompressedBytes(), limits.maxArchiveEntries(), limits.maxCompressionRatio(),
				limits.ratioCheckMinBytes(), limits.maxCellTextLength(), limits.headerScanWindow());
	}

	}
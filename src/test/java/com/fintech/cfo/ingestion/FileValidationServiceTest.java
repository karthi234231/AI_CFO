package com.fintech.cfo.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.fintech.cfo.ingestion.enums.ColumnType;
import com.fintech.cfo.ingestion.enums.FileSecurityStatus;
import com.fintech.cfo.ingestion.enums.FileType;
import com.fintech.cfo.ingestion.enums.IngestionErrorType;
import com.fintech.cfo.ingestion.enums.RejectionReason;
import com.fintech.cfo.ingestion.enums.ValidationSeverity;
import com.fintech.cfo.ingestion.model.ColumnSchema;
import com.fintech.cfo.ingestion.model.FileParseResult;
import com.fintech.cfo.ingestion.model.FileSecurityResult;
import com.fintech.cfo.ingestion.model.IngestionLimits;
import com.fintech.cfo.ingestion.model.IngestionSchema;
import com.fintech.cfo.ingestion.model.ParsedRow;
import com.fintech.cfo.ingestion.model.RejectedRow;
import com.fintech.cfo.ingestion.model.SanitisedFilename;
import com.fintech.cfo.ingestion.model.ValidationFinding;
import com.fintech.cfo.ingestion.model.ValidationResult;
import com.fintech.cfo.ingestion.parser.CsvFileParser;
import com.fintech.cfo.ingestion.parser.ParseRequest;
import com.fintech.cfo.ingestion.security.FileUploadSecurityService;
import com.fintech.cfo.ingestion.security.FilenameSanitiser;
import com.fintech.cfo.ingestion.security.MalwareScanService;
import com.fintech.cfo.ingestion.service.FileValidationService;
import com.fintech.cfo.ingestion.validator.FormulaInjectionSanitiser;
import com.fintech.cfo.ingestion.validator.TypedValueParser;
import com.fintech.cfo.ingestion.validator.UploadFileValidator;

/**
 * Tests for the gates an upload must pass before its rows are trusted.
 *
 * <p>Two things are being checked here, and they are different: that the gates
 * <em>refuse</em> the right things, and that they refuse them for a stated reason.
 * A refusal without a reason is indistinguishable from a bug in production, so
 * every negative case asserts on the {@link RejectionReason} as well as on the
 * outcome.
 *
 * <p>The other half is that the gates refuse <em>only</em> the right things. A
 * validation layer that rejects an honest export gets switched off, and then the
 * refusals it was there for are gone too — so the positive cases are given equal
 * weight.
 */
class FileValidationServiceTest {

	private static final LocalDate AS_OF = LocalDate.of(2024, 6, 30);

	private static final String FILE_ID = "5d3c1a20-9f88-4c71-b3d2-6a7f8e9d0c11";

	private static final String FILE_NAME = "ledger.csv";

	// WHY referenced without a `this.` prefix from the @Nested classes below: `this`
	// inside a nested class is the *nested* instance, and these fields live on the
	// enclosing instance. The simple name still resolves to the outer field, so the
	// nested classes share one service exactly as a top-level class would.
	private final FileValidationService service = new FileValidationService();

	private FileParseResult parse(String content) {
		return new CsvFileParser().parse(ParseRequest.of(
				new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), FILE_ID, FILE_NAME, "text/csv",
				AS_OF));
	}

	private static IngestionSchema ledgerSchema() {
		return IngestionSchema.of(List.of(ColumnSchema.required("invoice_no", ColumnType.TEXT),
				ColumnSchema.required("txn_date", ColumnType.DATE),
				ColumnSchema.required("amount", ColumnType.AMOUNT),
				ColumnSchema.required("currency", ColumnType.CURRENCY),
				ColumnSchema.optional("narration", ColumnType.TEXT)));
	}

	@Nested
	@DisplayName("the file gate")
	class TheFileGate {

		@Test
		@DisplayName("an honest CSV passes")
		void acceptsAnHonestCsv() {
			FileSecurityResult result = service.validateFile(csvBytes("invoice_no,amount\nINV-1,10.00\n"),
					FILE_NAME, "text/csv");

			assertThat(result.allowsParsing()).isTrue();
			assertThat(result.status()).isEqualTo(FileSecurityStatus.PASSED);
			assertThat(result.findings()).isEmpty();
			assertThat(result.declaredFileType().code()).isEqualTo("csv");
		}

		@Test
		@DisplayName("an empty upload is refused as empty")
		void refusesAnEmptyUpload() {
			FileSecurityResult result = service.validateFile(new byte[0], FILE_NAME, "text/csv");

			// WHY read the reasons off the findings rather than a dedicated accessor:
			// FileSecurityResult synthesises exactly one finding per rejection (see
			// FileSecurityResult.rejected), so `findings` IS the reason list, and
			// asserting on it additionally proves a rejection never travels without the
			// finding that explains it.
			assertThat(result.allowsParsing()).isFalse();
			assertThat(result.findings()).extracting(ValidationFinding::reason)
				.containsExactly(RejectionReason.EMPTY_FILE);
		}

		@Test
		@DisplayName("a file over the size ceiling is refused and the limit is stated")
		void refusesAFileOverTheSizeCeiling() {
			byte[] content = csvBytes("invoice_no,amount\nINV-1,10.00\n");
			IngestionLimits limits = new IngestionLimits(4L, 1000, 100, 8, 1_000_000L, 100, 100L, 4096, 4096, 10);

			FileSecurityResult result = service.validateFile(content, FILE_NAME, "text/csv", limits);

			assertThat(result.allowsParsing()).isFalse();
			assertThat(result.findings()).extracting(ValidationFinding::reason).containsExactly(RejectionReason.SIZE_LIMIT_EXCEEDED);
			assertThat(result.findings().get(0).message()).contains("4");
		}

		@Test
		@DisplayName("a ZIP container named .csv is refused: the name lies about the bytes")
		void refusesAZipNamedAsCsv() {
			FileSecurityResult result = service.validateFile(zipMagic(), FILE_NAME, "text/csv");

			assertThat(result.allowsParsing()).isFalse();
			assertThat(result.findings()).extracting(ValidationFinding::reason).containsExactly(RejectionReason.EXTENSION_CONTENT_TYPE_MISMATCH);
		}

		@Test
		@DisplayName("plain text named .xlsx is refused, because a spreadsheet is not text")
		void refusesTextNamedAsXlsx() {
			FileSecurityResult result = service.validateFile(csvBytes("invoice_no,amount\nINV-1,10.00\n"),
					"ledger.xlsx", FileType.XLSX.canonicalContentType());

			assertThat(result.allowsParsing()).isFalse();
			assertThat(result.findings()).extracting(ValidationFinding::reason).containsExactly(RejectionReason.EXTENSION_CONTENT_TYPE_MISMATCH);
		}

		@Test
		@DisplayName("an extension the milestone refuses is reported as unsupported, with a way forward")
		void refusesAnUnsupportedFormat() {
			FileSecurityResult result = service.validateFile("%PDF-1.7 content".getBytes(StandardCharsets.UTF_8),
					"statement.pdf", "application/pdf");

			assertThat(result.allowsParsing()).isFalse();
			assertThat(result.findings()).extracting(ValidationFinding::reason).containsExactly(RejectionReason.UNSUPPORTED_FILE_TYPE);
			assertThat(result.findings().get(0).message()).contains("CSV or XLSX");
		}

		@Test
		@DisplayName("a browser's application/octet-stream is accepted as unspecified rather than treated as a lie")
		void acceptsAGenericContentType() {
			FileSecurityResult result = service.validateFile(csvBytes("invoice_no,amount\nINV-1,10.00\n"),
					FILE_NAME, "application/octet-stream");

			assertThat(result.allowsParsing()).isTrue();
		}

		@Test
		@DisplayName("binary content that is not a known container is refused as unreadable")
		void refusesUnrecognisableBinaryContent() {
			byte[] content = new byte[512];
			for (int index = 0; index < content.length; index++) {
				content[index] = (byte) (index % 7);
			}

			FileSecurityResult result = service.validateFile(content, FILE_NAME, "text/csv");

			assertThat(result.allowsParsing()).isFalse();
			assertThat(result.findings()).extracting(ValidationFinding::reason).contains(RejectionReason.CONTENT_TYPE_MISMATCH);
		}

	}

	@Nested
	@DisplayName("the filename and content screen")
	class TheFilenameAndContentScreen {

		private final FileUploadSecurityService screen = new FileUploadSecurityService();

		@Test
		@DisplayName("a traversal filename is refused outright rather than quietly normalised")
		void refusesATraversalFilename() {
			FileSecurityResult result = this.screen.screen(csvBytes("a,b\n1,2\n"), "../../etc/passwd.csv", "text/csv");

			assertThat(result.allowsParsing()).isFalse();
			assertThat(result.findings()).extracting(ValidationFinding::reason).containsExactly(RejectionReason.PATH_TRAVERSAL_ATTEMPT);
		}

		@Test
		@DisplayName("a Windows-style traversal is refused too")
		void refusesAWindowsTraversalFilename() {
			FileSecurityResult result = this.screen.screen(csvBytes("a,b\n1,2\n"), "..\\..\\secrets.csv", "text/csv");

			assertThat(result.allowsParsing()).isFalse();
			assertThat(result.findings()).extracting(ValidationFinding::reason).containsExactly(RejectionReason.PATH_TRAVERSAL_ATTEMPT);
		}

		@Test
		@DisplayName("an absolute path is refused")
		void refusesAnAbsolutePath() {
			FileSecurityResult result = this.screen.screen(csvBytes("a,b\n1,2\n"), "/var/data/ledger.csv", "text/csv");

			assertThat(result.allowsParsing()).isFalse();
			assertThat(result.findings()).extracting(ValidationFinding::reason).containsExactly(RejectionReason.PATH_TRAVERSAL_ATTEMPT);
		}

		@Test
		@DisplayName("a filename carrying a NUL byte is refused")
		void refusesANulByteInTheFilename() {
			FileSecurityResult result = this.screen.screen(csvBytes("a,b\n1,2\n"), "ledger\u0000.csv", "text/csv");

			assertThat(result.allowsParsing()).isFalse();
			assertThat(result.findings()).extracting(ValidationFinding::reason).containsExactly(RejectionReason.PATH_TRAVERSAL_ATTEMPT);
		}

		@Test
		@DisplayName("a Windows executable named .csv is refused")
		void refusesAnExecutableNamedAsCsv() {
			// WHY the cast: 0x90 does not fit in a signed byte literal, and a PE header's
			// third byte is exactly 0x90 — the byte MalwareScanService looks for after "MZ".
			byte[] executable = new byte[] { 'M', 'Z', (byte) 0x90, 0x00, 0x03, 0x00, 0x00, 0x00 };

			FileSecurityResult result = this.screen.screen(executable, FILE_NAME, "text/csv");

			assertThat(result.allowsParsing()).isFalse();
			assertThat(result.findings()).extracting(ValidationFinding::reason).containsExactly(RejectionReason.EXECUTABLE_CONTENT);
		}

		@Test
		@DisplayName("text smuggling NUL bytes is refused")
		void refusesNulSmuggling() {
			byte[] smuggling = new byte[] { 'a', ',', 'b', '\n', 0x00, 0x00, 0x00, 0x00, '\n' };

			FileSecurityResult result = this.screen.screen(smuggling, FILE_NAME, "text/csv");

			assertThat(result.allowsParsing()).isFalse();
			assertThat(result.findings()).extracting(ValidationFinding::reason).containsExactly(RejectionReason.EXECUTABLE_CONTENT);
		}

		@Test
		@DisplayName("a name with spaces is normalised, and the normalisation is reported as a warning")
		void normalisesAndReportsUnsafeNames() {
			FileSecurityResult result = this.screen.screen(csvBytes("a,b\n1,2\n"), "My Ledger (June).csv", "text/csv");

			assertThat(result.allowsParsing()).isTrue();
			// WHY "My_Ledger_June_.csv" and not "My_Ledger_June.csv": FilenameSanitiser
			// replaces every non-allow-listed character with '_' and then collapses only
			// *runs* of separators, so the '_' standing in for ')' survives while the
			// space it follows does not. That lossy-but-deterministic rebuild is the
			// documented behaviour; the important property is that the result is a
			// leaf name, which producesOnlyLeafNames asserts directly.
			assertThat(result.filename()).get()
				.extracting(SanitisedFilename::displayName)
				.isEqualTo("My_Ledger_June_.csv");
			assertThat(result.findings()).extracting(ValidationFinding::reason)
				.contains(RejectionReason.UNSAFE_FILENAME);
			assertThat(result.findings()).allSatisfy(finding -> assertThat(finding.severity())
				.isNotEqualTo(ValidationSeverity.ERROR));
		}

		@Test
		@DisplayName("a sanitised name is always a leaf name with no separator and no dot prefix")
		void producesOnlyLeafNames() {
			FilenameSanitiser sanitiser = new FilenameSanitiser();

			List<String> hostile = List.of("../../etc/passwd.csv", "..\\..\\config.csv", "  ..  /x.csv",
					"\u202Egnp.csv", "con.csv", ".hidden.csv", "ledger\u0000.csv", "a".repeat(300) + ".csv");

			for (String name : hostile) {
				SanitisedFilename sanitised = sanitiser.sanitise(name);
				assertThat(sanitised.displayName())
					.as("leaf name for %s", name)
					.doesNotContain("/")
					.doesNotContain("\\")
					.doesNotContain("\u0000")
					.doesNotStartWith(".")
					.doesNotContain("..");
				assertThat(sanitised.displayName()).isEqualTo(sanitised.displayName().trim());
			}
		}

		@Test
		@DisplayName("the content screen names a signature and never quotes the bytes")
		void reportsTheSignatureWithoutTheContent() {
			// WHY the cast: 0x90 does not fit in a signed byte literal (see above).
			MalwareScanService.ScanResult scan = new MalwareScanService()
				.scan(new byte[] { 'M', 'Z', (byte) 0x90, 0x00, 0x00, 0x00 });

			assertThat(scan.clean()).isFalse();
			assertThat(scan.signature()).isEqualTo("pe-executable");
			assertThat(scan.detail()).doesNotContain("MZ");
		}

		@Test
		@DisplayName("honest content is clean")
		void reportsHonestContentAsClean() {
			MalwareScanService.ScanResult scan = new MalwareScanService().scan(csvBytes("a,b\n1,2\n"));

			assertThat(scan.clean()).isTrue();
			assertThat(scan.signature()).isEqualTo("none");
		}

		@Test
		@DisplayName("the type gate reports a mismatch before the content is sniffed as a format")
		void refusesAMismatchBetweenExtensionAndContentType() {
			UploadFileValidator validator = new UploadFileValidator();

			// WHY the spreadsheet MIME type rather than a legacy type: FileType only
			// resolves content types this milestone can route on, and anything it does
			// not recognise is deliberately treated as "unspecified" rather than as a
			// lie (see acceptsAGenericContentType). The xlsx MIME against a .csv name
			// is a disagreement the gate is able to see, which is what this case is
			// about: the mismatch is reported from the name and the declared type, and
			// the bytes are never sniffed to try to reconcile it.
			FileSecurityResult result = validator.validate(csvBytes("a,b\n1,2\n"),
					new SanitisedFilename("ledger.csv", "csv"), FileType.XLSX.canonicalContentType(),
					IngestionLimits.defaults());

			assertThat(result.allowsParsing()).isFalse();
			assertThat(result.findings()).extracting(ValidationFinding::reason)
				.containsExactly(RejectionReason.EXTENSION_CONTENT_TYPE_MISMATCH);
		}

	}

	@Nested
	@DisplayName("the schema gate")
	class TheSchemaGate {

		@Test
		@DisplayName("an honest header against an honest schema reports nothing")
		void acceptsAMatchingHeader() {
			FileParseResult parsed = parse("invoice_no,txn_date,amount,currency\nINV-1,2024-01-31,10.00,INR\n");

			ValidationResult result = service.validateSchema(parsed, ledgerSchema());

			assertThat(result.hasErrors()).isFalse();
			assertThat(result.findings()).isEmpty();
		}

		@Test
		@DisplayName("a missing required column is a file-level refusal, not a per-row complaint")
		void refusesAMissingRequiredColumn() {
			FileParseResult parsed = parse("invoice_no,amount\nINV-1,10.00\n");

			ValidationResult result = service.validateSchema(parsed, ledgerSchema());

			assertThat(result.rejectsFile()).isTrue();
			assertThat(result.findings()).extracting(ValidationFinding::reason)
				.contains(RejectionReason.MISSING_REQUIRED_COLUMN);
			assertThat(result.findings()).allSatisfy(finding -> {
				assertThat(finding.isFileLevel()).isTrue();
				assertThat(finding.errorType()).isEqualTo(IngestionErrorType.SCHEMA);
			});
		}

		@Test
		@DisplayName("an empty schema means nothing was declared, so nothing is invented to complain about")
		void staysSilentForAnEmptySchema() {
			FileParseResult parsed = parse("whatever,columns\n1,2\n");

			ValidationResult result = service.validateSchema(parsed, IngestionSchema.empty());

			assertThat(result.findings()).isEmpty();
		}

		@Test
		@DisplayName("a repeated column name is reported as a schema problem")
		void reportsADuplicateColumn() {
			FileParseResult parsed = parse("invoice_no,invoice_no\nINV-1,INV-2\n");

			ValidationResult result = service.validateSchema(parsed, IngestionSchema.empty());

			// HeaderDetector makes the duplicate addressable, and the schema gate is
			// what tells the analyst the export is wrong.
			assertThat(result.findings()).extracting(ValidationFinding::reason)
				.contains(RejectionReason.DUPLICATE_COLUMN);
		}

	}

	@Nested
	@DisplayName("the row gate")
	class TheRowGate {

		@Test
		@DisplayName("well-formed rows are accepted and sanitised")
		void acceptsWellFormedRows() {
			FileParseResult parsed = parse("invoice_no,txn_date,amount,currency,narration\n"
					+ "INV-1,2024-01-31,10.00,INR,first\n"
					+ "INV-2,01/02/2024,20.50,USD,second\n");

			ValidationResult result = service.validateRows(parsed, ledgerSchema(), AS_OF);

			assertThat(result.acceptedRows()).hasSize(2);
			assertThat(result.rejectedRows()).isEmpty();
			assertThat(result.hasErrors()).isFalse();
		}

		@Test
		@DisplayName("a blank required cell refuses its row and names the column")
		void refusesARowMissingARequiredValue() {
			FileParseResult parsed = parse("invoice_no,txn_date,amount,currency\n"
					+ "INV-1,2024-01-31,10.00,INR\n"
					+ ",2024-01-31,20.00,INR\n");

			ValidationResult result = service.validateRows(parsed, ledgerSchema(), AS_OF);

			assertThat(result.acceptedRows()).hasSize(1);
			// WHY assert the size of rejectedRows() rather than a count accessor: the
			// list is the only place a rejection exists, so sizing it proves there is
			// no count that could disagree with the rows actually refused.
			assertThat(result.rejectedRows()).hasSize(1);
			RejectedRow rejected = result.rejectedRows().get(0);
			assertThat(rejected.reason()).isEqualTo(RejectionReason.MISSING_REQUIRED_VALUE);
			assertThat(rejected.columnName()).isEqualTo("invoice_no");
			assertThat(rejected.coordinate().rowNumber()).isEqualTo(3L);
		}

		@Test
		@DisplayName("an unparseable date refuses its row rather than being coerced")
		void refusesAnUnparseableDate() {
			FileParseResult parsed = parse("invoice_no,txn_date,amount,currency\n"
					+ "INV-1,31/31/2024,10.00,INR\n");

			ValidationResult result = service.validateRows(parsed, ledgerSchema(), AS_OF);

			assertThat(result.acceptedRows()).isEmpty();
			assertThat(result.rejectedRowsWith(RejectionReason.INVALID_DATE)).hasSize(1);
			assertThat(result.rejectedRows().get(0).columnName()).isEqualTo("txn_date");
		}

		@Test
		@DisplayName("a date that does not exist is refused instead of rolling forward")
		void refusesANonExistentDate() {
			FileParseResult parsed = parse("invoice_no,txn_date,amount,currency\n"
					+ "INV-1,2024-02-30,10.00,INR\n");

			ValidationResult result = service.validateRows(parsed, ledgerSchema(), AS_OF);

			assertThat(result.rejectedRowsWith(RejectionReason.INVALID_DATE)).hasSize(1);
		}

		@Test
		@DisplayName("an amount with more fraction digits than storage allows is refused, not rounded")
		void refusesAnOverPreciseAmount() {
			FileParseResult parsed = parse("invoice_no,txn_date,amount,currency\n"
					+ "INV-1,2024-01-31,10.123456,INR\n");

			ValidationResult result = service.validateRows(parsed, ledgerSchema(), AS_OF);

			assertThat(result.rejectedRowsWith(RejectionReason.VALUE_OUT_OF_RANGE)).hasSize(1);
			assertThat(result.findings()).extracting(ValidationFinding::message)
				.allSatisfy(message -> assertThat(message.toString()).doesNotContain("10.12"));
		}

		@Test
		@DisplayName("an amount with a thousands separator is refused: the two readings are not recoverable")
		void refusesAGroupedAmount() {
			FileParseResult parsed = parse("invoice_no,txn_date,amount,currency\n"
					+ "INV-1,2024-01-31,\"1,234.56\",INR\n");

			ValidationResult result = service.validateRows(parsed, ledgerSchema(), AS_OF);

			assertThat(result.rejectedRowsWith(RejectionReason.INVALID_AMOUNT)).hasSize(1);
		}

		@Test
		@DisplayName("a currency that is not an ISO-4217 code is refused")
		void refusesAnInvalidCurrency() {
			FileParseResult parsed = parse("invoice_no,txn_date,amount,currency\n"
					+ "INV-1,2024-01-31,10.00,RUPEE\n");

			ValidationResult result = service.validateRows(parsed, ledgerSchema(), AS_OF);

			assertThat(result.rejectedRowsWith(RejectionReason.INVALID_CURRENCY)).hasSize(1);
			assertThat(result.rejectedRows().get(0).columnName()).isEqualTo("currency");
		}

		@Test
		@DisplayName("the same row twice in one file is refused, and the first occurrence wins")
		void refusesADuplicateRow() {
			FileParseResult parsed = parse("invoice_no,txn_date,amount,currency\n"
					+ "INV-1,2024-01-31,10.00,INR\n"
					+ "INV-1,2024-01-31,10.00,INR\n");

			ValidationResult result = service.validateRows(parsed, ledgerSchema(), AS_OF);

			assertThat(result.acceptedRows()).hasSize(1);
			assertThat(result.rejectedRowsWith(RejectionReason.DUPLICATE_ROW)).hasSize(1);
			assertThat(result.rejectedRows().get(0).coordinate().rowNumber()).isEqualTo(3L);
		}

		@Test
		@DisplayName("the same amount written two ways is still one duplicate")
		void refusesADuplicateWrittenDifferently() {
			FileParseResult parsed = parse("invoice_no,txn_date,amount,currency\n"
					+ "INV-1,2024-01-31,100.00,INR\n"
					+ "INV-1,31/01/2024,100,INR\n");

			ValidationResult result = service.validateRows(parsed, ledgerSchema(), AS_OF);

			assertThat(result.rejectedRowsWith(RejectionReason.DUPLICATE_ROW)).hasSize(1);
		}

		@Test
		@DisplayName("one bad row does not cost the good rows around it")
		void isolatesABadRow() {
			FileParseResult parsed = parse("invoice_no,txn_date,amount,currency\n"
					+ "INV-1,2024-01-31,10.00,INR\n"
					+ "INV-2,not-a-date,20.00,INR\n"
					+ "INV-3,2024-01-33,30.00,INR\n"
					+ "INV-4,2024-02-01,40.00,INR\n");

			ValidationResult result = service.validateRows(parsed, ledgerSchema(), AS_OF);

			assertThat(result.acceptedRows()).hasSize(2);
			assertThat(result.rejectedRows()).hasSize(2);
			assertThat(result.acceptedRows()).extracting(row -> row.text("invoice_no"))
				.containsExactly("INV-1", "INV-4");
		}

		@Test
		@DisplayName("every refusal names its row, so finance can go and look at it")
		void everyRejectionCarriesItsCoordinates() {
			FileParseResult parsed = parse("invoice_no,txn_date,amount,currency\n"
					+ "INV-1,2024-01-31,10.00,INR\n"
					+ "INV-2,bad-date,20.00,INR\n");

			ValidationResult result = service.validateRows(parsed, ledgerSchema(), AS_OF);

			assertThat(result.rejectedRows()).allSatisfy(row -> {
				assertThat(row.coordinate()).isNotNull();
				assertThat(row.coordinate().sourceFileId()).isEqualTo(FILE_ID);
				assertThat(row.coordinate().sourceFileName()).isEqualTo(FILE_NAME);
				assertThat(row.coordinate().rowNumber()).isPositive();
				assertThat(row.detail()).isNotBlank();
			});
		}

		@Test
		@DisplayName("a rejected row and its finding cannot drift apart: every refusal has a finding")
		void everyRejectionHasAFinding() {
			FileParseResult parsed = parse("invoice_no,txn_date,amount,currency\n"
					+ "INV-1,bad-date,10.00,INR\n"
					+ "INV-1,bad-date,10.00,INR\n");

			ValidationResult result = service.validateRows(parsed, ledgerSchema(), AS_OF);

			assertThat(result.rejectedRows()).hasSize(2);
			assertThat(result.findings()).hasSizeGreaterThanOrEqualTo(result.rejectedRows().size());
			assertThat(result.findings()).allSatisfy(finding -> assertThat(finding.reason()).isNotNull());
		}

		@Test
		@DisplayName("parser findings are carried into the validation result, not dropped")
		void carriesParserFindingsForward() {
			FileParseResult parsed = parse("invoice_no,amount\nINV-1,10.00\nshort\n");

			ValidationResult result = service.validateRows(parsed, IngestionSchema.empty(), AS_OF);

			assertThat(result.rejectedRowsWith(RejectionReason.FIELD_COUNT_MISMATCH)).hasSize(1);
			assertThat(result.findings()).extracting(ValidationFinding::reason)
				.contains(RejectionReason.FIELD_COUNT_MISMATCH);
		}

		@Test
		@DisplayName("the accepted row is the sanitised row, never the raw one")
		void returnsTheSanitisedRow() {
			FileParseResult parsed = parse("narration,amount\n\"=cmd|'/c calc'!A0\",10.00\n");

			ValidationResult result = service.validateRows(parsed, IngestionSchema.empty(), AS_OF);

			ParsedRow accepted = result.acceptedRows().get(0);
			assertThat(accepted.text("narration")).startsWith(FormulaInjectionSanitiser.ESCAPE + "=");
			assertThat(accepted.text("narration")).doesNotStartWith("=");
		}

	}

	@Nested
	@DisplayName("formula injection")
	class FormulaInjection {

		private FormulaInjectionSanitiser sanitiser = FormulaInjectionSanitiser.instance();

		@Test
		@DisplayName("every formula trigger is escaped with the prefix the spreadsheets understand")
		void escapesEveryFormulaTrigger() {
			List<String> payloads = List.of("=1+1", "@SUM(A1)", "+1+1", "-cmd|' /C calc'!A0", "=cmd|'/c calc'!A0",
					"\t=1+1", "\r=1+1", "\n=1+1");

			for (String payload : payloads) {
				FormulaInjectionSanitiser.Result result = this.sanitiser.sanitise(payload);

				assertThat(result.neutralised()).as("neutralised for %s", payload).isTrue();
				assertThat(result.text()).as("escaped for %s", payload)
					.startsWith(String.valueOf(FormulaInjectionSanitiser.ESCAPE));
			}
		}

		@Test
		@DisplayName("a plain signed amount is a value, not a payload, and is left exactly as it was")
		void leavesSignedAmountsAlone() {
			List<String> amounts = List.of("-1000.00", "+4.50", "-0.0001", "+12345678.9");

			for (String amount : amounts) {
				FormulaInjectionSanitiser.Result result = this.sanitiser.sanitise(amount);

				assertThat(result.neutralised()).as("untouched for %s", amount).isFalse();
				assertThat(result.text()).isEqualTo(amount);
			}
		}

		@Test
		@DisplayName("ordinary text is never altered")
		void leavesOrdinaryTextAlone() {
			List<String> texts = List.of("Widget, large", "1000.00", "INV-1001", "2024-01-31", "INR", "multi\nline",
					"50% discount", "a-b");

			for (String text : texts) {
				FormulaInjectionSanitiser.Result result = this.sanitiser.sanitise(text);

				assertThat(result.text()).as("unchanged for %s", text).isEqualTo(text);
				assertThat(result.neutralised()).isFalse();
			}
		}

		@Test
		@DisplayName("invisible bidi and format characters are removed, not escaped")
		void removesInvisibleCharacters() {
			FormulaInjectionSanitiser.Result result = this.sanitiser.sanitise("Acme\u202E Ltd\u200B");

			assertThat(result.text()).isEqualTo("Acme Ltd");
			assertThat(result.removedControlCharacters()).isTrue();
		}

		@Test
		@DisplayName("an embedded newline is preserved, because a quoted field may legitimately hold one")
		void preservesEmbeddedNewlines() {
			FormulaInjectionSanitiser.Result result = this.sanitiser.sanitise("line one\nline two");

			assertThat(result.text()).isEqualTo("line one\nline two");
			assertThat(result.neutralised()).isFalse();
		}

		@Test
		@DisplayName("escaping is reversible, so an analyst-facing export can recover the value")
		void escapingCanBeUndone() {
			String payload = "=cmd|'/c calc'!A0";

			String escaped = this.sanitiser.sanitise(payload).text();

			assertThat(FormulaInjectionSanitiser.unescape(escaped)).isEqualTo(payload);
		}

		@Test
		@DisplayName("unescape leaves a genuinely escaped apostrophe-prefixed value alone")
		void unescapeDoesNotStripLegitimateApostrophes() {
			assertThat(FormulaInjectionSanitiser.unescape("'quoted name")).isEqualTo("'quoted name");
		}

		@Test
		@DisplayName("the payload test and the sanitiser agree on what counts as a payload")
		void payloadDetectionAgreesWithTheSanitiser() {
			List<String> values = List.of("=1+1", "@x", "-1.5", "+1.5", "-1+1", "plain", "1,234.56");

			for (String value : values) {
				assertThat(this.sanitiser.sanitise(value).neutralised())
					.as("payload test agrees for %s", value)
					.isEqualTo(FormulaInjectionSanitiser.isInjectionPayload(value));
			}
		}

		@Test
		@DisplayName("a neutralised cell is recorded as a warning, and its row is still accepted")
		void recordsTheNeutralisationAsAWarning() {
			FileParseResult parsed = parse("narration,amount\n\"=1+1\",10.00\n");

			ValidationResult result = service.validateRows(parsed, IngestionSchema.empty(), AS_OF);

			assertThat(result.acceptedRows()).hasSize(1);
			assertThat(result.warnings()).extracting(ValidationFinding::reason)
				.containsExactly(RejectionReason.FORMULA_INJECTION_NEUTRALISED);
			assertThat(result.hasErrors()).isFalse();
			assertThat(result.warnings()).extracting(ValidationFinding::errorType)
				.containsExactly(IngestionErrorType.FORMULA_INJECTION);
		}

	}

	@Nested
	@DisplayName("exact value parsing")
	class ExactValueParsing {

		@Test
		@DisplayName("amounts parse exactly, with no binary floating-point error")
		void parsesAmountsExactly() {
			BigDecimal parsed = TypedValueParser.amount("0.1");

			assertThat(parsed).isEqualByComparingTo("0.1");
			assertThat(parsed.toPlainString()).isEqualTo("0.1");
			assertThat(parsed.scale()).isEqualTo(1);
		}

		@Test
		@DisplayName("a negative amount keeps its sign and its scale")
		void parsesNegativeAmountsExactly() {
			assertThat(TypedValueParser.amount("-1234.5600").toPlainString()).isEqualTo("-1234.5600");
		}

		@Test
		@DisplayName("an amount beyond the storage precision is refused, naming the limit")
		void refusesAnAmountBeyondPrecision() {
			// A 22-digit amount cannot fit NUMERIC(20,4); assert through the reason rather
			// than the message wording.
			assertThat(reasonFor("1".repeat(20) + ".00")).isEqualTo(RejectionReason.VALUE_OUT_OF_RANGE);
			// WHY also assert the exception type: the refusal has to arrive as a typed
			// ValueFormatException carrying the reason, because that is the only thing
			// DataTypeValidator can turn into a finding. A raw NumberFormatException
			// would escape the per-row isolation and abort the file.
			assertThat(catchThrowableOfType(() -> TypedValueParser.amount("1".repeat(20) + ".00"),
					TypedValueParser.ValueFormatException.class))
				.isNotNull()
				.extracting(failure -> ((TypedValueParser.ValueFormatException) failure).reason())
				.isEqualTo(RejectionReason.VALUE_OUT_OF_RANGE);
		}

		@Test
		@DisplayName("an amount with too many fraction digits is refused rather than rounded")
		void refusesAnOverPreciseAmount() {
			assertThat(reasonFor("1.000001")).isEqualTo(RejectionReason.VALUE_OUT_OF_RANGE);
		}

		@Test
		@DisplayName("a grouped amount is refused because the two readings are not recoverable")
		void refusesAGroupedAmount() {
			assertThat(reasonFor("1,234.56")).isEqualTo(RejectionReason.INVALID_AMOUNT);
		}

		@Test
		@DisplayName("a currency symbol is refused rather than stripped")
		void refusesACurrencySymbol() {
			assertThat(reasonFor("₹100")).isEqualTo(RejectionReason.INVALID_AMOUNT);
		}

		@Test
		@DisplayName("an escaped value is not read back as a number")
		void refusesAnEscapedValue() {
			assertThat(reasonFor("'1234.56")).isEqualTo(RejectionReason.INVALID_FIELD_FORMAT);
		}

		@Test
		@DisplayName("only unambiguous date layouts are accepted")
		void acceptsOnlyUnambiguousDates() {
			assertThat(TypedValueParser.date("2024-01-31")).isEqualTo(LocalDate.of(2024, 1, 31));
			assertThat(TypedValueParser.date("31/01/2024")).isEqualTo(LocalDate.of(2024, 1, 31));
			assertThat(TypedValueParser.date("31-01-2024")).isEqualTo(LocalDate.of(2024, 1, 31));
		}

		@Test
		@DisplayName("a date that does not exist is refused rather than rolled forward")
		void refusesANonExistentDate() {
			assertThat(reasonForDate("2024-02-30")).isEqualTo(RejectionReason.INVALID_DATE);
			assertThat(reasonForDate("31/31/2024")).isEqualTo(RejectionReason.INVALID_DATE);
		}

		@Test
		@DisplayName("the as-of date decides whether a future date is plausible, and it is injected")
		void usesTheInjectedAsOfDate() {
			FileParseResult parsed = parse("invoice_no,txn_date,amount,currency\n"
					+ "INV-1,2099-01-01,10.00,INR\n");

			ValidationResult future = service.validateRows(parsed, ledgerSchema(),
					LocalDate.of(2024, 6, 30));
			ValidationResult later = service.validateRows(parsed, ledgerSchema(), LocalDate.of(2099, 6, 30));

			// WHY the row is accepted in both cases: DataQualityValidator treats a
			// forward-dated entry as a warning, not an error, because forward dating is
			// real accounting. What the injected as-of date controls is therefore the
			// warning, not the row's fate — and asserting the warning appears and then
			// disappears is a sharper proof that the cut-off is injected than asserting
			// a rejection would be. A verdict read from the clock would not vary.
			assertThat(future.acceptedRows()).hasSize(1);
			assertThat(future.warnings()).extracting(ValidationFinding::reason)
				.contains(RejectionReason.VALUE_OUT_OF_RANGE);
			assertThat(future.warnings()).extracting(ValidationFinding::message)
				.allSatisfy(message -> assertThat(message.toString()).contains("2024-06-30"));
			assertThat(future.hasErrors()).isFalse();

			assertThat(later.acceptedRows()).hasSize(1);
			assertThat(later.hasWarnings()).isFalse();
		}

		private static RejectionReason reasonFor(String value) {
			try {
				TypedValueParser.amount(value);
				throw new AssertionError("expected '" + value + "' to be refused");
			}
			catch (TypedValueParser.ValueFormatException ex) {
				return ex.reason();
			}
		}

		private static RejectionReason reasonForDate(String value) {
			try {
				TypedValueParser.date(value);
				throw new AssertionError("expected '" + value + "' to be refused");
			}
			catch (TypedValueParser.ValueFormatException ex) {
				return ex.reason();
			}
		}

	}

	/* ---- fixture helpers ---- */

	private static byte[] csvBytes(String content) {
		return content.getBytes(StandardCharsets.UTF_8);
	}

	private static byte[] zipMagic() {
		return new byte[] { 0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00 };
	}

}
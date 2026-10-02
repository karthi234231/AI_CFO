package com.fintech.cfo.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.fintech.cfo.ingestion.enums.FieldType;
import com.fintech.cfo.ingestion.enums.FileType;
import com.fintech.cfo.ingestion.enums.ParseStatus;
import com.fintech.cfo.ingestion.enums.RejectionReason;
import com.fintech.cfo.ingestion.model.FileParseResult;
import com.fintech.cfo.ingestion.model.IngestionLimits;
import com.fintech.cfo.ingestion.model.ParsedRow;
import com.fintech.cfo.ingestion.model.ValidationFinding;
import com.fintech.cfo.ingestion.parser.ArchiveGuard;
import com.fintech.cfo.ingestion.parser.ExcelFileParser;
import com.fintech.cfo.ingestion.parser.ExcelParseOptions;
import com.fintech.cfo.ingestion.parser.ParseRequest;

/**
 * Reader tests for the spreadsheet path.
 *
 * <p>Every workbook is built in memory with POI and serialised to bytes, so the
 * tests exercise the real container — the same zip {@link ArchiveGuard} walks —
 * rather than a fixture someone checked in once and never updated. Nothing is read
 * from disk and nothing is written.
 *
 * <p>The zip-bomb cases are the reason this suite builds its own archives: the
 * ceilings only fire at sizes no committed fixture should have, and a
 * decompression bomb is the one input an ingestion module genuinely must refuse.
 */
class ExcelFileParserTest {

	private static final LocalDate AS_OF = LocalDate.of(2024, 6, 30);

	private static final String FILE_ID = "9c2a3b41-77d2-4e1f-8a55-0d1e2f3a4b5c";

	private static final String FILE_NAME = "ledger-june.xlsx";

	private static final String CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

	// WHY referenced without a `this.` prefix from the @Nested class below: `this`
	// inside a nested class is the *nested* instance, and this field lives on the
	// enclosing instance. The simple name still resolves to the outer field.
	private final ExcelFileParser parser = new ExcelFileParser();

	private FileParseResult parse(byte[] workbook) {
		return parse(workbook, IngestionLimits.defaults());
	}

	private FileParseResult parse(byte[] workbook, IngestionLimits limits) {
		return new ExcelFileParser()
			.parse(ParseRequest.of(new ByteArrayInputStream(workbook), FILE_ID, FILE_NAME, CONTENT_TYPE, AS_OF,
					limits));
	}

	@Nested
	@DisplayName("reading a real workbook")
	class ReadingAWorkbook {

		@Test
		@DisplayName("a typed row is read with its cells' real types and its Excel row number")
		void readsTypedCellsWithExcelRowNumbers() {
			byte[] workbook = ledgerWorkbook();

			FileParseResult result = parse(workbook);

			assertThat(result.status()).isEqualTo(ParseStatus.SUCCESS);
			assertThat(result.acceptedRowCount()).isEqualTo(2);
			assertThat(result.sheetNames()).containsExactly("Ledger");

			ParsedRow first = result.rows().get(0);
			assertThat(first.coordinate().rowNumber()).isEqualTo(2L);
			assertThat(first.coordinate().sheetName()).isEqualTo("Ledger");
			assertThat(first.coordinate().sourceFileId()).isEqualTo(FILE_ID);
			assertThat(first.typeOf("txn_date")).isEqualTo(FieldType.DATE);
			assertThat(first.typeOf("amount")).isEqualTo(FieldType.NUMBER);
			assertThat(first.text("invoice_no")).isEqualTo("INV-1001");

			assertThat(result.rows().get(1).coordinate().rowNumber()).isEqualTo(3L);
			assertThat(result.rows().get(1).text("invoice_no")).isEqualTo("INV-1002");
		}

		@Test
		@DisplayName("a numeric cell becomes an exact decimal string, never a double artefact")
		void convertsNumbersToExactDecimals() {
			byte[] workbook = ledgerWorkbook();

			ParsedRow first = parse(workbook).rows().get(0);

			// 1234.5 as a double is 1234.4999999999998 in binary; the reader must not
			// hand that tail to the money layer.
			assertThat(first.text("amount")).isEqualTo("1234.5");
			assertThat(first.typeOf("amount")).isEqualTo(FieldType.NUMBER);
		}

		@Test
		@DisplayName("a date cell is read as a date, using the workbook's own date system")
		void readsDateCells() {
			byte[] workbook = ledgerWorkbook();

			ParsedRow first = parse(workbook).rows().get(0);

			assertThat(first.typeOf("txn_date")).isEqualTo(FieldType.DATE);
			assertThat(first.text("txn_date")).startsWith("2024-01-31");
		}

		@Test
		@DisplayName("the header row is consumed and each sheet is read independently")
		void readsEachSheetIndependently() {
			byte[] workbook = twoSheetWorkbook();

			FileParseResult result = parse(workbook);

			assertThat(result.sheetNames()).containsExactly("Ledger", "Summary");
			assertThat(result.sheetCount()).isEqualTo(2);
			assertThat(result.acceptedRowCount()).isEqualTo(3);
			assertThat(result.rowsBySheet().get("Ledger")).hasSize(2);
			assertThat(result.rowsBySheet().get("Summary")).hasSize(1);
			// Each sheet restarts its own row numbering at the header.
			assertThat(result.rowsBySheet().get("Summary").get(0).coordinate().rowNumber()).isEqualTo(2L);
		}

		@Test
		@DisplayName("a formula cell keeps its stored result and its expression, and is never evaluated here")
		void readsFormulaCellsWithoutEvaluating() {
			byte[] workbook = formulaWorkbook();

			ParsedRow row = parse(workbook).rows().get(0);

			assertThat(row.typeOf("total")).isEqualTo(FieldType.FORMULA);
			assertThat(row.text("total")).isEqualTo("2469.0");
			assertThat(row.rawText("total")).isEqualTo("SUM(B2:C2)");
		}

		@Test
		@DisplayName("a formula cell with no stored result refuses its row instead of inventing a zero")
		void refusesAFormulaWithNoCachedResult() {
			byte[] workbook = uncachedFormulaWorkbook();

			FileParseResult result = parse(workbook);

			assertThat(result.rejectedRowCount()).isEqualTo(1);
			assertThat(result.rejectedRows().get(0).reason())
				.isEqualTo(RejectionReason.FORMULA_WITHOUT_CACHED_RESULT);
			assertThat(result.rejectedRows().get(0).columnName()).isEqualTo("total");
			assertThat(result.acceptedRowCount()).isZero();
		}

		@Test
		@DisplayName("a styled but empty row is skipped and counted, not read as a row of blanks")
		void skipsBlankRows() {
			byte[] workbook = blankRowWorkbook();

			FileParseResult result = parse(workbook);

			assertThat(result.acceptedRowCount()).isEqualTo(1);
			assertThat(result.skippedRows()).isEqualTo(1);
		}

		@Test
		@DisplayName("a hidden sheet is not read, and the skip is reported")
		void skipsHiddenSheets() {
			byte[] workbook = hiddenSheetWorkbook();

			FileParseResult result = parse(workbook);

			assertThat(result.sheetNames()).containsExactly("Ledger");
			assertThat(result.findings()).extracting(ValidationFinding::reason).contains(RejectionReason.BLANK_ROW);
		}

		@Test
		@DisplayName("the configured row limit stops the sheet and reports the remainder unread")
		void stopsAtTheConfiguredRowLimit() {
			FileParseResult result = parse(ledgerWorkbook(), withMaxRows(1));

			assertThat(result.status()).isEqualTo(ParseStatus.PARTIAL);
			assertThat(result.acceptedRowCount()).isEqualTo(1);
			assertThat(result.findings()).extracting(ValidationFinding::reason)
				.contains(RejectionReason.MAX_ROWS_EXCEEDED);
		}

		@Test
		@DisplayName("the parser reports the type it supports")
		void reportsItsSupportedType() {
			assertThat(parser.supportedType()).isEqualTo(FileType.XLSX);
		}

		@Test
		@DisplayName("reading only the named sheet leaves the others unread")
		void canRestrictReadingToNamedSheets() {
			ExcelFileParser restricted = new ExcelFileParser(
					ExcelParseOptions.defaults().withIncludedSheets(java.util.Set.of("Summary")));

			FileParseResult result = restricted.parse(ParseRequest.of(
					new ByteArrayInputStream(twoSheetWorkbook()), FILE_ID, FILE_NAME, CONTENT_TYPE, AS_OF));

			assertThat(result.sheetNames()).containsExactly("Summary");
			assertThat(result.acceptedRowCount()).isEqualTo(1);
		}

	}

	@Nested
	@DisplayName("archive defences")
	class ArchiveDefences {

		@Test
		@DisplayName("a genuine workbook is recognised as a spreadsheet package and reaches POI")
		void acceptsAGenuineWorkbookPackage() {
			ArchiveGuard.ArchiveInspection inspection = ArchiveGuard.inspect(ledgerWorkbook(),
					IngestionLimits.defaults());

			assertThat(inspection.looksLikeSpreadsheetPackage()).isTrue();
			assertThat(inspection.looksLikeOtherOoxmlPackage()).isFalse();
			assertThat(inspection.entryCount()).isGreaterThan(0);
			assertThat(inspection.entryNames()).contains("[content_types].xml", "xl/workbook.xml");
		}

		@Test
		@DisplayName("a zip whose entries say 'word/' is refused as not a spreadsheet")
		void refusesAWordPackageNamedAsASpreadsheet() {
			byte[] wordDocument = zip(List.of("word/document.xml", "[Content_Types].xml"));

			FileParseResult result = parse(wordDocument);

			assertThat(result.status()).isEqualTo(ParseStatus.FAILED);
			assertThat(result.findings()).extracting(ValidationFinding::reason)
				.containsExactly(RejectionReason.CORRUPT_FILE);
			assertThat(result.findings().get(0).message()).contains("not a spreadsheet");
		}

		@Test
		@DisplayName("a zip bomb is refused by the ratio ceiling before POI sees it")
		void refusesAZipBomb() {
			IngestionLimits tightRatio = IngestionLimits.defaults();
			IngestionLimits limits = new IngestionLimits(tightRatio.maxFileBytes(), tightRatio.maxRowsPerFile(),
					tightRatio.maxColumns(), tightRatio.maxSheets(), tightRatio.maxUncompressedBytes(),
					tightRatio.maxArchiveEntries(), 5L, 64, tightRatio.maxCellTextLength(),
					tightRatio.headerScanWindow());

			FileParseResult result = parse(zipBomb(1_000_000), limits);

			assertThat(result.status()).isEqualTo(ParseStatus.FAILED);
			assertThat(result.findings()).extracting(ValidationFinding::reason)
				.containsExactly(RejectionReason.ZIP_BOMB_SUSPECTED);
		}

		@Test
		@DisplayName("a container that inflates past the ceiling is refused, naming only the limit")
		void refusesAContainerOverTheInflatedCeiling() {
			IngestionLimits limits = withMaxUncompressedBytes(1024L);

			FileParseResult result = parse(zipBomb(500_000), limits);

			assertThat(result.status()).isEqualTo(ParseStatus.FAILED);
			assertThat(result.findings()).extracting(ValidationFinding::reason)
				.containsExactly(RejectionReason.ARCHIVE_LIMIT_EXCEEDED);
			assertThat(result.findings().get(0).message()).contains("1024");
		}

		@Test
		@DisplayName("more entries than the ceiling allows is refused before the bytes are inflated")
		void refusesTooManyArchiveEntries() {
			FileParseResult result = parse(zip(List.of("[Content_Types].xml", "xl/workbook.xml", "xl/a", "xl/b")),
					withMaxArchiveEntries(2));

			assertThat(result.status()).isEqualTo(ParseStatus.FAILED);
			assertThat(result.findings()).extracting(ValidationFinding::reason)
				.containsExactly(RejectionReason.ARCHIVE_LIMIT_EXCEEDED);
		}

		@Test
		@DisplayName("content that is not a zip at all is refused as corrupt, not as a limit breach")
		void refusesContentThatIsNotAZip() {
			byte[] notAZip = "this is plain text, not a container".getBytes(java.nio.charset.StandardCharsets.UTF_8);

			FileParseResult result = parse(notAZip);

			assertThat(result.status()).isEqualTo(ParseStatus.FAILED);
			assertThat(result.findings()).extracting(ValidationFinding::reason)
				.containsExactly(RejectionReason.CORRUPT_FILE);
		}

		@Test
		@DisplayName("an empty upload is refused as empty rather than as a corrupt container")
		void refusesAnEmptyUpload() {
			FileParseResult result = parse(new byte[0]);

			assertThat(result.status()).isEqualTo(ParseStatus.FAILED);
			assertThat(result.findings()).extracting(ValidationFinding::reason)
				.containsExactly(RejectionReason.EMPTY_FILE);
		}

		@Test
		@DisplayName("a workbook whose sheets are all unreadable is refused with a stated reason")
		void refusesAWorkbookWithNoReadableSheet() {
			// WHY a hidden sheet rather than an empty one: "no readable sheet" is decided
			// by the sheet being skipped before it is read, and a hidden sheet is the
			// only such case — a sheet that is merely empty was still opened successfully
			// and is reported as empty, not unreadable (see reportsAnEmptySheetAsEmpty).
			byte[] workbook = allHiddenSheetWorkbook();

			FileParseResult result = parse(workbook);

			assertThat(result.status()).isEqualTo(ParseStatus.FAILED);
			assertThat(result.findings()).extracting(ValidationFinding::reason)
				.containsExactly(RejectionReason.NO_READABLE_COLUMNS);
		}

		@Test
		@DisplayName("a workbook with a readable but empty sheet is empty, not corrupt")
		void reportsAnEmptySheetAsEmpty() {
			// The counterpart to refusesAWorkbookWithNoReadableSheet: the sheet opened
			// and carried no data, which is a clean empty parse. Reporting it as a
			// refusal would make a blank export look like a broken upload.
			FileParseResult result = parse(emptySheetWorkbook());

			assertThat(result.status()).isEqualTo(ParseStatus.EMPTY);
			assertThat(result.acceptedRowCount()).isZero();
			assertThat(result.findings()).isEmpty();
		}

		@Test
		@DisplayName("the archive walk reports the inflated size it measured, not a declared size")
		void measuresTheInflatedSizeItself() {
			ArchiveGuard.ArchiveInspection inspection = ArchiveGuard.inspect(zipBomb(200_000),
					IngestionLimits.defaults());

			assertThat(inspection.entryNames()).contains("[content_types].xml");
			assertThat(inspection.uncompressedBytes()).isGreaterThan(200_000L);
		}

	}

	/* ---- workbook builders: every fixture is constructed here, never loaded ---- */

	private static byte[] ledgerWorkbook() {
		try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			CellStyle dateStyle = workbook.createCellStyle();
			dateStyle.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd"));

			Sheet sheet = workbook.createSheet("Ledger");
			header(sheet, "invoice_no", "txn_date", "amount", "currency");

			Row first = sheet.createRow(1);
			first.createCell(0).setCellValue("INV-1001");
			Cell date = first.createCell(1);
			date.setCellValue(LocalDateTime.of(2024, 1, 31, 0, 0));
			date.setCellStyle(dateStyle);
			first.createCell(2).setCellValue(1234.5d);
			first.createCell(3).setCellValue("INR");

			Row second = sheet.createRow(2);
			second.createCell(0).setCellValue("INV-1002");
			Cell secondDate = second.createCell(1);
			secondDate.setCellValue(LocalDateTime.of(2024, 2, 1, 0, 0));
			secondDate.setCellStyle(dateStyle);
			second.createCell(2).setCellValue(234.5d);
			second.createCell(3).setCellValue("INR");

			workbook.write(out);
			return out.toByteArray();
		}
		catch (IOException ex) {
			throw new IllegalStateException("could not build the test workbook", ex);
		}
	}

	private static byte[] twoSheetWorkbook() {
		try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			Sheet ledger = workbook.createSheet("Ledger");
			header(ledger, "invoice_no", "amount");
			ledger.createRow(1).createCell(0).setCellValue("INV-1001");
			ledger.createRow(1).createCell(1).setCellValue(100.0d);
			ledger.createRow(2).createCell(0).setCellValue("INV-1002");
			ledger.createRow(2).createCell(1).setCellValue(200.0d);

			Sheet summary = workbook.createSheet("Summary");
			header(summary, "invoice_no", "amount");
			summary.createRow(1).createCell(0).setCellValue("INV-1001");
			summary.createRow(1).createCell(1).setCellValue(300.0d);

			workbook.write(out);
			return out.toByteArray();
		}
		catch (IOException ex) {
			throw new IllegalStateException("could not build the test workbook", ex);
		}
	}

	/** The uploader's spreadsheet evaluated the formula, so a cached value exists. */
	private static byte[] formulaWorkbook() {
		try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			Sheet sheet = workbook.createSheet("Ledger");
			header(sheet, "invoice_no", "amount", "tax", "total");

			Row row = sheet.createRow(1);
			row.createCell(0).setCellValue("INV-1001");
			row.createCell(1).setCellValue(1234.5d);
			row.createCell(2).setCellValue(1234.5d);
			row.createCell(3).setCellFormula("SUM(B2:C2)");
			workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();

			workbook.write(out);
			return out.toByteArray();
		}
		catch (IOException ex) {
			throw new IllegalStateException("could not build the test workbook", ex);
		}
	}

	/** A formula nobody ever calculated: no {@code <v>} element exists to read. */
	private static byte[] uncachedFormulaWorkbook() {
		try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			Sheet sheet = workbook.createSheet("Ledger");
			header(sheet, "invoice_no", "total");

			Row row = sheet.createRow(1);
			row.createCell(0).setCellValue("INV-1001");
			row.createCell(1).setCellFormula("NOW()");

			workbook.write(out);
			return out.toByteArray();
		}
		catch (IOException ex) {
			throw new IllegalStateException("could not build the test workbook", ex);
		}
	}

	private static byte[] blankRowWorkbook() {
		try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			Sheet sheet = workbook.createSheet("Ledger");
			header(sheet, "invoice_no", "amount");
			sheet.createRow(1).createCell(0).setCellValue("INV-1001");
			sheet.createRow(1).createCell(1).setCellValue(100.0d);
			// Styled but empty, as a formatted export is full of.
			Row styled = sheet.createRow(2);
			styled.createCell(0).setCellStyle(workbook.createCellStyle());

			workbook.write(out);
			return out.toByteArray();
		}
		catch (IOException ex) {
			throw new IllegalStateException("could not build the test workbook", ex);
		}
	}

	private static byte[] hiddenSheetWorkbook() {
		try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			Sheet visible = workbook.createSheet("Ledger");
			header(visible, "invoice_no", "amount");
			visible.createRow(1).createCell(0).setCellValue("INV-1001");
			visible.createRow(1).createCell(1).setCellValue(100.0d);

			Sheet hidden = workbook.createSheet("Draft");
			header(hidden, "invoice_no", "amount");
			hidden.createRow(1).createCell(0).setCellValue("INV-DRAFT");
			workbook.setSheetVisibility(workbook.getSheetIndex(hidden), SheetVisibility.HIDDEN);

			workbook.write(out);
			return out.toByteArray();
		}
		catch (IOException ex) {
			throw new IllegalStateException("could not build the test workbook", ex);
		}
	}

	/** Every sheet hidden, so nothing is readable and the reason is "no readable sheet". */
	private static byte[] allHiddenSheetWorkbook() {
		try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			Sheet sheet = workbook.createSheet("Draft");
			header(sheet, "invoice_no", "amount");
			sheet.createRow(1).createCell(0).setCellValue("INV-DRAFT");
			workbook.setSheetVisibility(workbook.getSheetIndex(sheet), SheetVisibility.HIDDEN);

			workbook.write(out);
			return out.toByteArray();
		}
		catch (IOException ex) {
			throw new IllegalStateException("could not build the test workbook", ex);
		}
	}

	private static byte[] emptySheetWorkbook() {
		try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			workbook.createSheet("Empty");
			workbook.write(out);
			return out.toByteArray();
		}
		catch (IOException ex) {
			throw new IllegalStateException("could not build the test workbook", ex);
		}
	}

	private static void header(Sheet sheet, String... names) {
		Row row = sheet.createRow(0);
		for (int index = 0; index < names.length; index++) {
			row.createCell(index).setCellValue(names[index]);
		}
	}

	/** A minimal zip with the given entry names, so package shape can be tested. */
	private static byte[] zip(List<String> entryNames) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(out)) {
			for (String name : entryNames) {
				zip.putNextEntry(new ZipEntry(name));
				zip.write(("content of " + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
				zip.closeEntry();
			}
		}
		catch (IOException ex) {
			throw new IllegalStateException("could not build the test archive", ex);
		}
		return out.toByteArray();
	}

	/** A zip holding one highly compressible entry: the shape of a decompression bomb. */
	private static byte[] zipBomb(int inflatedBytes) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(out)) {
			zip.setLevel(Deflater.BEST_COMPRESSION);
			zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
			zip.write("<?xml version=\"1.0\"?>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
			zip.closeEntry();

			zip.putNextEntry(new ZipEntry("xl/workbook.xml"));
			byte[] zeros = new byte[inflatedBytes];
			zip.write(zeros);
			zip.closeEntry();
		}
		catch (IOException ex) {
			throw new IllegalStateException("could not build the test archive", ex);
		}
		return out.toByteArray();
	}

	private static IngestionLimits withMaxRows(int rows) {
		IngestionLimits limits = IngestionLimits.defaults();
		return new IngestionLimits(limits.maxFileBytes(), rows, limits.maxColumns(), limits.maxSheets(),
				limits.maxUncompressedBytes(), limits.maxArchiveEntries(), limits.maxCompressionRatio(),
				limits.ratioCheckMinBytes(), limits.maxCellTextLength(), limits.headerScanWindow());
	}

	private static IngestionLimits withMaxArchiveEntries(int entries) {
		IngestionLimits limits = IngestionLimits.defaults();
		return new IngestionLimits(limits.maxFileBytes(), limits.maxRowsPerFile(), limits.maxColumns(),
				limits.maxSheets(), limits.maxUncompressedBytes(), entries, limits.maxCompressionRatio(),
				limits.ratioCheckMinBytes(), limits.maxCellTextLength(), limits.headerScanWindow());
	}

	private static IngestionLimits withMaxUncompressedBytes(long bytes) {
		IngestionLimits limits = IngestionLimits.defaults();
		return new IngestionLimits(limits.maxFileBytes(), limits.maxRowsPerFile(), limits.maxColumns(),
				limits.maxSheets(), bytes, limits.maxArchiveEntries(), limits.maxCompressionRatio(),
				limits.ratioCheckMinBytes(), limits.maxCellTextLength(), limits.headerScanWindow());
	}

}
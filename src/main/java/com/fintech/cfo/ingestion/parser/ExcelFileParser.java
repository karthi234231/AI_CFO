package com.fintech.cfo.ingestion.parser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.ss.usermodel.Date1904Support;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import com.fintech.cfo.ingestion.enums.FieldType;
import com.fintech.cfo.ingestion.enums.FileType;
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
 * Workbook reader built on Apache POI.
 *
 * <p>Handles multiple sheets, finds the header on each sheet independently, keeps
 * each cell's real type, and refuses the file deterministically when it is not a
 * readable spreadsheet package.
 *
 * <p>Row coordinates use Excel's own 1-based row numbers, sheet name included.
 * That is the number an operator sees on screen and the one they will quote in a
 * dispute, so evidence that used any other numbering would not survive contact
 * with a conversation.
 *
 * <p>Defences, in order, before POI sees anything:
 *
 * <ul>
 * <li>the size ceiling is enforced while buffering, so a huge upload is refused
 * rather than materialised;</li>
 * <li>{@link ArchiveGuard} walks the zip under entry-count, inflated-byte and
 * inflation-ratio ceilings, which is what stops a zip bomb;</li>
 * <li>formula cells are never evaluated, and a formula with no cached result
 * rejects its row rather than becoming a fabricated zero.</li>
 * </ul>
 *
 * <p>Row-level isolation: each row is read inside its own try/catch. A corrupt
 * cell, a formula with no cached value, or a row past the column limit becomes a
 * {@link RejectedRow} with its coordinates, and the next row is read normally.
 */
public final class ExcelFileParser implements FileParser {

	private final ExcelParseOptions options;

	public ExcelFileParser() {
		this(ExcelParseOptions.defaults());
	}

	public ExcelFileParser(ExcelParseOptions options) {
		this.options = Objects.requireNonNull(options, "options must not be null");
	}

	public ExcelParseOptions options() {
		return this.options;
	}

	@Override
	public FileType supportedType() {
		return FileType.XLSX;
	}

	@Override
	public FileParseResult parse(ParseRequest request) {
		Objects.requireNonNull(request, "request must not be null");
		byte[] content;
		try {
			content = request.readContent();
		}
		catch (ParseRequest.ContentTooLargeException ex) {
			return FileParseResult.failed(FileType.XLSX, RejectionReason.SIZE_LIMIT_EXCEEDED, ex.getMessage());
		}
		catch (IOException ex) {
			return FileParseResult.failed(FileType.XLSX, RejectionReason.CORRUPT_FILE,
					"the upload stream could not be read (" + ex.getClass().getSimpleName() + ")");
		}

		if (content.length == 0) {
			return FileParseResult.failed(FileType.XLSX, RejectionReason.EMPTY_FILE, "the file contains no bytes");
		}

		try {
			ArchiveGuard.ArchiveInspection inspection = ArchiveGuard.inspect(content, request.limits());
			if (!inspection.looksLikeSpreadsheetPackage()) {
				return FileParseResult.failed(FileType.XLSX, RejectionReason.CORRUPT_FILE,
						inspection.looksLikeOtherOoxmlPackage()
								? "the container is an Office document package but not a spreadsheet"
								: "the container does not hold a spreadsheet package");
			}
		}
		catch (ArchiveGuard.ArchiveRejection ex) {
			// The reason travels on the exception rather than being recovered from
			// the wording of the message, which would silently reclassify a refusal
			// as ARCHIVE_LIMIT_EXCEEDED the first time the text is reworded.
			return FileParseResult.failed(FileType.XLSX, ex.reason(), ex.getMessage());
		}

		Workbook workbook;
		try {
			workbook = WorkbookFactory.create(new ByteArrayInputStream(content));
		}
		catch (EncryptedDocumentException ex) {
			return FileParseResult.failed(FileType.XLSX, RejectionReason.ENCRYPTED_FILE,
					"the workbook is password protected; remove the protection and upload it again");
		}
		catch (IllegalArgumentException | IndexOutOfBoundsException ex) {
			return FileParseResult.failed(FileType.XLSX, RejectionReason.CORRUPT_FILE,
					"the content is not a readable workbook (" + ex.getClass().getSimpleName() + ")");
		}
		catch (IOException ex) {
			return FileParseResult.failed(FileType.XLSX, RejectionReason.CORRUPT_FILE,
					"the workbook container could not be opened (" + ex.getClass().getSimpleName() + ")");
		}
		catch (RuntimeException ex) {
			return FileParseResult.failed(FileType.XLSX, RejectionReason.CORRUPT_FILE,
					"the workbook could not be opened (" + ex.getClass().getSimpleName() + ")");
		}

		try (Workbook open = workbook) {
			return readWorkbook(open, request);
		}
		catch (IOException ex) {
			return FileParseResult.failed(FileType.XLSX, RejectionReason.CORRUPT_FILE,
					"the workbook could not be closed cleanly (" + ex.getClass().getSimpleName() + ")");
		}
		catch (RuntimeException ex) {
			return FileParseResult.failed(FileType.XLSX, RejectionReason.CORRUPT_FILE,
					"the workbook could not be read (" + ex.getClass().getSimpleName() + ")");
		}
	}

	private FileParseResult readWorkbook(Workbook workbook, ParseRequest request) {
		IngestionLimits limits = request.limits();
		boolean workbookUses1904 = workbook instanceof Date1904Support date1904
				&& date1904.isDate1904();
		boolean use1904Epoch = this.options.matchesWorkbookEpoch(workbookUses1904);
		FileParseResult.Builder result = FileParseResult.builder(FileType.XLSX);
		int sheetsRead = 0;

		for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
			if (sheetsRead >= limits.maxSheets()) {
				result.add(ValidationFinding.file(IngestionErrorType.LIMIT_EXCEEDED, RejectionReason.MAX_ROWS_EXCEEDED,
						"reading stopped after " + limits.maxSheets()
								+ " sheets as configured; the remaining sheets were not read"));
				result.status(ParseStatus.PARTIAL);
				break;
			}
			String sheetName = workbook.getSheetName(sheetIndex);
			if (!this.options.isIncluded(sheetName)) {
				continue;
			}
			if (this.options.skipHiddenSheets()
					&& workbook.getSheetVisibility(sheetIndex) == SheetVisibility.HIDDEN) {
				result.add(ValidationFinding.file(ValidationSeverity.INFO, IngestionErrorType.PARSE,
						RejectionReason.BLANK_ROW, "hidden sheet " + sheetName + " was not read"));
				continue;
			}
			sheetsRead++;
			result.sheetName(sheetName);
			readSheet(workbook.getSheetAt(sheetIndex), sheetName, request, use1904Epoch, limits, result);
		}

		if (sheetsRead == 0) {
			return FileParseResult.failed(FileType.XLSX, RejectionReason.NO_READABLE_COLUMNS,
					"the workbook contains no readable sheet");
		}
		return result.build();
	}

	private void readSheet(Sheet sheet, String sheetName, ParseRequest request, boolean use1904Epoch,
			IngestionLimits limits, FileParseResult.Builder result) {
		List<List<String>> leadingRows = new ArrayList<>();
		int firstRow = sheet.getFirstRowNum();
		int lastRow = sheet.getLastRowNum();
		int width = 0;

		for (int rowIndex = firstRow; rowIndex <= lastRow; rowIndex++) {
			Row row = sheet.getRow(rowIndex);
			if (row == null) {
				continue;
			}
			width = Math.max(width, row.getLastCellNum());
			List<String> texts = ExcelCellReader.textsOf(row, width, use1904Epoch);
			if (isBlank(texts)) {
				continue;
			}
			leadingRows.add(texts);
			if (leadingRows.size() > 50) {
				break;
			}
		}
		if (leadingRows.isEmpty()) {
			return;
		}

		int headerOffset = resolveHeaderOffset(leadingRows, limits.headerScanWindow());
		List<String> columnNames;
		int firstDataRow;
		if (headerOffset >= 0) {
			// Recorded before HeaderDetector rewrites repeats, so the schema gate can
			// still see that this sheet's header carried the same label twice.
			result.rawHeader(leadingRows.get(headerOffset));
			columnNames = HeaderDetector.normalise(leadingRows.get(headerOffset));
			firstDataRow = firstRow + headerOffset + 1;
			for (int offset = 0; offset < headerOffset; offset++) {
				result.skipRows(1);
				result.add(ValidationFinding.file(ValidationSeverity.INFO, IngestionErrorType.SCHEMA,
						RejectionReason.MISSING_HEADER, "sheet " + sheetName + " row " + (firstRow + offset + 1)
								+ " precedes the header row and was not treated as data"));
			}
		}
		else {
			columnNames = HeaderDetector.positionalNames(width);
			firstDataRow = firstRow;
		}
		result.columns(columnNames);

		int accepted = 0;
		boolean rowLimitReached = false;
		for (int rowIndex = firstDataRow; rowIndex <= lastRow; rowIndex++) {
			Row row = sheet.getRow(rowIndex);
			long rowNumber = rowIndex + 1L;
			if (row == null) {
				continue;
			}
			if (ExcelCellReader.isBlankRow(row, width)) {
				result.skipRows(1);
				continue;
			}
			if (accepted >= limits.maxRowsPerFile()) {
				rowLimitReached = true;
				result.add(ValidationFinding.file(IngestionErrorType.LIMIT_EXCEEDED, RejectionReason.MAX_ROWS_EXCEEDED,
						"reading stopped at the configured maximum of " + limits.maxRowsPerFile()
								+ " rows on sheet " + sheetName + "; the remainder was not read"));
				break;
			}
			RowCoordinate coordinate = new RowCoordinate(request.sourceFileId(), request.fileName(), rowNumber,
					sheetName);
			RowOutcome outcome = readRow(row, coordinate, columnNames, limits, use1904Epoch, result);
			if (outcome.row() != null) {
				result.add(outcome.row());
				accepted++;
			}
			else {
				result.reject(outcome.rejection());
			}
		}
		if (rowLimitReached) {
			result.status(ParseStatus.PARTIAL);
		}
	}

	/**
	 * Reads one row in isolation. Whatever goes wrong — an unreadable cell, a
	 * formula with no cached value, a row wider than the column limit — comes back
	 * as a rejection for this row alone.
	 */
	private RowOutcome readRow(Row row, RowCoordinate coordinate, List<String> columnNames, IngestionLimits limits,
			boolean use1904Epoch, FileParseResult.Builder result) {
		int lastCell = row.getLastCellNum();
		if (lastCell > limits.maxColumns()) {
			return RowOutcome.rejected(RejectedRow.of(coordinate, RejectionReason.FIELD_COUNT_MISMATCH,
					"the row spans " + lastCell + " columns but at most " + limits.maxColumns() + " are permitted"));
		}
		int cells = Math.max(lastCell, 0);
		if (cells > columnNames.size()) {
			return RowOutcome.rejected(RejectedRow.of(coordinate, RejectionReason.FIELD_COUNT_MISMATCH,
					"the row has " + cells + " cells but the header declares " + columnNames.size()));
		}

		List<ParsedCell> parsed = new ArrayList<>(cells);
		for (int index = 0; index < cells; index++) {
			String columnName = index < columnNames.size() ? columnNames.get(index) : "column_" + (index + 1);
			Cell cell = row.getCell(index, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
			if (cell == null) {
				parsed.add(ParsedCell.blank(columnName, index));
				continue;
			}
			ParsedCell read;
			try {
				read = ExcelCellReader.read(cell, columnName, index, use1904Epoch);
			}
			catch (ExcelCellReader.FormulaWithoutCachedValue ex) {
				return RowOutcome.rejected(RejectedRow.ofField(coordinate, columnName,
						RejectionReason.FORMULA_WITHOUT_CACHED_RESULT,
						"the cell holds a formula with no stored result, and this module never evaluates an "
								+ "uploaded formula",
						ex.expression()));
			}
			catch (RuntimeException ex) {
				return RowOutcome.rejected(RejectedRow.ofField(coordinate, columnName, RejectionReason.UNREADABLE_CELL,
						"the cell could not be read (" + ex.getClass().getSimpleName() + ")", null));
			}
			if (read.text().length() > limits.maxCellTextLength()) {
				return RowOutcome.rejected(RejectedRow.ofField(coordinate, columnName, RejectionReason.VALUE_TOO_LONG,
						"the value is " + read.text().length() + " characters long; the limit is "
								+ limits.maxCellTextLength(),
						null));
			}
			if (read.type() == FieldType.ERROR) {
				result.add(ValidationFinding.field(IngestionErrorType.DATA_TYPE, RejectionReason.INVALID_FIELD_FORMAT,
						coordinate, columnName,
						"the cell holds the spreadsheet error value " + read.text()
								+ "; the row is kept so the error stays visible to finance"));
			}
			parsed.add(read);
		}
		return RowOutcome.accepted(ParsedRow.of(coordinate, FileType.XLSX, parsed));
	}

	private int resolveHeaderOffset(List<List<String>> leadingRows, int scanWindow) {
		return switch (this.options.headerMode()) {
			case NONE -> -1;
			case FIRST_RECORD -> 0;
			case AUTO -> HeaderDetector.detect(leadingRows, scanWindow);
		};
	}

	private static boolean isBlank(List<String> texts) {
		return texts.stream().allMatch(value -> value == null || value.trim().isEmpty());
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
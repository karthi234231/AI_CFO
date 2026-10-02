package com.fintech.cfo.ingestion.parser;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaError;
import org.apache.poi.ss.usermodel.RichTextString;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFCell;

import com.fintech.cfo.ingestion.enums.FieldType;
import com.fintech.cfo.ingestion.model.ParsedCell;

/**
 * Turns one POI cell into a {@link ParsedCell}, preserving the type it actually
 * had.
 *
 * <p>Three decisions here are load-bearing:
 *
 * <ul>
 * <li><b>Formulas are never evaluated.</b> Evaluating an uploaded formula means
 * running attacker-supplied expressions through the JVM's formula engine, and it
 * would also make a result depend on the evaluator rather than on the file. The
 * cached result stored by the last spreadsheet application becomes the value; the
 * expression is kept as {@code rawText}, so a number used in a calculation can
 * still be traced to the formula that produced it.</li>
 * <li><b>No {@code double} escapes.</b> POI exposes numeric cells as
 * {@code double}; they are converted at once with
 * {@code new BigDecimal(Double.toString(value))} — the shortest decimal that
 * round-trips — and carried as text, so binary floating-point error can never
 * reach the money layer (rule 2).</li>
 * <li><b>A missing cached result is a refusal, not a zero.</b> A formula nobody
 * ever calculated has no value; reading it as {@code 0} would invent a monetary
 * figure the file never asserted.</li>
 * </ul>
 */
final class ExcelCellReader {

	private ExcelCellReader() {
	}

	/**
	 * Signals that a formula cell carries no stored result, so the row cannot be
	 * valued without evaluating the formula, which this module never does.
	 */
	static final class FormulaWithoutCachedValue extends RuntimeException {

		private static final long serialVersionUID = 1L;

		private final String expression;

		FormulaWithoutCachedValue(String expression) {
			super("the formula cell has no cached result");
			this.expression = expression == null ? "" : expression;
		}

		String expression() {
			return this.expression;
		}

	}

	/**
	 * @param use1904Epoch whether the workbook uses the 1904 date system
	 * @return the typed cell, or a {@link FormulaWithoutCachedValue} refusal
	 */
	static ParsedCell read(Cell cell, String columnName, int columnIndex, boolean use1904Epoch) {
		Objects.requireNonNull(cell, "cell must not be null");
		return switch (cell.getCellType()) {
			case STRING -> string(cell, columnName, columnIndex);
			case NUMERIC -> numeric(cell, columnName, columnIndex, use1904Epoch);
			case BOOLEAN -> text(cell, columnName, columnIndex, FieldType.BOOLEAN,
					Boolean.toString(cell.getBooleanCellValue()));
			case BLANK -> ParsedCell.blank(columnName, columnIndex);
			case ERROR -> text(cell, columnName, columnIndex, FieldType.ERROR, errorText(cell.getErrorCellValue()));
			case FORMULA -> formula(cell, columnName, columnIndex, use1904Epoch);
			case _NONE -> ParsedCell.blank(columnName, columnIndex);
		};
	}

	/**
	 * @return the text of any cell type, used only for header detection where a
	 * typed value is not wanted and a throwing accessor would be unhelpful
	 */
	static String displayText(Cell cell, boolean use1904Epoch) {
		if (cell == null) {
			return "";
		}
		try {
			if (cell.getCellType() == CellType.STRING) {
				return stringValue(cell);
			}
			return read(cell, "", cell.getColumnIndex(), use1904Epoch).text();
		}
		catch (FormulaWithoutCachedValue ex) {
			return "";
		}
		catch (RuntimeException ex) {
			return "";
		}
	}

	private static ParsedCell string(Cell cell, String columnName, int columnIndex) {
		String value = stringValue(cell);
		return new ParsedCell(columnName, columnIndex, FieldType.STRING, value, value);
	}

	private static ParsedCell text(Cell cell, String columnName, int columnIndex, FieldType type, String value) {
		return new ParsedCell(columnName, columnIndex, type, value, value);
	}

	private static ParsedCell numeric(Cell cell, String columnName, int columnIndex, boolean use1904Epoch) {
		if (isDateFormatted(cell)) {
			LocalDateTime moment = DateUtil.getLocalDateTime(cell.getNumericCellValue(), use1904Epoch);
			if (moment != null) {
				return new ParsedCell(columnName, columnIndex, dateOrDateTime(moment), moment.toString(),
						plain(cell));
			}
		}
		return new ParsedCell(columnName, columnIndex, FieldType.NUMBER, plain(cell), plain(cell));
	}

	private static ParsedCell formula(Cell cell, String columnName, int columnIndex, boolean use1904Epoch) {
		String expression = expressionOf(cell);
		// WHY the guard tests the stored *value* rather than the cached *type*: OOXML
		// defaults the t attribute to "n" (numeric) and allows a <f> with no <v>, so
		// POI reports such a cell's cached type as NUMERIC and then reads its numeric
		// value as 0.0. Switching on the type therefore never reaches the
		// FormulaWithoutCachedValue branch below, and a formula nobody ever
		// calculated would be published as a fabricated zero — precisely the invented
		// monetary figure this module must never produce.
		if (hasNoCachedValue(cell)) {
			throw new FormulaWithoutCachedValue(expression);
		}
		CellType cached = cell.getCachedFormulaResultType();
		return switch (cached) {
			case STRING -> ParsedCell.formula(columnName, columnIndex, FieldType.STRING, stringValue(cell), expression);
			case NUMERIC -> formulaNumeric(cell, columnName, columnIndex, use1904Epoch, expression);
			case BOOLEAN -> ParsedCell.formula(columnName, columnIndex, FieldType.BOOLEAN,
					Boolean.toString(cell.getBooleanCellValue()), expression);
			case ERROR -> ParsedCell.formula(columnName, columnIndex, FieldType.ERROR, errorText(cell.getErrorCellValue()),
					expression);
			case BLANK, _NONE -> throw new FormulaWithoutCachedValue(expression);
			case FORMULA -> throw new FormulaWithoutCachedValue(expression);
		};
	}

	/**
	 * @return true only when the file positively stored no cached result for this
	 * formula cell
	 *
	 * <p>POI 5 exposes the stored value on {@code XSSFCell} rather than on the
	 * {@code Cell} interface, so the check is narrowed to the OOXML implementation.
	 * This module reads XLSX only — {@code FileType} has no other spreadsheet type
	 * and {@link ArchiveGuard} refuses anything that is not an OOXML spreadsheet
	 * package — so the narrowing loses no reachable case. Any other implementation
	 * returns false and falls through to the cached-type switch, which is the
	 * behaviour that existed before: a cell is only ever refused here on positive
	 * evidence that its value is absent, never because the check could not be made.
	 */
	private static boolean hasNoCachedValue(Cell cell) {
		if (cell instanceof XSSFCell ooxml) {
			return ooxml.getRawValue() == null;
		}
		return false;
	}

	private static ParsedCell formulaNumeric(Cell cell, String columnName, int columnIndex, boolean use1904Epoch,
			String expression) {
		if (isDateFormatted(cell)) {
			LocalDateTime moment = DateUtil.getLocalDateTime(cell.getNumericCellValue(), use1904Epoch);
			if (moment != null) {
				return ParsedCell.formula(columnName, columnIndex, dateOrDateTime(moment), moment.toString(),
						expression);
			}
		}
		return ParsedCell.formula(columnName, columnIndex, FieldType.NUMBER, plain(cell), expression);
	}

	private static FieldType dateOrDateTime(LocalDateTime moment) {
		return moment.toLocalTime().equals(java.time.LocalTime.MIDNIGHT) ? FieldType.DATE : FieldType.DATETIME;
	}

	private static boolean isDateFormatted(Cell cell) {
		return cell.getCellStyle() != null && DateUtil.isValidExcelDate(cell.getNumericCellValue())
				&& DateUtil.isCellDateFormatted(cell);
	}

	/**
	 * Converts POI's {@code double} into the shortest decimal that reproduces it.
	 *
	 * <p>{@code new BigDecimal(double)} would preserve the exact binary expansion —
	 * {@code 1234.5600000000001} — and hand a fabricated tail to the money layer.
	 * Going through {@code Double.toString} yields {@code 1234.56}, which is both
	 * deterministic and locale-independent.
	 */
	private static String plain(Cell cell) {
		return new BigDecimal(Double.toString(cell.getNumericCellValue())).toPlainString();
	}

	private static String expressionOf(Cell cell) {
		try {
			return cell.getCellFormula();
		}
		catch (RuntimeException ex) {
			return "";
		}
	}

	private static String errorText(byte errorCode) {
		try {
			return "#" + FormulaError.forInt(errorCode).getString();
		}
		catch (IllegalArgumentException ex) {
			return "#ERROR";
		}
	}

	private static String stringValue(Cell cell) {
		try {
			RichTextString rich = cell.getRichStringCellValue();
			return rich == null ? "" : rich.getString();
		}
		catch (RuntimeException ex) {
			return "";
		}
	}

	/**
	 * @return the row rendered as one text per column, blanks for gaps, so the
	 * header and the data rows can be compared position by position
	 */
	static List<String> textsOf(Row row, int width, boolean use1904Epoch) {
		List<String> values = new ArrayList<>(width);
		for (int index = 0; index < width; index++) {
			values.add(displayText(row == null ? null : row.getCell(index, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL),
					use1904Epoch));
		}
		return values;
	}

	/**
	 * @return true when the row holds no data at all — the styled-but-empty rows a
	 * formatted export is full of
	 */
	static boolean isBlankRow(Row row, int width) {
		for (int index = 0; index < width; index++) {
			Cell cell = row.getCell(index, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
			if (cell == null) {
				continue;
			}
			if (cell.getCellType() == CellType.BLANK || cell.getCellType() == CellType._NONE) {
				continue;
			}
			if (cell.getCellType() == CellType.STRING && stringValue(cell).isBlank()) {
				continue;
			}
			return false;
		}
		return true;
	}

}
package com.fintech.cfo.ingestion.parser;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import com.fintech.cfo.ingestion.enums.HeaderMode;

/**
 * Read options for the workbook reader.
 *
 * @param headerMode               how the header row is found, applied per sheet
 * @param skipHiddenSheets         ignore tabs the uploader hid; the skip is reported
 * @param includedSheets           restrict reading to these tab names; empty means all
 * @param requireCachedFormulaResults refuse a formula cell that has no cached result
 * @param date1904Epoch            set when the workbook uses the 1904 date system
 */
public record ExcelParseOptions(HeaderMode headerMode, boolean skipHiddenSheets, Set<String> includedSheets,
		boolean requireCachedFormulaResults, boolean date1904Epoch) {

	public ExcelParseOptions {
		Objects.requireNonNull(headerMode, "headerMode must not be null");
		includedSheets = includedSheets == null ? Set.of() : Set.copyOf(includedSheets);
	}

	public static ExcelParseOptions defaults() {
		return new ExcelParseOptions(HeaderMode.AUTO, true, Set.of(), true, false);
	}

	public ExcelParseOptions withHeaderMode(HeaderMode newHeaderMode) {
		return new ExcelParseOptions(newHeaderMode, this.skipHiddenSheets, this.includedSheets,
				this.requireCachedFormulaResults, this.date1904Epoch);
	}

	public ExcelParseOptions withIncludedSheets(Set<String> sheets) {
		return new ExcelParseOptions(this.headerMode, this.skipHiddenSheets, sheets, this.requireCachedFormulaResults,
				this.date1904Epoch);
	}

	public ExcelParseOptions withSkipHiddenSheets(boolean skip) {
		return new ExcelParseOptions(this.headerMode, skip, this.includedSheets, this.requireCachedFormulaResults,
				this.date1904Epoch);
	}

	public ExcelParseOptions withDate1904Epoch(boolean use1904) {
		return new ExcelParseOptions(this.headerMode, this.skipHiddenSheets, this.includedSheets,
				this.requireCachedFormulaResults, use1904);
	}

	/**
	 * POI reports the workbook's own epoch, and this flag lets a caller override it
	 * for a workbook whose flag was lost in an export round trip.
	 */
	public boolean matchesWorkbookEpoch(boolean workbookSays1904) {
		return this.date1904Epoch || workbookSays1904;
	}

	public boolean isIncluded(String sheetName) {
		return this.includedSheets.isEmpty()
				|| this.includedSheets.stream().anyMatch(name -> name.equalsIgnoreCase(sheetName));
	}

	@Override
	public String toString() {
		return "ExcelParseOptions[header=" + this.headerMode.name().toLowerCase(Locale.ROOT) + ", hidden="
				+ this.skipHiddenSheets + ", cachedFormulas=" + this.requireCachedFormulaResults + "]";
	}

}
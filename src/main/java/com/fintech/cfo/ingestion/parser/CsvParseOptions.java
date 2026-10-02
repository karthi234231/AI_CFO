package com.fintech.cfo.ingestion.parser;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import com.fintech.cfo.ingestion.enums.HeaderMode;

/**
 * Dialect and tolerance settings for the CSV reader.
 *
 * <p>A record rather than scattered setters, because a parse must be
 * reproducible: the exact dialect a file was read with is part of how a result
 * can be re-derived months later (rule 3), so it travels with the reader instead
 * of living in ambient state.
 *
 * @param delimiter         field separator
 * @param quote             quote character, {@code null} disables quoting entirely
 * @param escape            escape character, {@code null} for the quote character
 * @param headerMode        how the header is located
 * @param ignoreEmptyLines  skip physically empty lines instead of reporting them
 * @param lenientEndOfFile  accept an unterminated quoted field at end of file
 * @param trimUnquoted      strip surrounding whitespace from unquoted fields only
 * @param maxColumnsHard    when true a wider-than-allowed record is rejected rather
 *                          than padded or truncated
 */
public record CsvParseOptions(char delimiter, Character quote, Character escape, HeaderMode headerMode,
		boolean ignoreEmptyLines, boolean lenientEndOfFile, boolean trimUnquoted, Charset defaultCharset,
		boolean maxColumnsHard) {

	public CsvParseOptions {
		Objects.requireNonNull(headerMode, "headerMode must not be null");
		defaultCharset = defaultCharset == null ? StandardCharsets.UTF_8 : defaultCharset;
		if (delimiter == '"' || delimiter == '\n' || delimiter == '\r' || delimiter == 0) {
			throw new IllegalArgumentException("delimiter must be a character that cannot start a quoted field");
		}
		if (quote != null && (quote == delimiter || quote == '\n' || quote == '\r')) {
			throw new IllegalArgumentException("quote must differ from the delimiter");
		}
	}

	/**
	 * RFC-4180-ish comma separated, UTF-8 unless a byte-order mark says otherwise.
	 *
	 * <p><b>Why {@code ignoreEmptyLines} is false here.</b> When the lexer is told
	 * to ignore empty lines it drops them silently: the record never reaches this
	 * module, so the position is not read, not refused and not counted as skipped,
	 * and {@code FileParseResult.totalRowCount()} stops being the total an operator
	 * reconciles a file against. A run that quietly ignored 400 blank rows is a run
	 * whose row numbering nobody can reproduce, which is the outcome
	 * {@code skippedRows} exists to prevent. With it false the blank record arrives,
	 * is counted, and is reported as {@code BLANK_ROW}.
	 */
	public static CsvParseOptions defaults() {
		return new CsvParseOptions(',', '"', '"', HeaderMode.AUTO, false, false, false, StandardCharsets.UTF_8, true);
	}

	public static CsvParseOptions delimited(char newDelimiter) {
		return defaults().withDelimiter(newDelimiter);
	}

	public static CsvParseOptions tabSeparated() {
		return defaults().withDelimiter('\t');
	}

	public static CsvParseOptions semicolonSeparated() {
		return defaults().withDelimiter(';');
	}

	public CsvParseOptions withDelimiter(char newDelimiter) {
		return new CsvParseOptions(newDelimiter, this.quote, this.escape, this.headerMode, this.ignoreEmptyLines,
				this.lenientEndOfFile, this.trimUnquoted, this.defaultCharset, this.maxColumnsHard);
	}

	public CsvParseOptions withHeaderMode(HeaderMode newHeaderMode) {
		return new CsvParseOptions(this.delimiter, this.quote, this.escape, Objects.requireNonNull(newHeaderMode),
				this.ignoreEmptyLines, this.lenientEndOfFile, this.trimUnquoted, this.defaultCharset, this.maxColumnsHard);
	}

	public CsvParseOptions withLenientEndOfFile(boolean lenient) {
		return new CsvParseOptions(this.delimiter, this.quote, this.escape, this.headerMode, this.ignoreEmptyLines,
				lenient, this.trimUnquoted, this.defaultCharset, this.maxColumnsHard);
	}

	public CsvParseOptions withTrimUnquoted(boolean trim) {
		return new CsvParseOptions(this.delimiter, this.quote, this.escape, this.headerMode, this.ignoreEmptyLines,
				this.lenientEndOfFile, trim, this.defaultCharset, this.maxColumnsHard);
	}

	public CsvParseOptions withDefaultCharset(Charset charset) {
		return new CsvParseOptions(this.delimiter, this.quote, this.escape, this.headerMode, this.ignoreEmptyLines,
				this.lenientEndOfFile, this.trimUnquoted, charset, this.maxColumnsHard);
	}

	public CsvParseOptions withIgnoreEmptyLines(boolean ignore) {
		return new CsvParseOptions(this.delimiter, this.quote, this.escape, this.headerMode, ignore,
				this.lenientEndOfFile, this.trimUnquoted, this.defaultCharset, this.maxColumnsHard);
	}

}
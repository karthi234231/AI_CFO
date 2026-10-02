package com.fintech.cfo.ingestion.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.fintech.cfo.ingestion.enums.FieldType;
import com.fintech.cfo.ingestion.enums.FileType;

/**
 * One successfully parsed row, bound to the file, sheet and row it came from.
 *
 * <p>Cells keep header order and their detected type. {@code rawPayload} is a
 * deterministic, lossless-enough dump of the cells joined with {@code |}, which
 * is what a future {@code source_records.raw_payload} column can hold without
 * storing the whole document (rule 4).
 *
 * <p>Final class rather than a record because the lookups are behaviour and the
 * defensive copies are the invariant worth protecting: a row handed to the
 * financial layer must not be mutable afterwards, or the evidence changes after
 * the number was reported.
 */
public final class ParsedRow {

	/**
	 * Field separator used by {@link #rawPayload()}. A pipe cannot appear
	 * unescaped in a header name and is not a CSV delimiter we accept by
	 * default, so it is unambiguous enough for a diagnostic payload.
	 */
	public static final String PAYLOAD_SEPARATOR = "|";

	private final RowCoordinate coordinate;
	private final FileType fileType;
	private final Map<String, ParsedCell> cells;
	private final List<ParsedCell> orderedCells;
	private final String rawPayload;

	private ParsedRow(RowCoordinate coordinate, FileType fileType, List<ParsedCell> cells) {
		this.coordinate = Objects.requireNonNull(coordinate, "coordinate must not be null");
		this.fileType = Objects.requireNonNull(fileType, "fileType must not be null");
		Objects.requireNonNull(cells, "cells must not be null");
		this.orderedCells = List.copyOf(cells);

		Map<String, ParsedCell> indexed = new LinkedHashMap<>();
		StringBuilder payload = new StringBuilder();
		for (ParsedCell cell : this.orderedCells) {
			indexed.putIfAbsent(cell.columnName(), cell);
			if (payload.length() > 0) {
				payload.append(PAYLOAD_SEPARATOR);
			}
			payload.append(cell.rawText());
		}
		this.cells = Collections.unmodifiableMap(indexed);
		this.rawPayload = payload.toString();
	}

	public static ParsedRow of(RowCoordinate coordinate, FileType fileType, List<ParsedCell> cells) {
		return new ParsedRow(coordinate, fileType, cells);
	}

	public RowCoordinate coordinate() {
		return this.coordinate;
	}

	public FileType fileType() {
		return this.fileType;
	}

	public List<ParsedCell> cells() {
		return this.orderedCells;
	}

	public Map<String, ParsedCell> cellsByColumn() {
		return this.cells;
	}

	public String rawPayload() {
		return this.rawPayload;
	}

	public Optional<ParsedCell> cell(String columnName) {
		return Optional.ofNullable(this.cells.get(columnName));
	}

	public String text(String columnName) {
		ParsedCell cell = this.cells.get(columnName);
		return cell == null ? "" : cell.text();
	}

	public String rawText(String columnName) {
		ParsedCell cell = this.cells.get(columnName);
		return cell == null ? "" : cell.rawText();
	}

	public FieldType typeOf(String columnName) {
		ParsedCell cell = this.cells.get(columnName);
		return cell == null ? FieldType.BLANK : cell.type();
	}

	@Override
	public String toString() {
		return this.coordinate + " -> " + this.rawPayload;
	}

}
package com.fintech.cfo.ingestion.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The expected shape of a data file: which columns exist and what they must
 * contain.
 *
 * <p>Supplied by the caller, never inferred from the upload. A schema read from
 * the file it is validating cannot reject anything.
 *
 * <p>Lookups are case-insensitive because header casing varies between exports of
 * the same ERP; lookups are nevertheless deterministic, iterating the declared
 * order rather than a hash order (rule 3).
 */
public final class IngestionSchema {

	private final List<ColumnSchema> columns;
	private final Map<String, ColumnSchema> byLowerCaseName;

	private IngestionSchema(List<ColumnSchema> columns) {
		this.columns = List.copyOf(columns);
		Map<String, ColumnSchema> index = new LinkedHashMap<>();
		for (ColumnSchema column : this.columns) {
			index.putIfAbsent(column.name().toLowerCase(Locale.ROOT), column);
		}
		this.byLowerCaseName = Map.copyOf(index);
	}

	public static IngestionSchema of(List<ColumnSchema> columns) {
		Objects.requireNonNull(columns, "columns must not be null");
		return new IngestionSchema(columns);
	}

	public static IngestionSchema empty() {
		return new IngestionSchema(List.of());
	}

	public List<ColumnSchema> columns() {
		return this.columns;
	}

	public boolean isEmpty() {
		return this.columns.isEmpty();
	}

	public Optional<ColumnSchema> column(String name) {
		if (name == null) {
			return Optional.empty();
		}
		return Optional.ofNullable(this.byLowerCaseName.get(name.trim().toLowerCase(Locale.ROOT)));
	}

	public boolean isRequired(String name) {
		return column(name).map(ColumnSchema::required).orElse(false);
	}

	public List<String> requiredColumnNames() {
		List<String> names = new ArrayList<>();
		for (ColumnSchema column : this.columns) {
			if (column.required()) {
				names.add(column.name());
			}
		}
		return names;
	}

	/**
	 * @param fileColumns the columns actually found in the file
	 * @return declared required columns that are absent, in declared order
	 */
	public List<String> missingRequiredColumns(List<String> fileColumns) {
		Objects.requireNonNull(fileColumns, "fileColumns must not be null");
		List<String> present = fileColumns.stream().map(value -> value.toLowerCase(Locale.ROOT)).toList();
		List<String> missing = new ArrayList<>();
		for (String required : requiredColumnNames()) {
			if (!present.contains(required.toLowerCase(Locale.ROOT))) {
				missing.add(required);
			}
		}
		return missing;
	}

	@Override
	public String toString() {
		return "IngestionSchema" + this.columns;
	}

}
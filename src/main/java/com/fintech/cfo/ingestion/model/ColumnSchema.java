package com.fintech.cfo.ingestion.model;

import java.util.Objects;

import com.fintech.cfo.ingestion.enums.ColumnType;

/**
 * One expected column: its name, its declared type, and whether a row may omit it.
 *
 * <p>Value type, not a database mapping. The schema an upload is validated against
 * is supplied by the caller (eventually derived from the source system
 * configuration), never inferred from the file — inferring it would let a hostile
 * export describe itself and pass its own validation.
 */
public record ColumnSchema(String name, ColumnType type, boolean required) {

	public ColumnSchema {
		Objects.requireNonNull(name, "name must not be null");
		Objects.requireNonNull(type, "type must not be null");
		if (name.isBlank()) {
			throw new IllegalArgumentException("column name must not be blank");
		}
	}

	public static ColumnSchema required(String name, ColumnType type) {
		return new ColumnSchema(name, type, true);
	}

	public static ColumnSchema optional(String name, ColumnType type) {
		return new ColumnSchema(name, type, false);
	}

	@Override
	public String toString() {
		return this.name + ":" + this.type + (this.required ? "!" : "?");
	}

}
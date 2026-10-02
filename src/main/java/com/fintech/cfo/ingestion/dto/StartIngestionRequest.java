package com.fintech.cfo.ingestion.dto;

import java.util.Objects;

import com.fintech.cfo.ingestion.enums.ColumnType;
import com.fintech.cfo.ingestion.model.ColumnSchema;
import com.fintech.cfo.ingestion.model.IngestionSchema;

/**
 * What a client supplies to start an ingestion run.
 *
 * <p>The schema arrives with the request rather than being inferred from the file.
 * A schema read out of the upload could describe itself and then satisfy its own
 * validation, which is not validation at all.
 *
 * <p>No upload bytes here: this module works on a buffered {@code byte[]} handed to
 * it by the transport layer, so the request describes <em>which</em> file and
 * <em>against what</em>, and never the content. That is also why there is no
 * Jackson annotation and no multipart binding here — the web boundary is not this
 * module's to define.
 */
public record StartIngestionRequest(String originalFilename, String contentType, String sourceSystem,
		String declaredSchemaName, java.util.List<ColumnRequest> columns, String asOfDate) {

	public StartIngestionRequest {
		originalFilename = requireText(originalFilename, "originalFilename");
		contentType = contentType == null ? "" : contentType;
		sourceSystem = sourceSystem == null ? "" : sourceSystem;
		declaredSchemaName = declaredSchemaName == null ? "" : declaredSchemaName;
		columns = columns == null ? java.util.List.of() : java.util.List.copyOf(columns);
		asOfDate = asOfDate == null ? "" : asOfDate;
	}

	private static String requireText(String value, String field) {
		Objects.requireNonNull(value, field + " must not be null");
		String trimmed = value.trim();
		if (trimmed.isEmpty()) {
			throw new IllegalArgumentException(field + " must not be blank");
		}
		return trimmed;
	}

	/**
	 * @return the caller's schema as a model value, ready for the validation gates
	 */
	public IngestionSchema toSchema() {
		return IngestionSchema.of(this.columns.stream().map(ColumnRequest::toColumnSchema).toList());
	}

	/**
	 * One expected column.
	 *
	 * <p>{@code type} is a {@link ColumnType} name rather than a raw string so a typo
	 * is refused at the boundary instead of silently becoming an untyped column that
	 * accepts anything.
	 *
	 * @param name     the column name as it appears in the file header
	 * @param type     the declared type name
	 * @param required whether a row may omit it
	 */
	public record ColumnRequest(String name, String type, boolean required) {

		public ColumnRequest {
			name = requireText(name, "column name");
			type = requireText(type, "column type");
		}

		public ColumnSchema toColumnSchema() {
			ColumnType declared;
			try {
				declared = ColumnType.valueOf(this.type.trim().toUpperCase(java.util.Locale.ROOT));
			}
			catch (IllegalArgumentException ex) {
				throw new IllegalArgumentException("unknown column type '" + this.type + "' for column " + this.name,
						ex);
			}
			return new ColumnSchema(this.name, declared, this.required);
		}

	}

}
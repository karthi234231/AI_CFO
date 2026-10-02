package com.fintech.cfo.ingestion.validator;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.fintech.cfo.ingestion.enums.IngestionErrorType;
import com.fintech.cfo.ingestion.enums.RejectionReason;
import com.fintech.cfo.ingestion.model.ColumnSchema;
import com.fintech.cfo.ingestion.model.IngestionSchema;
import com.fintech.cfo.ingestion.model.ParsedRow;
import com.fintech.cfo.ingestion.model.ValidationFinding;

/**
 * Checks that every column the schema marks required carries a value.
 *
 * <p>A missing required value is always an error, never a warning. A ledger row
 * without a date or an amount cannot be posted, and defaulting it to zero or to
 * today would invent a fact.
 *
 * <p>Runs before type checking so an absent value is reported as
 * {@code MISSING_REQUIRED_VALUE} rather than as a confusing parse failure of an
 * empty string.
 */
public final class RequiredFieldValidator {

	public List<ValidationFinding> validate(ParsedRow row, IngestionSchema schema) {
		Objects.requireNonNull(row, "row must not be null");
		Objects.requireNonNull(schema, "schema must not be null");
		List<ValidationFinding> findings = new ArrayList<>();
		for (ColumnSchema column : schema.columns()) {
			if (!column.required()) {
				continue;
			}
			if (row.text(column.name()).isBlank()) {
				findings.add(ValidationFinding.field(IngestionErrorType.REQUIRED_FIELD,
						RejectionReason.MISSING_REQUIRED_VALUE, row.coordinate(), column.name(),
						"the required column '" + column.name() + "' has no value in this row"));
			}
		}
		return findings;
	}

	/**
	 * @return true when any required column is blank, without building findings —
	 * used by callers that only need the yes/no answer
	 */
	public boolean hasMissingRequiredValue(ParsedRow row, IngestionSchema schema) {
		for (ColumnSchema column : schema.columns()) {
			if (column.required() && row.text(column.name()).isBlank()) {
				return true;
			}
		}
		return false;
	}

}
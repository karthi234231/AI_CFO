package com.fintech.cfo.ingestion.validator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.fintech.cfo.ingestion.enums.IngestionErrorType;
import com.fintech.cfo.ingestion.enums.RejectionReason;
import com.fintech.cfo.ingestion.model.FileParseResult;
import com.fintech.cfo.ingestion.model.IngestionSchema;
import com.fintech.cfo.ingestion.model.ValidationFinding;

/**
 * Checks the header of a parsed file against the expected schema.
 *
 * <p>All of these are file-level findings: if a required column is absent, every
 * row lacks it, so the whole upload is refused rather than rejecting each row
 * with the same message. That is the difference between "this export is wrong"
 * and "row 4001 is wrong", and an operator needs the first.
 *
 * <p>An empty schema means the caller has declared nothing to check, which is
 * legitimate for free-form CSVs; the validator then stays silent rather than
 * inventing expectations.
 */
public final class SchemaValidator {

	public List<ValidationFinding> validate(FileParseResult parseResult, IngestionSchema schema) {
		Objects.requireNonNull(parseResult, "parseResult must not be null");
		Objects.requireNonNull(schema, "schema must not be null");
		List<ValidationFinding> findings = new ArrayList<>();

		// WHY a repeated column is reported even for an empty schema, and why it is
		// read from the raw header rather than from columnNames(): an empty schema
		// means "the caller declared nothing", not "the file may be malformed"; and
		// HeaderDetector has already rewritten repeats to name_2 / name_3 so the
		// columns stay addressable, which means the normalised names can never
		// repeat. Checking those would make DUPLICATE_COLUMN unreachable, and an
		// export with two columns both called `amount` is precisely the mistake an
		// analyst has to be told about before posting against it.
		findings.addAll(duplicateColumns(parseResult));

		if (schema.isEmpty()) {
			return findings;
		}
		List<String> fileColumns = parseResult.columnNames();

		if (fileColumns.isEmpty()) {
			findings.add(ValidationFinding.file(IngestionErrorType.SCHEMA, RejectionReason.MISSING_HEADER,
					"the file has no header row and cannot be matched to the expected columns"));
			return findings;
		}

		for (String missing : schema.missingRequiredColumns(fileColumns)) {
			findings.add(ValidationFinding.file(IngestionErrorType.SCHEMA, RejectionReason.MISSING_REQUIRED_COLUMN,
					"the required column '" + missing + "' is not present in the file header"));
		}
		return findings;
	}

	/**
	 * One finding per repeated header cell, in the order the headers were read.
	 *
	 * <p>Blank cells are compared as the empty string and so collide with each
	 * other; they are skipped instead, because a header padded with empty cells is a
	 * layout artefact rather than a duplicated column name, and the positional
	 * {@code column_n} names those cells receive make them unambiguous anyway.
	 */
	private static List<ValidationFinding> duplicateColumns(FileParseResult parseResult) {
		List<ValidationFinding> findings = new ArrayList<>();
		for (List<String> header : parseResult.rawHeaders()) {
			Map<String, Integer> seen = new LinkedHashMap<>();
			for (String column : header) {
				String key = column == null ? "" : column.trim().toLowerCase(Locale.ROOT);
				if (key.isEmpty()) {
					continue;
				}
				Integer previous = seen.putIfAbsent(key, 1);
				if (previous != null) {
					findings.add(ValidationFinding.file(IngestionErrorType.SCHEMA, RejectionReason.DUPLICATE_COLUMN,
							"the column name '" + column + "' appears more than once in the file header"));
				}
			}
		}
		return findings;
	}

}
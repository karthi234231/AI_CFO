package com.fintech.cfo.ingestion.validator;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.fintech.cfo.ingestion.enums.ColumnType;
import com.fintech.cfo.ingestion.enums.IngestionErrorType;
import com.fintech.cfo.ingestion.enums.RejectionReason;
import com.fintech.cfo.ingestion.enums.ValidationSeverity;
import com.fintech.cfo.ingestion.model.ColumnSchema;
import com.fintech.cfo.ingestion.model.IngestionSchema;
import com.fintech.cfo.ingestion.model.IngestionLimits;
import com.fintech.cfo.ingestion.model.ParsedRow;
import com.fintech.cfo.ingestion.model.ValidationFinding;

/**
 * Business-reasonable checks that a value is well-typed but still implausible.
 *
 * <p>Type checking answers "can this be read as a date"; this answers "is this a
 * date the business can believe". The distinction matters for severity. A date
 * before the ledger epoch is an error: it cannot be posted. A date after the
 * reporting cut-off is a warning: forward-dated entries are real, but the analyst
 * should see them.
 *
 * <p>The cut-off is passed in rather than read from the clock. An import run must
 * be reproducible next month, and {@code LocalDate.now()} would silently change
 * the verdict (rule 2).
 */
public final class DataQualityValidator {

	/** No accounting entry predates double-entry bookkeeping in this domain. */
	public static final LocalDate EARLIEST_PLAUSIBLE_DATE = LocalDate.of(1900, 1, 1);

	public List<ValidationFinding> validate(ParsedRow row, IngestionSchema schema, LocalDate asOfDate,
			IngestionLimits limits) {
		Objects.requireNonNull(row, "row must not be null");
		Objects.requireNonNull(schema, "schema must not be null");
		Objects.requireNonNull(asOfDate, "asOfDate must not be null");
		Objects.requireNonNull(limits, "limits must not be null");
		List<ValidationFinding> findings = new ArrayList<>();
		for (ColumnSchema column : schema.columns()) {
			String value = row.text(column.name());
			if (value.isBlank()) {
				continue;
			}
			if (value.length() > limits.maxCellTextLength()) {
				findings.add(ValidationFinding.field(IngestionErrorType.DATA_QUALITY, RejectionReason.VALUE_TOO_LONG,
						row.coordinate(), column.name(),
						"the value is " + value.length() + " characters and the limit is "
								+ limits.maxCellTextLength()));
				continue;
			}
			if (column.type() == ColumnType.DATE || column.type() == ColumnType.DATETIME) {
				checkDate(row, column, value, asOfDate, findings);
			}
		}
		return findings;
	}

	private static void checkDate(ParsedRow row, ColumnSchema column, String value, LocalDate asOfDate,
			List<ValidationFinding> findings) {
		LocalDate date;
		try {
			date = column.type() == ColumnType.DATE ? TypedValueParser.date(value)
					: TypedValueParser.dateTime(value).toLocalDate();
		}
		catch (TypedValueParser.ValueFormatException ex) {
			return;
		}
		if (date.isBefore(EARLIEST_PLAUSIBLE_DATE)) {
			findings.add(ValidationFinding.field(IngestionErrorType.DATA_QUALITY, RejectionReason.VALUE_OUT_OF_RANGE,
					row.coordinate(), column.name(),
					"the date " + date + " is before the earliest plausible accounting date "
							+ EARLIEST_PLAUSIBLE_DATE));
			return;
		}
		if (date.isAfter(asOfDate)) {
			findings.add(ValidationFinding
					.field(IngestionErrorType.DATA_QUALITY, RejectionReason.VALUE_OUT_OF_RANGE, row.coordinate(),
							column.name(), "the date " + date + " is after the reporting cut-off " + asOfDate
									+ "; forward-dated entries are imported but flagged")
					.withSeverity(ValidationSeverity.WARNING));
		}
	}

}
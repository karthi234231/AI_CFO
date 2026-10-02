package com.fintech.cfo.ingestion.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.fintech.cfo.ingestion.enums.IngestionErrorType;
import com.fintech.cfo.ingestion.enums.RejectionReason;
import com.fintech.cfo.ingestion.enums.ValidationSeverity;
import com.fintech.cfo.ingestion.model.FileParseResult;
import com.fintech.cfo.ingestion.model.FileSecurityResult;
import com.fintech.cfo.ingestion.model.IngestionLimits;
import com.fintech.cfo.ingestion.model.IngestionSchema;
import com.fintech.cfo.ingestion.model.ParsedCell;
import com.fintech.cfo.ingestion.model.ParsedRow;
import com.fintech.cfo.ingestion.model.RejectedRow;
import com.fintech.cfo.ingestion.model.ValidationFinding;
import com.fintech.cfo.ingestion.model.ValidationResult;
import com.fintech.cfo.ingestion.security.FilenameSanitiser;
import com.fintech.cfo.ingestion.validator.DataQualityValidator;
import com.fintech.cfo.ingestion.validator.DataTypeValidator;
import com.fintech.cfo.ingestion.validator.DuplicateValidator;
import com.fintech.cfo.ingestion.validator.FormulaInjectionSanitiser;
import com.fintech.cfo.ingestion.validator.RequiredFieldValidator;
import com.fintech.cfo.ingestion.validator.SchemaValidator;
import com.fintech.cfo.ingestion.validator.UploadFileValidator;

/**
 * The validation facade: the one object the ingestion orchestrator talks to for
 * everything that happens between "bytes arrived" and "rows are trustworthy".
 *
 * <p>Three gates, in order, each cheaper than the next:
 *
 * <ol>
 * <li>{@link #validateFile} decides whether the bytes may be parsed at all. Until
 * it passes, no parser is created and no untrusted structure is walked.</li>
 * <li>{@link #validateSchema} checks the header against the expected columns.
 * A failure here is file-level and refuses the whole upload.</li>
 * <li>{@link #validateRows} sanitises each accepted row, checks required fields,
 * declared types and plausibility, then refuses duplicates. The row it accepts is
 * the sanitised row, never the raw one — which is why the sanitised rows travel
 * inside the {@link ValidationResult} instead of being recomputed.</li>
 * </ol>
 *
 * <p>Plain constructor injection, no Spring annotation: the class is pure enough
 * to be exercised directly, and keeping the framework out of it is what makes the
 * tests real rather than mock-shaped.
 */
public final class FileValidationService {

	private final FilenameSanitiser filenameSanitiser;

	private final UploadFileValidator fileValidator;

	private final SchemaValidator schemaValidator;

	private final RequiredFieldValidator requiredFieldValidator;

	private final DataTypeValidator dataTypeValidator;

	private final DataQualityValidator dataQualityValidator;

	private final FormulaInjectionSanitiser formulaSanitiser;

	public FileValidationService() {
		this(new FilenameSanitiser(), new UploadFileValidator(), new SchemaValidator(), new RequiredFieldValidator(),
				new DataTypeValidator(), new DataQualityValidator(), FormulaInjectionSanitiser.instance());
	}

	public FileValidationService(FilenameSanitiser filenameSanitiser, UploadFileValidator fileValidator,
			SchemaValidator schemaValidator, RequiredFieldValidator requiredFieldValidator,
			DataTypeValidator dataTypeValidator, DataQualityValidator dataQualityValidator,
			FormulaInjectionSanitiser formulaSanitiser) {
		this.filenameSanitiser = Objects.requireNonNull(filenameSanitiser, "filenameSanitiser must not be null");
		this.fileValidator = Objects.requireNonNull(fileValidator, "fileValidator must not be null");
		this.schemaValidator = Objects.requireNonNull(schemaValidator, "schemaValidator must not be null");
		this.requiredFieldValidator = Objects.requireNonNull(requiredFieldValidator,
				"requiredFieldValidator must not be null");
		this.dataTypeValidator = Objects.requireNonNull(dataTypeValidator, "dataTypeValidator must not be null");
		this.dataQualityValidator = Objects.requireNonNull(dataQualityValidator, "dataQualityValidator must not be null");
		this.formulaSanitiser = Objects.requireNonNull(formulaSanitiser, "formulaSanitiser must not be null");
	}

	/**
	 * Gate one: sanitises the filename and proves the bytes are a readable,
	 * non-executable, correctly-declared file.
	 */
	public FileSecurityResult validateFile(byte[] content, String filename, String contentType,
			IngestionLimits limits) {
		Objects.requireNonNull(limits, "limits must not be null");
		return this.fileValidator.validate(content, this.filenameSanitiser.sanitise(filename), contentType, limits);
	}

	public FileSecurityResult validateFile(byte[] content, String filename, String contentType) {
		return validateFile(content, filename, contentType, IngestionLimits.defaults());
	}

	/**
	 * Gate two: header versus schema. File-level findings only.
	 */
	public ValidationResult validateSchema(FileParseResult parseResult, IngestionSchema schema) {
		ValidationResult.Builder builder = ValidationResult.builder();
		builder.columns(parseResult.columnNames());
		builder.addAll(this.schemaValidator.validate(parseResult, schema));
		return builder.build();
	}

	/**
	 * Gate three: per-row sanitisation, required fields, declared types,
	 * plausibility and duplicates.
	 *
	 * <p>Parser findings and parser-level rejections are carried into the result
	 * first, so a caller that only reads this object sees the complete story
	 * rather than a sanitised subset.
	 */
	public ValidationResult validateRows(FileParseResult parseResult, IngestionSchema schema, LocalDate asOfDate,
			IngestionLimits limits) {
		Objects.requireNonNull(parseResult, "parseResult must not be null");
		Objects.requireNonNull(schema, "schema must not be null");
		Objects.requireNonNull(asOfDate, "asOfDate must not be null");
		Objects.requireNonNull(limits, "limits must not be null");

		ValidationResult.Builder builder = ValidationResult.builder();
		builder.columns(parseResult.columnNames());
		builder.addAll(parseResult.findings());
		builder.rejectAll(parseResult.rejectedRows());

		DuplicateValidator duplicates = DuplicateValidator.forSchema(schema);

		for (ParsedRow raw : parseResult.rows()) {
			SanitisedRow sanitised = sanitise(raw);
			builder.addAll(sanitised.findings());

			List<ValidationFinding> rowFindings = new ArrayList<>();
			rowFindings.addAll(this.requiredFieldValidator.validate(sanitised.row(), schema));
			rowFindings.addAll(this.dataTypeValidator.validate(sanitised.row(), schema));
			rowFindings.addAll(this.dataQualityValidator.validate(sanitised.row(), schema, asOfDate, limits));
			builder.addAll(rowFindings);

			Optional<ValidationFinding> blocking = rowFindings.stream().filter(ValidationFinding::isError).findFirst();
			if (blocking.isPresent()) {
				ValidationFinding failure = blocking.get();
				builder.reject(rejected(sanitised.row(), failure));
				continue;
			}
			if (isEntirelyBlank(sanitised.row())) {
				builder.reject(RejectedRow.of(sanitised.row().coordinate(), RejectionReason.BLANK_ROW,
						"the row carries no values in any column"));
				continue;
			}
			Optional<RejectedRow> duplicate = duplicates.check(sanitised.row());
			if (duplicate.isPresent()) {
				builder.reject(duplicate.get());
				continue;
			}
			builder.accept(sanitised.row());
		}
		return builder.build();
	}

	public ValidationResult validateRows(FileParseResult parseResult, IngestionSchema schema, LocalDate asOfDate) {
		return validateRows(parseResult, schema, asOfDate, IngestionLimits.defaults());
	}

	/**
	 * Convenience for the common path: schema gate followed by the row gate.
	 * A file-level schema failure is reported, and the rows are still validated so
	 * the analyst sees everything wrong in one pass rather than one problem per
	 * retry.
	 */
	public ValidationResult validate(FileParseResult parseResult, IngestionSchema schema, LocalDate asOfDate,
			IngestionLimits limits) {
		return validateSchema(parseResult, schema).merge(validateRows(parseResult, schema, asOfDate, limits));
	}

	public ValidationResult validate(FileParseResult parseResult, IngestionSchema schema, LocalDate asOfDate) {
		return validate(parseResult, schema, asOfDate, IngestionLimits.defaults());
	}

	private SanitisedRow sanitise(ParsedRow row) {
		List<ParsedCell> cells = new ArrayList<>(row.cells().size());
		List<ValidationFinding> findings = new ArrayList<>();
		for (ParsedCell cell : row.cells()) {
			FormulaInjectionSanitiser.Result text = this.formulaSanitiser.sanitise(cell.text());
			FormulaInjectionSanitiser.Result raw = this.formulaSanitiser.sanitise(cell.rawText());
			cells.add(new ParsedCell(cell.columnName(), cell.columnIndex(), cell.type(), text.text(), raw.text()));
			if (text.neutralised() || raw.neutralised()) {
				findings.add(ValidationFinding
						.field(IngestionErrorType.FORMULA_INJECTION, RejectionReason.FORMULA_INJECTION_NEUTRALISED,
								row.coordinate(), cell.columnName(),
								"the value began with a spreadsheet formula trigger and was escaped with a leading "
										+ FormulaInjectionSanitiser.ESCAPE)
						.withSeverity(ValidationSeverity.WARNING));
			}
			if (text.removedControlCharacters() || raw.removedControlCharacters()) {
				findings.add(ValidationFinding
						.field(IngestionErrorType.DATA_QUALITY, RejectionReason.INVALID_FIELD_FORMAT, row.coordinate(),
								cell.columnName(),
								"invisible bidirectional or format characters were removed from the value")
						.withSeverity(ValidationSeverity.WARNING));
			}
		}
		return new SanitisedRow(ParsedRow.of(row.coordinate(), row.fileType(), cells), findings);
	}

	private static RejectedRow rejected(ParsedRow row, ValidationFinding failure) {
		if (failure.isColumnScoped()) {
			return RejectedRow.ofField(row.coordinate(), failure.columnName(), failure.reason(), failure.message(),
					row.rawText(failure.columnName()));
		}
		return RejectedRow.of(row.coordinate(), failure.reason(), failure.message());
	}

	private static boolean isEntirelyBlank(ParsedRow row) {
		return row.cells().stream().allMatch(ParsedCell::isBlank);
	}

	private record SanitisedRow(ParsedRow row, List<ValidationFinding> findings) {
	}

}
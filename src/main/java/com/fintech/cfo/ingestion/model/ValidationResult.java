package com.fintech.cfo.ingestion.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.fintech.cfo.ingestion.enums.IngestionErrorType;
import com.fintech.cfo.ingestion.enums.RejectionReason;
import com.fintech.cfo.ingestion.enums.ValidationSeverity;

/**
 * Accumulated validation findings plus the rows that survived them.
 *
 * <p>Holds findings and accepted rows together on purpose: sanitising a value
 * (see {@code FormulaInjectionSanitiser}) changes the row, and a result object
 * that reported only findings would force the caller to re-derive the mutated
 * rows and risk using the un-neutralised ones.
 */
public final class ValidationResult {

	/** All findings in encounter order; the basis of every severity query below. */
	private final List<ValidationFinding> findings;

	/** Rows that passed every rule, already mutated by any sanitisation. */
	private final List<ParsedRow> acceptedRows;

	/** Rows refused at any stage, each carrying the reason it was refused. */
	private final List<RejectedRow> rejectedRows;

	/** Column names seen in the source, deduplicated and order-preserving. */
	private final List<String> columnNames;

	/**
	 * Private constructor, reached only from the {@link Builder}.
	 *
	 * <p>Each list is copied with {@link List#copyOf}, which both makes the result
	 * genuinely immutable and produces a list that rejects null elements. That is
	 * the guarantee callers depend on: a {@code ValidationResult} handed to a
	 * controller cannot have its contents altered by a later stage of the pipeline,
	 * so the accepted-row count a report shows is the count that was actually
	 * validated.
	 */
	private ValidationResult(Builder builder) {
		this.findings = List.copyOf(builder.findings);
		this.acceptedRows = List.copyOf(builder.acceptedRows);
		this.rejectedRows = List.copyOf(builder.rejectedRows);
		this.columnNames = List.copyOf(builder.columnNames);
	}

	/** Entry point for the builder. */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * A result with no findings and no rows — the starting point when a file turns
	 * out to have nothing in it, or when a stage is skipped entirely. Returned
	 * rather than left null so downstream code needs no null handling.
	 */
	public static ValidationResult empty() {
		return new Builder().build();
	}

	/** Every finding, in the order the rules encountered them. */
	public List<ValidationFinding> findings() {
		return this.findings;
	}

	/**
	 * Rows that passed. The important subtlety is that these are the <i>mutated</i>
	 * rows: if a rule neutralised a formula-injection prefix, the sanitised value
	 * is what is held here, not the original text. Callers must persist these
	 * rather than re-reading the source file, or they would resurrect the
	 * un-neutralised value.
	 */
	public List<ParsedRow> acceptedRows() {
		return this.acceptedRows;
	}

	/** Refused rows, from parsing and validation alike. */
	public List<RejectedRow> rejectedRows() {
		return this.rejectedRows;
	}

	/**
	 * Source column names, deduplicated. Retained so error messages can name the
	 * offending column and so downstream mapping can report which expected column
	 * was absent.
	 */
	public List<String> columnNames() {
		return this.columnNames;
	}

	/**
	 * Findings of one severity. Single-pass stream filter; used by
	 * {@link #errors()} and {@link #warnings()} so severity filtering is defined
	 * in one place.
	 */
	public List<ValidationFinding> findingsOf(ValidationSeverity severity) {
		return this.findings.stream().filter(finding -> finding.severity() == severity).toList();
	}

	/** Error-severity findings — the ones that invalidate a run. */
	public List<ValidationFinding> errors() {
		return findingsOf(ValidationSeverity.ERROR);
	}

	/** Warning-severity findings — recorded but non-blocking. */
	public List<ValidationFinding> warnings() {
		return findingsOf(ValidationSeverity.WARNING);
	}

	/**
	 * Whether any finding is error-severity. This is the single boolean the
	 * ingestion service checks to decide whether a file may be persisted, so it is
	 * worth stating plainly: any error, anywhere, blocks the file.
	 */
	public boolean hasErrors() {
		return this.findings.stream().anyMatch(ValidationFinding::isError);
	}

	/** Whether any warning was raised. Derived from the filtered list for consistency. */
	public boolean hasWarnings() {
		return !warnings().isEmpty();
	}

	/**
	 * The first error in encounter order.
	 *
	 * <p>Order matters rather than severity ranking: the first error is the one
	 * that most likely explains the rest, so it is the right thing to show a user
	 * first. A file-level error such as a missing header column will precede the
	 * per-row errors it caused, which is exactly the ordering a user needs.
	 */
	public Optional<ValidationFinding> firstError() {
		return this.findings.stream().filter(ValidationFinding::isError).findFirst();
	}

	/**
	 * Whether the whole file must be refused, as opposed to only some rows.
	 *
	 * <p>Derived from the <i>first</i> error being file-level, and it works because
	 * of the ordering property just described: a schema or header failure is
	 * recorded before the row errors it causes. Using {@code anyMatch} on
	 * file-level alone would be wrong in the opposite direction, since a row-level
	 * error can follow a merely suspicious file-level one.
	 */
	public boolean rejectsFile() {
		return firstError().map(ValidationFinding::isFileLevel).orElse(false);
	}

	/** Rejected rows sharing one reason, for per-reason reporting and metrics. */
	public List<RejectedRow> rejectedRowsWith(RejectionReason reason) {
		return this.rejectedRows.stream().filter(row -> row.reason() == reason).toList();
	}

	/**
	 * Merges another result into this one without losing ordering. Findings are
	 * concatenated in encounter order because rule 3 forbids hash-ordered
	 * iteration influencing any downstream decision.
	 */
	public ValidationResult merge(ValidationResult other) {
		Builder builder = new Builder();
		builder.findings.addAll(this.findings);
		builder.findings.addAll(other.findings);
		builder.acceptedRows.addAll(this.acceptedRows);
		builder.acceptedRows.addAll(other.acceptedRows);
		builder.rejectedRows.addAll(this.rejectedRows);
		builder.rejectedRows.addAll(other.rejectedRows);
		builder.columnNames.addAll(this.columnNames);
		for (String column : other.columnNames) {
			if (!builder.columnNames.contains(column)) {
				builder.columnNames.add(column);
			}
		}
		return builder.build();
	}

	/**
	 * Counts only, never the row contents or the finding text.
	 *
	 * <p>These lists can hold every row of a large upload, so a
	 * {@code toString} that included them would flood the log with customer
	 * financial data on every accidental print. The three counts are enough to
	 * diagnose a bad import; the detail is available through the explicit
	 * accessors when a human actually needs it.
	 */
	@Override
	public String toString() {
		return "ValidationResult[accepted=" + this.acceptedRows.size() + ", rejected=" + this.rejectedRows.size()
				+ ", findings=" + this.findings.size() + "]";
	}

	/** Mutable accumulator; the result itself is immutable once built. */
	public static final class Builder {

		private final List<ValidationFinding> findings = new ArrayList<>();
		private final List<ParsedRow> acceptedRows = new ArrayList<>();
		private final List<RejectedRow> rejectedRows = new ArrayList<>();
		private final List<String> columnNames = new ArrayList<>();

		private Builder() {
		}

		/**
		 * Adds one finding, tolerating null.
		 *
		 * <p>Null-tolerance is deliberate: findings commonly come from optional
		 * lookups, and a missing annotation should not fail an ingestion run.
		 * Contrast with {@code accept} and {@code reject}, which reject null — a
		 * null row is a programming error, whereas a null finding is merely absent.
		 */
		public Builder add(ValidationFinding finding) {
			if (finding != null) {
				this.findings.add(finding);
			}
			return this;
		}

		/** Adds several findings, preserving order. */
		public Builder addAll(List<ValidationFinding> newFindings) {
			Objects.requireNonNull(newFindings, "newFindings must not be null");
			newFindings.forEach(this::add);
			return this;
		}

		/**
		 * Records a surviving row. Null is rejected because a null in the accepted
		 * list would silently inflate the accepted-row count a report publishes.
		 */
		public Builder accept(ParsedRow row) {
			this.acceptedRows.add(Objects.requireNonNull(row, "row must not be null"));
			return this;
		}

		/** Adds several surviving rows. */
		public Builder acceptAll(List<ParsedRow> rows) {
			Objects.requireNonNull(rows, "rows must not be null");
			rows.forEach(this::accept);
			return this;
		}

		/**
		 * Records a rejection and derives its finding in one step so the two can
		 * never drift apart — a rejected row with no matching finding would let a
		 * caller report a clean run.
		 *
		 * <p><b>Why the finding is derived here.</b> The rejection reason and the
		 * finding are two views of one fact. If they were recorded separately, some
		 * path would eventually record a rejection without its finding, and the
		 * report would show rows refused with no explanation and no error — a
		 * silently degraded import that looks successful.
		 *
		 * <p><b>Why the reason-to-error-type mapping lives here.</b> A
		 * {@link RejectionReason} describes what happened; an
		 * {@link IngestionErrorType} describes how a consumer should classify it.
		 * Mapping them in this one place keeps the taxonomy consistent across the
		 * parse stage and the validation stage. The groupings are: duplicates on
		 * their own; missing required values under a required-field type; the four
		 * structural failures under a schema type, since all mean the file's shape is
		 * wrong rather than any one value; neutralised formulas under their own type
		 * because the row was kept and only the value was rewritten; the five
		 * parse-level failures under a parse type; and everything else falling
		 * through to a data-type default rather than to an exception, so a new
		 * reason added later still produces a usable finding.
		 *
		 * <p><b>Field- versus row-scoped.</b> A row that names a column produces a
		 * field-scoped finding, which lets a client highlight the exact cell; a
		 * whole-row failure produces a row-scoped one. Choosing correctly is what
		 * makes the error actionable rather than merely present.
		 *
		 * <p>A row with no coordinate — a file-level rejection — adds no finding
		 * here, because the caller records that finding at file scope where the
		 * whole file's status is decided.
		 */
		public Builder reject(RejectedRow row) {
			Objects.requireNonNull(row, "row must not be null");
			this.rejectedRows.add(row);
			if (row.coordinate() == null) {
				return this;
			}
			IngestionErrorType errorType = switch (row.reason()) {
				case DUPLICATE_ROW -> IngestionErrorType.DUPLICATE;
				case MISSING_REQUIRED_VALUE -> IngestionErrorType.REQUIRED_FIELD;
				case MISSING_HEADER, MISSING_REQUIRED_COLUMN, DUPLICATE_COLUMN -> IngestionErrorType.SCHEMA;
				case FORMULA_INJECTION_NEUTRALISED -> IngestionErrorType.FORMULA_INJECTION;
				case FIELD_COUNT_MISMATCH, UNREADABLE_CELL, TRUNCATED_RECORD, ROW_READ_FAILED,
						FORMULA_WITHOUT_CACHED_RESULT -> IngestionErrorType.PARSE;
				default -> IngestionErrorType.DATA_TYPE;
			};
			ValidationFinding finding = row.isColumnScoped()
					? ValidationFinding.field(errorType, row.reason(), row.coordinate(), row.columnName(), row.detail())
					: ValidationFinding.row(errorType, row.reason(), row.coordinate(), row.detail());
			return add(finding);
		}

		/** Adds several rejected rows, deriving each one's finding. */
		public Builder rejectAll(List<RejectedRow> rows) {
			Objects.requireNonNull(rows, "rows must not be null");
			rows.forEach(this::reject);
			return this;
		}

		/**
		 * Records the source column names. Appends rather than replaces, so stages
		 * that each contribute a column set accumulate correctly;
		 * {@link ValidationResult#merge} is what ultimately deduplicates.
		 */
		public Builder columns(List<String> names) {
			Objects.requireNonNull(names, "names must not be null");
			this.columnNames.addAll(names);
			return this;
		}

		/**
		 * Freezes the accumulated state into an immutable result. After this the
		 * builder may still be reused to build another result, but the returned
		 * object cannot be changed.
		 */
		public ValidationResult build() {
			return new ValidationResult(this);
		}

	}

}
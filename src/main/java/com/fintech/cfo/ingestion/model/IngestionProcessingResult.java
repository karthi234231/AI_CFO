package com.fintech.cfo.ingestion.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.fintech.cfo.ingestion.enums.IngestionStage;
import com.fintech.cfo.ingestion.enums.IngestionStatus;
import com.fintech.cfo.ingestion.enums.ParseStatus;
import com.fintech.cfo.shared.domain.OrganizationId;

/**
 * Terminal output of the in-memory ingestion flow: parse, then validate, then
 * count. No persistence, no network — everything downstream of an upload can be
 * derived from this object alone, which is what makes the whole pipeline testable
 * without a database.
 *
 * <p>{@code acceptedRows} are the sanitised rows. {@code rejectedRows} covers
 * every row refused anywhere in the flow, parse stage and validation stage alike,
 * so {@code acceptedRows.size() + rejectedRows.size() + skippedRows} is the full
 * accounting for the file and anything less is a row that went missing.
 */
public final class IngestionProcessingResult {

	/** Identity of the run, absent when the result is used standalone in a test. */
	private final UUID ingestionRunId;

	/** Owning tenant; every downstream write is scoped by this. */
	private final OrganizationId organizationId;

	/** Storage key of the uploaded file. */
	private final UUID sourceFileId;

	/** Overall run outcome. */
	private final IngestionStatus status;

	/** How far the run progressed before it finished or stopped. */
	private final IngestionStage finalStage;

	/** Outcome of the parse phase specifically. */
	private final ParseStatus parseStatus;

	/** Sanitised name, size, checksum and type of the upload. */
	private final UploadMetadata file;

	/** Rows that survived parsing and validation, already sanitised. */
	private final List<ParsedRow> acceptedRows;

	/** Rows refused at any stage, parse and validation alike. */
	private final List<RejectedRow> rejectedRows;

	/** Every finding raised across the run, in encounter order. */
	private final List<ValidationFinding> findings;

	/**
	 * Rows deliberately not processed, e.g. beyond a configured cap.
	 *
	 * <p>Tracked separately rather than lumped in with rejections because the two
	 * mean different things: a rejected row was examined and refused, a skipped row
	 * was never examined. Conflating them would hide a truncation.
	 */
	private final int skippedRows;

	/** Why the run stopped, or empty when it did not stop early. */
	private final String failureReason;

	/**
	 * Private constructor: build through the {@link Builder}.
	 *
	 * <p>The three status fields are mandatory because a result with no verdict is
	 * unusable, while the identifier and metadata fields are optional because this
	 * type is also constructed by unit tests that do not touch the database. The
	 * three lists are defensively copied into immutable form, and a null failure
	 * reason is normalised to the empty string so the "why did this stop" question
	 * never has to be answered with a null check.
	 */
	private IngestionProcessingResult(Builder builder) {
		this.ingestionRunId = builder.ingestionRunId;
		this.organizationId = builder.organizationId;
		this.sourceFileId = builder.sourceFileId;
		this.status = Objects.requireNonNull(builder.status, "status must not be null");
		this.finalStage = Objects.requireNonNull(builder.finalStage, "finalStage must not be null");
		this.parseStatus = Objects.requireNonNull(builder.parseStatus, "parseStatus must not be null");
		this.file = builder.file;
		this.acceptedRows = List.copyOf(builder.acceptedRows);
		this.rejectedRows = List.copyOf(builder.rejectedRows);
		this.findings = List.copyOf(builder.findings);
		this.skippedRows = builder.skippedRows;
		this.failureReason = builder.failureReason == null ? "" : builder.failureReason;
	}

	/** Entry point for the builder. */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Run ID, as an {@link Optional} because an in-memory test run has none. Making
	 * it explicit at the call site forces a decision about what to do when there is
	 * nothing to persist against.
	 */
	public Optional<UUID> ingestionRunId() {
		return Optional.ofNullable(this.ingestionRunId);
	}

	/** Owning tenant, optional for the same reason as {@link #ingestionRunId()}. */
	public Optional<OrganizationId> organizationId() {
		return Optional.ofNullable(this.organizationId);
	}

	/** Storage key of the source file, optional in unit-test construction. */
	public Optional<UUID> sourceFileId() {
		return Optional.ofNullable(this.sourceFileId);
	}

	/** Overall outcome. Prefer {@link #isSuccess()} for a yes/no question. */
	public IngestionStatus status() {
		return this.status;
	}

	/** The stage reached, which is what tells an operator where a run stopped. */
	public IngestionStage finalStage() {
		return this.finalStage;
	}

	/** The parse-phase outcome in isolation from the overall run status. */
	public ParseStatus parseStatus() {
		return this.parseStatus;
	}

	/** Upload metadata; absent only when nothing was ever received. */
	public Optional<UploadMetadata> file() {
		return Optional.ofNullable(this.file);
	}

	/** Surviving rows. Already sanitised, so safe to persist as-is. */
	public List<ParsedRow> acceptedRows() {
		return this.acceptedRows;
	}

	/** Refused rows from every stage, each carrying its own reason. */
	public List<RejectedRow> rejectedRows() {
		return this.rejectedRows;
	}

	/** All findings, immutable and in encounter order. */
	public List<ValidationFinding> findings() {
		return this.findings;
	}

	/** Rows never examined; see the field note on {@code skippedRows}. */
	public int skippedRows() {
		return this.skippedRows;
	}

	/** @return why the run stopped, empty when nothing went wrong */
	public String failureReason() {
		return this.failureReason;
	}

	/** Accepted count; a convenience over {@link #acceptedRows()}{@code .size()}. */
	public int acceptedRowCount() {
		return this.acceptedRows.size();
	}

	/** Rejected count. */
	public int rejectedRowCount() {
		return this.rejectedRows.size();
	}

	/**
	 * The full accounting for the file: accepted, rejected and skipped together.
	 *
	 * <p>This identity is the point of tracking {@code skippedRows} separately. If
	 * the three do not sum to the number of rows the file was known to contain,
	 * then rows have gone missing somewhere in the pipeline — which is exactly the
	 * class of silent data loss that a CFO cannot be allowed to discover at
	 * reporting time rather than at ingestion time.
	 */
	public int totalRowCount() {
		return this.acceptedRows.size() + this.rejectedRows.size() + this.skippedRows;
	}

	/**
	 * True for any non-failure status, including a run that completed with
	 * rejections. Those are a partial success worth proceeding from, so callers
	 * must consult {@link #rejectedRowCount()} or the findings as well rather than
	 * treating this boolean as a full description of the run.
	 */
	public boolean isSuccess() {
		return !this.status.isFailure();
	}

	/**
	 * The first error-severity finding. The one to show the user, since errors are
	 * what stop a run while warnings merely annotate it.
	 */
	public Optional<ValidationFinding> firstError() {
		return this.findings.stream().filter(ValidationFinding::isError).findFirst();
	}

	/**
	 * Compact one-line summary for logs. Includes the failure reason only when
	 * there is one, so a healthy run does not carry an empty field.
	 */
	@Override
	public String toString() {
		return "IngestionProcessingResult[" + this.status + "/" + this.parseStatus + " accepted="
				+ this.acceptedRows.size() + " rejected=" + this.rejectedRows.size() + " skipped=" + this.skippedRows
				+ (this.failureReason.isEmpty() ? "" : " reason=" + this.failureReason) + "]";
	}

	/** Mutable accumulator; the built result is immutable. */
	public static final class Builder {

		/** Run identity; null in test-built standalone results. */
		private UUID ingestionRunId;

		/** Owning tenant; null in test-built standalone results. */
		private OrganizationId organizationId;

		/** Storage key of the uploaded file; null in test-built results. */
		private UUID sourceFileId;

		/** Set by an explicit failure; a null means PENDING, the pre-run state. */
		private IngestionStatus status = IngestionStatus.PENDING;

		/** UPLOAD is the earliest possible stage, so it is the safe default. */
		private IngestionStage finalStage = IngestionStage.UPLOAD;

		/** EMPTY until the parser has actually run and said otherwise. */
		private ParseStatus parseStatus = ParseStatus.EMPTY;

		/** Optional upload metadata, absent in test-built results. */
		private UploadMetadata file;

		/** Accumulated lists; each is copied into the result at build time. */
		private final List<ParsedRow> acceptedRows = new ArrayList<>();
		private final List<RejectedRow> rejectedRows = new ArrayList<>();
		private final List<ValidationFinding> findings = new ArrayList<>();

		/** Accumulated rather than assigned, so repeated calls sum. */
		private int skippedRows;

		/** Empty string rather than null, matching the result's normalisation. */
		private String failureReason = "";

		/** Private: the only way in is {@link IngestionProcessingResult#builder()}. */
		private Builder() {
		}

		/**
		 * Sets the three identifiers in one call. Grouped because they are always
		 * known together at the point a real run is registered, and splitting them
		 * into three calls would allow a partially-identified result to be built.
		 */
		public Builder identifiers(UUID runId, OrganizationId orgId, UUID fileId) {
			this.ingestionRunId = runId;
			this.organizationId = orgId;
			this.sourceFileId = fileId;
			return this;
		}

		/** Records the upload's sanitised metadata. */
		public Builder file(UploadMetadata metadata) {
			this.file = metadata;
			return this;
		}

		/** Explicit status; {@code null} is rejected so a status is never absent. */
		public Builder status(IngestionStatus newStatus) {
			this.status = Objects.requireNonNull(newStatus, "newStatus must not be null");
			return this;
		}

		/** How far the run got. */
		public Builder finalStage(IngestionStage stage) {
			this.finalStage = Objects.requireNonNull(stage, "stage must not be null");
			return this;
		}

		/** The parse-phase verdict. */
		public Builder parseStatus(ParseStatus newStatus) {
			this.parseStatus = Objects.requireNonNull(newStatus, "newStatus must not be null");
			return this;
		}

		/** Adds surviving rows in bulk; null is rejected rather than ignored. */
		public Builder acceptAll(List<ParsedRow> rows) {
			Objects.requireNonNull(rows, "rows must not be null");
			this.acceptedRows.addAll(rows);
			return this;
		}

		/** Adds refused rows in bulk. */
		public Builder rejectAll(List<RejectedRow> rows) {
			Objects.requireNonNull(rows, "rows must not be null");
			this.rejectedRows.addAll(rows);
			return this;
		}

		/**
		 * Adds one finding, tolerating null. Null-tolerance here is deliberate
		 * because findings are frequently the result of a lookup that may not have
		 * matched, and silently dropping one is preferable to failing an ingestion
		 * run over a missing optional annotation.
		 */
		public Builder finding(ValidationFinding finding) {
			if (finding != null) {
				this.findings.add(finding);
			}
			return this;
		}

		/** Adds several findings, preserving their order. */
		public Builder findings(List<ValidationFinding> newFindings) {
			Objects.requireNonNull(newFindings, "newFindings must not be null");
			this.findings.addAll(newFindings);
			return this;
		}

		/** Adds to the skipped count, so several stages can each contribute. */
		public Builder skipRows(int count) {
			this.skippedRows += count;
			return this;
		}

		/** Records why the run stopped early. */
		public Builder failureReason(String reason) {
			this.failureReason = reason;
			return this;
		}

		/**
		 * Derives the final status from the evidence rather than letting each
		 * caller decide. A caller who could report {@code COMPLETED} over a run
		 * with rejections would be able to hide a bad export.
		 *
		 * <p>The decision is made in {@code build()} rather than in each stage
		 * because only here are all three outcomes known at once. Guarding on
		 * {@code PENDING}/{@code RUNNING} means an explicitly set status such as
		 * {@code FAILED} is respected instead of being overwritten by the row
		 * counts — a run that failed must not be reclassified as merely completed.
		 *
		 * <p>Rejections are checked before acceptance because a file can contain
		 * both, and {@code COMPLETED_WITH_REJECTIONS} is the more actionable
		 * verdict: it tells the operator their export was partly unusable. A file
		 * with no accepted and no rejected rows stays {@code PENDING} rather than
		 * being reported as completed, because nothing was actually ingested.
		 */
		public IngestionProcessingResult build() {
			if (this.status == IngestionStatus.PENDING || this.status == IngestionStatus.RUNNING) {
				if (!this.rejectedRows.isEmpty()) {
					this.status = IngestionStatus.COMPLETED_WITH_REJECTIONS;
				}
				else if (!this.acceptedRows.isEmpty()) {
					this.status = IngestionStatus.COMPLETED;
				}
			}
			return new IngestionProcessingResult(this);
		}

	}

}
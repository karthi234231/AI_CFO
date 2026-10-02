package com.fintech.cfo.ingestion.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.fintech.cfo.ingestion.enums.FileType;
import com.fintech.cfo.ingestion.enums.IngestionErrorType;
import com.fintech.cfo.ingestion.enums.ParseStatus;
import com.fintech.cfo.ingestion.enums.RejectionReason;

/**
 * Everything one parser produced for one file: the rows it could read, the rows
 * it refused and why, and whether it saw the whole file.
 *
 * <p>The three counts are kept apart on purpose. {@code skippedRows} covers
 * positions that genuinely carried no data — blank lines, styled-but-empty
 * spreadsheet rows, preamble lines above the header. They are counted and, where
 * they hide something (a preamble), reported as findings, because a run that
 * quietly ignored 400 blank rows is a run whose row numbering nobody can
 * reproduce.
 *
 * <p>Final class with a builder: eleven consistent components is already too many
 * for a record constructor to stay readable, and the interesting part here is the
 * derived behaviour.
 */
public final class FileParseResult {

	private final FileType fileType;
	private final List<ParsedRow> rows;
	private final List<RejectedRow> rejectedRows;
	private final List<ValidationFinding> findings;
	private final Map<String, List<ParsedRow>> rowsBySheet;
	private final List<String> sheetNames;
	private final List<String> columnNames;
	private final List<List<String>> rawHeaders;
	private final int headerRowNumber;
	private final int skippedRows;
	private final ParseStatus status;
	private final String failureReason;

	private FileParseResult(Builder builder) {
		this.fileType = builder.fileType;
		this.rows = List.copyOf(builder.rows);
		this.rejectedRows = List.copyOf(builder.rejectedRows);
		this.findings = List.copyOf(builder.findings);
		this.sheetNames = List.copyOf(builder.sheetNames);
		this.columnNames = List.copyOf(builder.columnNames);
		this.rawHeaders = builder.rawHeaders.stream().map(List::copyOf).toList();
		this.rowsBySheet = Map.copyOf(builder.rowsBySheet);
		this.headerRowNumber = builder.headerRowNumber;
		this.skippedRows = builder.skippedRows;
		this.status = builder.status;
		this.failureReason = builder.failureReason;
	}

	public static Builder builder(FileType fileType) {
		return new Builder(fileType);
	}

	/**
	 * Terminal failure with nothing usable. Used for corrupt, encrypted, empty and
	 * unsupported files, so the reason is always stated rather than inferred from
	 * an empty row list.
	 */
	public static FileParseResult failed(FileType fileType, RejectionReason reason, String detail) {
		return new Builder(fileType).status(ParseStatus.FAILED).failureReason(reason + ": " + detail)
				.add(ValidationFinding.file(IngestionErrorType.PARSE, reason, detail))
				.build();
	}

	public FileType fileType() {
		return this.fileType;
	}

	public List<ParsedRow> rows() {
		return this.rows;
	}

	public List<RejectedRow> rejectedRows() {
		return this.rejectedRows;
	}

	public List<ValidationFinding> findings() {
		return this.findings;
	}

	public Map<String, List<ParsedRow>> rowsBySheet() {
		return this.rowsBySheet;
	}

	public List<String> sheetNames() {
		return this.sheetNames;
	}

	public int sheetCount() {
		return this.sheetNames.size();
	}

	public List<String> columnNames() {
		return this.columnNames;
	}

	/**
	 * The header rows exactly as they appeared in the file, one entry per sheet that
	 * produced a header.
	 *
	 * <p><b>Why this is kept separately from {@link #columnNames()}.</b>
	 * {@code columnNames} is the <i>addressable</i> form: {@code HeaderDetector}
	 * rewrites a repeated label to {@code name_2} / {@code name_3} so that every
	 * column can still be named, and this builder drops exact repeats on top of
	 * that. Both steps are correct for reading and both are lossy for auditing: once
	 * they have run, the evidence that the export repeated a column no longer exists
	 * anywhere in the result, so {@code RejectionReason.DUPLICATE_COLUMN} could never
	 * be reported and a file with two columns called {@code amount} would be
	 * indistinguishable from one that had been fixed. The untouched cells are the
	 * only place that fact survives, which is what lets the schema gate tell the
	 * analyst their export is wrong.
	 *
	 * <p>Empty for a file read with no header ({@code HeaderMode.NONE}) or for one
	 * that failed before a header could be located.
	 */
	public List<List<String>> rawHeaders() {
		return this.rawHeaders;
	}

	/**
	 * @return 1-based row number of the header, or {@code -1} when the file was
	 * read without a header and generated column names are in use
	 */
	public int headerRowNumber() {
		return this.headerRowNumber;
	}

	public int skippedRows() {
		return this.skippedRows;
	}

	public int acceptedRowCount() {
		return this.rows.size();
	}

	public int rejectedRowCount() {
		return this.rejectedRows.size();
	}

	/**
	 * @return rows read plus rows refused plus positions skipped; this is the
	 * denominator an operator should reconcile a file against
	 */
	public int totalRowCount() {
		return this.rows.size() + this.rejectedRows.size() + this.skippedRows;
	}

	public ParseStatus status() {
		return this.status;
	}

	public String failureReason() {
		return this.failureReason;
	}

	public boolean isUsable() {
		return this.status == ParseStatus.SUCCESS || this.status == ParseStatus.PARTIAL;
	}

	public boolean isComplete() {
		return this.status == ParseStatus.SUCCESS || this.status == ParseStatus.EMPTY;
	}

	public Optional<RejectedRow> firstRejectedRow() {
		return this.rejectedRows.stream().findFirst();
	}

	@Override
	public String toString() {
		return "FileParseResult[" + this.fileType + " " + this.status + " accepted=" + this.rows.size()
				+ " rejected=" + this.rejectedRows.size() + " skipped=" + this.skippedRows
				+ (this.failureReason == null || this.failureReason.isEmpty() ? "" : " reason=" + this.failureReason)
				+ "]";
	}

	/** Mutable accumulator; the built result is immutable. */
	public static final class Builder {

		private final FileType fileType;
		private final List<ParsedRow> rows = new ArrayList<>();
		private final List<RejectedRow> rejectedRows = new ArrayList<>();
		private final List<ValidationFinding> findings = new ArrayList<>();
		private final Map<String, List<ParsedRow>> rowsBySheet = new LinkedHashMap<>();
		private final List<String> sheetNames = new ArrayList<>();
		private final List<String> columnNames = new ArrayList<>();
		private final List<List<String>> rawHeaders = new ArrayList<>();
		private int headerRowNumber = -1;
		private int skippedRows;
		private ParseStatus status = ParseStatus.SUCCESS;
		private String failureReason = "";

		private Builder(FileType fileType) {
			this.fileType = Objects.requireNonNull(fileType, "fileType must not be null");
		}

		public Builder add(ParsedRow row) {
			Objects.requireNonNull(row, "row must not be null");
			this.rows.add(row);
			String sheet = row.coordinate().sheetName();
			this.rowsBySheet.computeIfAbsent(sheet, key -> new ArrayList<>()).add(row);
			return this;
		}

		public Builder reject(RejectedRow row) {
			this.rejectedRows.add(Objects.requireNonNull(row, "row must not be null"));
			return this;
		}

		public Builder add(ValidationFinding finding) {
			if (finding != null) {
				this.findings.add(finding);
			}
			return this;
		}

		public Builder sheetName(String sheetName) {
			if (!this.sheetNames.contains(sheetName)) {
				this.sheetNames.add(sheetName);
			}
			return this;
		}

		public Builder columns(List<String> names) {
			Objects.requireNonNull(names, "names must not be null");
			for (String name : names) {
				if (!this.columnNames.contains(name)) {
					this.columnNames.add(name);
				}
			}
			return this;
		}

		/**
		 * Records the header row exactly as the file carried it, before
		 * {@code HeaderDetector} made its names unique. See {@link FileParseResult#rawHeaders()}
		 * for why the normalised list cannot serve this purpose.
		 */
		public Builder rawHeader(List<String> headerCells) {
			Objects.requireNonNull(headerCells, "headerCells must not be null");
			this.rawHeaders.add(List.copyOf(headerCells));
			return this;
		}

		public Builder headerRowNumber(int rowNumber) {
			this.headerRowNumber = rowNumber;
			return this;
		}

		public Builder skipRows(int count) {
			this.skippedRows += count;
			return this;
		}

		public Builder status(ParseStatus newStatus) {
			this.status = Objects.requireNonNull(newStatus, "newStatus must not be null");
			return this;
		}

		public Builder failureReason(String reason) {
			this.failureReason = reason == null ? "" : reason;
			return this;
		}

		public FileParseResult build() {
			// The status is derived from what was actually accumulated, not chosen by
			// the caller. Rows but rejections and no rows both mean the file was not
			// read in full, so neither may be reported as SUCCESS.
			if (this.status != ParseStatus.FAILED && this.rows.isEmpty()) {
				this.status = this.rejectedRows.isEmpty() ? ParseStatus.EMPTY : ParseStatus.PARTIAL;
			}
			// A failed result must always say why; an empty reason would make the run
			// unanswerable to the analyst who has to fix the export.
			if (this.status == ParseStatus.FAILED && this.failureReason.isEmpty()) {
				this.failureReason = "unspecified parse failure";
			}
			return new FileParseResult(this);
		}

	}

}
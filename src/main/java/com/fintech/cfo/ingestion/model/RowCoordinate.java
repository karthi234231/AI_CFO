package com.fintech.cfo.ingestion.model;

import java.util.Objects;

import com.fintech.cfo.shared.domain.SourceReference;

/**
 * Where a single value physically came from: which file, which sheet, which
 * 1-based row.
 *
 * <p>This is the hard product requirement behind rule 4 — every reported rupee
 * must be traceable back to the row that produced it. The row number is 1-based
 * and counted exactly as a human would count it in the source: in a spreadsheet
 * it is Excel's own row number, in a delimited file it is the logical record
 * number including the header. Embedding a newline inside a CSV field does not
 * shift it, which is what makes the coordinate stable enough to be evidence.
 *
 * <p>Immutable and free of any document content, per rule 4.
 */
public record RowCoordinate(String sourceFileId, String sourceFileName, long rowNumber, String sheetName) {

	public RowCoordinate {
		Objects.requireNonNull(sourceFileId, "sourceFileId must not be null");
		Objects.requireNonNull(sourceFileName, "sourceFileName must not be null");
		if (rowNumber < 1) {
			throw new IllegalArgumentException("rowNumber must be 1-based and positive, was " + rowNumber);
		}
		sheetName = sheetName == null ? "" : sheetName;
	}

	public RowCoordinate(String sourceFileId, String sourceFileName, long rowNumber) {
		this(sourceFileId, sourceFileName, rowNumber, null);
	}

	/**
	 * Projects this coordinate onto the shared lineage type so downstream
	 * financial modules can link a calculated variance back to this row without
	 * re-deriving the coordinates.
	 *
	 * @param sourceSystem ERP/exporter the file came from, e.g. {@code TALLY}
	 * @param recordType   the record kind this row represents
	 * @return a pointer safe to store beside a monetary result
	 */
	public SourceReference toSourceReference(String sourceSystem, String recordType) {
		return SourceReference.of(sourceSystem, recordType, sheetName + ":" + this.rowNumber, this.sourceFileId,
				this.rowNumber);
	}

	@Override
	public String toString() {
		return this.sourceFileName + (this.sheetName.isEmpty() ? "" : "#" + this.sheetName) + "#row" + this.rowNumber;
	}

}
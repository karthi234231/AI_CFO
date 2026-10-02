package com.fintech.cfo.ingestion.enums;

/**
 * How far a parse got.
 *
 * <p>{@link #PARTIAL} is the important one. Commons CSV cannot resume a stream
 * after a lexical fault, and POI can abort mid-workbook, so when that happens the
 * rows already read are kept and the run is reported as partial with a reason.
 * Collapsing that into {@link #SUCCESS} would overstate how much of the file was
 * read; collapsing it into {@link #FAILED} would throw away good rows.
 */
public enum ParseStatus {

	/** The whole file was read. */
	SUCCESS,
	/** Some rows were recovered and some of the file was never seen; the reason is recorded. */
	PARTIAL,
	/** The file was readable and held no data rows. */
	EMPTY,
	/** The file could not be read at all; there are no rows and a stated reason. */
	FAILED

}
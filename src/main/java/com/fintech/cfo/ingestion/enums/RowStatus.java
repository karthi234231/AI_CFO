package com.fintech.cfo.ingestion.enums;

/**
 * Outcome for one physical row of an uploaded file.
 *
 * <p>Every row that the reader laid eyes on ends up in exactly one of these
 * buckets, or in an explicit skip that is counted. There is no fourth, silent
 * bucket: a row that is not {@link #ACCEPTED} always has a reason attached.
 */
public enum RowStatus {

	/** The row passed every gate; it is sanitised and usable. */
	ACCEPTED,
	/** The row was refused. Always carries a {@code RejectionReason}. */
	REJECTED,
	/** The position carried no data at all: a blank line or an empty spreadsheet row. Counted, not dropped. */
	SKIPPED

}
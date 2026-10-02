package com.fintech.cfo.ingestion.model;

import com.fintech.cfo.ingestion.enums.RejectionReason;

/**
 * Anything ingestion refused: either a whole file, or one row of it.
 *
 * <p>The union exists so the orchestration layer can talk about "what was
 * refused" without caring which stage refused it. A caller can exhaustively
 * switch over the subtypes, which the compiler enforces — the alternative, a
 * {@code null} row on a rejected file or a failed status on a rejected row, is
 * exactly the ambiguity that lets a partial failure be reported as a clean run.
 *
 * <p>Both subtypes carry a {@link RejectionReason} and a content-free
 * {@code detail}: the reason is what an operator filters on, the detail says what
 * was expected versus what was found, and neither ever quotes the uploaded bytes
 * (rule 5).
 *
 * @see FileRejection
 * @see RejectedRow
 */
public sealed interface Rejection permits FileRejection, RejectedRow {

	/** @return why the file or the row was refused; never {@code null} */
	RejectionReason reason();

	/** @return an explanation safe to log and to show a user; never {@code null} */
	String detail();

}
/**
 * The vocabulary ingestion reports in.
 *
 * <p>Every constant is written to a V3 column, so the string forms and the count
 * are part of the storage contract: {@code ingestion_errors.error_type} is
 * {@code VARCHAR(32)} and {@code severity} is {@code VARCHAR(16)}. Constants may be
 * added; existing names and their meanings may not change.
 *
 * <p>{@link com.fintech.cfo.ingestion.enums.RejectionReason} is deliberately
 * exhaustive rather than generic: "row 417, column amount, INVALID_AMOUNT" is
 * actionable in a way that "invalid file" never is.
 */
@NullMarked
package com.fintech.cfo.ingestion.enums;

import org.jspecify.annotations.NullMarked;
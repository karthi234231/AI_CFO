package com.fintech.cfo.ingestion.model;

import java.util.Objects;

import com.fintech.cfo.ingestion.enums.RejectionReason;

/**
 * A row that was refused, with the reason, the coordinates, and — where the
 * problem is narrower than the whole row — the column.
 *
 * <p>Rejection is never implicit. The raw payload is kept so finance can see what
 * was actually in the refused row and fix the export, rather than being told
 * merely that row 417 was "invalid".
 */
public record RejectedRow(RowCoordinate coordinate, RejectionReason reason, String columnName, String detail,
		String rawPayload) implements Rejection {

	public RejectedRow {
		Objects.requireNonNull(coordinate, "coordinate must not be null");
		Objects.requireNonNull(reason, "reason must not be null");
		columnName = columnName == null ? "" : columnName;
		detail = detail == null ? "" : detail;
		rawPayload = rawPayload == null ? "" : rawPayload;
	}

	public static RejectedRow of(RowCoordinate coordinate, RejectionReason reason, String detail) {
		return new RejectedRow(coordinate, reason, null, detail, null);
	}

	public static RejectedRow ofField(RowCoordinate coordinate, String columnName, RejectionReason reason,
			String detail, String rawValue) {
		return new RejectedRow(coordinate, reason, columnName, detail, rawValue);
	}

	/**
	 * @return {@code true} when the problem is attributable to a single column,
	 * so a caller can report it against that column rather than the whole row
	 */
	public boolean isColumnScoped() {
		return !this.columnName.isEmpty();
	}

	@Override
	public String toString() {
		return this.coordinate + " rejected (" + this.reason + ")"
				+ (isColumnScoped() ? " column=" + this.columnName : "") + ": " + this.detail;
	}

}
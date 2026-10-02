package com.fintech.cfo.ingestion.model;

import java.util.Objects;

import com.fintech.cfo.ingestion.enums.RejectionReason;

/**
 * A refusal that applies to the whole upload: an unreadable container, an
 * encrypted workbook, a zip bomb, a name that says one thing and bytes that say
 * another.
 *
 * <p>Has no coordinates, and does not need them. There is no row 417 to point at
 * when the file could not be opened at all, and inventing one would put a false
 * row number into an audit record.
 *
 * @param reason why the upload was refused
 * @param detail what was expected and what was found, free of file content
 */
public record FileRejection(RejectionReason reason, String detail) implements Rejection {

	public FileRejection {
		Objects.requireNonNull(reason, "reason must not be null");
		detail = detail == null ? "" : detail;
	}

	public static FileRejection of(RejectionReason reason, String detail) {
		return new FileRejection(reason, detail);
	}

	@Override
	public String toString() {
		return "file refused (" + this.reason + "): " + this.detail;
	}

}
package com.fintech.cfo.shared.domain;

import java.io.Serializable;
import java.util.Objects;

/**
 * Immutable pointer from normalized internal data back to its origin.
 *
 * <p>Stores references only — never full source rows or document contents.
 * This is what makes a reported monetary amount traceable:
 *
 * <pre>
 * EconomicOpportunity -&gt; CalculationResult -&gt; FinancialRecord
 *                     -&gt; SourceReference -&gt; original file / row
 * </pre>
 */
public final class SourceReference implements Serializable {

	private static final long serialVersionUID = 1L;

	private final String sourceSystem;
	private final String sourceRecordType;
	private final String sourceRecordId;
	private final String sourceFileId;
	private final Long sourceRowNumber;

	/**
	 * @param sourceSystem     originating system, e.g. {@code ERP}
	 * @param sourceRecordType record kind within that system, e.g. {@code INVOICE_LINE}
	 * @param sourceRecordId   record identifier within that system
	 * @param sourceFileId     uploaded file the record came from, or null when the
	 *                         record did not originate in an upload
	 * @param sourceRowNumber  1-based row within the file, or null
	 */
public SourceReference(String sourceSystem, String sourceRecordType, String sourceRecordId,
			String sourceFileId, Long sourceRowNumber) {
		// The three identity fields are mandatory and normalised: a reference that
		// cannot be resolved back to a record is not a reference.
		this.sourceSystem = com.fintech.cfo.shared.validation.Preconditions.requireText(sourceSystem, "sourceSystem");
		this.sourceRecordType = com.fintech.cfo.shared.validation.Preconditions.requireText(sourceRecordType, "sourceRecordType");
		this.sourceRecordId = com.fintech.cfo.shared.validation.Preconditions.requireText(sourceRecordId, "sourceRecordId");
		// The file and row stay nullable because records can also be created
		// directly through an API rather than parsed from an upload.
		this.sourceFileId = sourceFileId;
		this.sourceRowNumber = sourceRowNumber;
	}

	/**
	 * @param sourceSystem     originating system
	 * @param sourceRecordType record kind
	 * @param sourceRecordId   record identifier
	 * @return a reference with no file or row, for records not derived from an upload
	 */
	public static SourceReference of(String sourceSystem, String sourceRecordType, String sourceRecordId) {
		return new SourceReference(sourceSystem, sourceRecordType, sourceRecordId, null, null);
	}

	/**
	 * @param sourceSystem     originating system
	 * @param sourceRecordType record kind
	 * @param sourceRecordId   record identifier
	 * @param sourceFileId     uploaded file the record came from
	 * @param sourceRowNumber  1-based row within that file
	 * @return a fully qualified reference
	 */
	public static SourceReference of(String sourceSystem, String sourceRecordType, String sourceRecordId,
			String sourceFileId, Long sourceRowNumber) {
		return new SourceReference(sourceSystem, sourceRecordType, sourceRecordId, sourceFileId, sourceRowNumber);
	}

	/** @return originating system */
	public String sourceSystem() {
		return this.sourceSystem;
	}

	/** @return record kind within the originating system */
	public String sourceRecordType() {
		return this.sourceRecordType;
	}

	/** @return record identifier within the originating system */
	public String sourceRecordId() {
		return this.sourceRecordId;
	}

	/** @return uploaded file the record came from, or null */
	public String sourceFileId() {
		return this.sourceFileId;
	}

	/** @return 1-based row within the file, or null */
	public Long sourceRowNumber() {
		return this.sourceRowNumber;
	}

	/**
	 * All five components participate, file and row included. Two references to
	 * the same record in different files are genuinely different provenance, so
	 * collapsing them would hide which file a number was read from.
	 */
	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		return other instanceof SourceReference that
				&& this.sourceSystem.equals(that.sourceSystem)
				&& this.sourceRecordType.equals(that.sourceRecordType)
				&& this.sourceRecordId.equals(that.sourceRecordId)
				&& Objects.equals(this.sourceFileId, that.sourceFileId)
				&& Objects.equals(this.sourceRowNumber, that.sourceRowNumber);
	}

	@Override
	public int hashCode() {
		return Objects.hash(this.sourceSystem, this.sourceRecordType, this.sourceRecordId, this.sourceFileId,
				this.sourceRowNumber);
	}

	/**
	 * @return a {@code system/type/id@file#row} form, omitting the file and row
	 *         segments when absent so the string reads as a locator rather than
	 *         as a fixed-width template
	 */
	@Override
	public String toString() {
		return this.sourceSystem + "/" + this.sourceRecordType + "/" + this.sourceRecordId
				+ (this.sourceFileId != null ? "@" + this.sourceFileId : "")
				+ (this.sourceRowNumber != null ? "#" + this.sourceRowNumber : "");
	}

	/**
	 * Local text guard.
	 *
	 * <p>Kept private rather than delegating to {@code Preconditions.requireText}
	 * deliberately: this constructor predates that helper and is used from the
	 * persistence boundary, so it keeps an {@link IllegalArgumentException}
	 * contract that a mapper translating rows can handle as a bad row instead of
	 * as a client validation failure.
	 *
	 * @param value text to validate
	 * @param field field name used in the failure message
	 * @return the trimmed value
	 */
	private static String requireText(String value, String field) {
		Objects.requireNonNull(value, field + " must not be null");
		String trimmed = value.trim();
		if (trimmed.isEmpty()) {
			throw new IllegalArgumentException(field + " must not be blank");
		}
		return trimmed;
	}

}

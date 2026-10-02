package com.fintech.cfo.evidence.model;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.fintech.cfo.shared.domain.SourceReference;
import com.fintech.cfo.shared.exception.ValidationException;

/**
 * The two source columns V7 repeats on {@code evidences}:
 * {@code source_file_id} and {@code source_row_number}.
 *
 * <p>Not a full {@link SourceReference}, and deliberately so: the evidence row does not
 * record which upstream system or which record inside the file the row came from,
 * because V7 has no columns for it. Inventing those two fields here would create a
 * second, partially-populated spelling of provenance that disagreed with the one the
 * canonical financial records use.
 *
 * <p>What this type does provide is the check that closes the gap. {@link
 * #matches(SourceReference)} proves that a locator and a reference point at the same
 * place, which is how the graph walk confirms that a reconstructed source row is the
 * one an {@code Evidence} row already claims, rather than a row that merely looks
 * similar.
 *
 * @param sourceFileId  {@code source_files.id} the row lives in; never null on a
 *                      source-bearing evidence row
 * @param sourceRowNumber {@code BIGINT} row number within the file; null when the
 *                      evidence is about the whole file
 */
public record SourceLocation(UUID sourceFileId, @Nullable Long sourceRowNumber) {

	/**
	 * Compact constructor: a locator without a file is not a locator.
	 *
	 * <p>The row number stays optional on purpose - evidence about a whole uploaded
	 * file legitimately has no single row - but a non-positive number is refused
	 * everywhere, because parser row numbering is 1-based and a 0-based coordinate
	 * stored beside a 1-based parser points a reviewer at the wrong line while
	 * looking entirely correct.
	 */
	public SourceLocation {
		if (sourceFileId == null) {
			throw new ValidationException("sourceFileId must not be null");
		}
		if (sourceRowNumber != null && sourceRowNumber <= 0) {
			throw new ValidationException("sourceRowNumber must be 1-based and positive when supplied");
		}
	}

	/** A locator for a whole file, with no row. */
	public static SourceLocation ofFile(UUID sourceFileId) {
		return new SourceLocation(sourceFileId, null);
	}

	/** A locator for one row of a file. */
	public static SourceLocation ofRow(UUID sourceFileId, long rowNumber) {
		return new SourceLocation(sourceFileId, rowNumber);
	}

	/**
	 * Whether this locator identifies a single row rather than a whole file.
	 *
	 * <p>The distinction matters when evidence is presented: a row-level proof can be
	 * checked against a specific line of the upload, while a file-level reference can
	 * only be checked by opening the document.
	 *
	 * @return true when a row number is present
	 */
	public boolean isRowLevel() {
		return this.sourceRowNumber != null;
	}

	/**
	 * Whether the locator and the reference name the same file and row.
	 *
	 * <p>Both sides must agree. A reference that omits the file or the row cannot
	 * confirm this locator, because "somewhere in that system" is not evidence that a
	 * specific line was read from a specific upload.
	 *
	 * <p>Comparison is done on string forms because the two types disagree on how to
	 * spell a file id: {@code shared.domain.SourceReference} carries its file id as
	 * text, inherited from the boundary where records arrive from upstream systems.
	 * Normalising to a string for the comparison keeps this type free of a parsing
	 * dependency it would otherwise need only to answer a yes/no question.
	 *
	 * @param reference the reference reconstructed from a canonical record, possibly null
	 * @return true only when both name the same file and the same 1-based row
	 */
	public boolean matches(SourceReference reference) {
		// Any missing component on the reference side yields false rather than a
		// partial match. A partial match is the dangerous outcome: it would confirm a
		// row that was actually read from a different upload of the same file name.
		if (reference == null || reference.sourceFileId() == null || reference.sourceRowNumber() == null) {
			return false;
		}
		// longValue() on both sides because one side is a primitive-friendly Long and
		// the other may arrive boxed from a parser; unboxing by identity comparison
		// would fail for values above the Long cache.
		return this.sourceFileId.toString().equals(reference.sourceFileId())
				&& this.sourceRowNumber.longValue() == reference.sourceRowNumber().longValue();
	}

	/**
	 * The single-line form used in audit detail; carries no monetary value.
	 *
	 * <p>Omits the row segment entirely when there is none, so a file-level reference
	 * does not render as {@code row=null} in a log a reviewer is expected to read.
	 *
	 * @return {@code file=<uuid>} with an optional {@code  row=<n>} segment
	 */
	public String describe() {
		return "file=" + this.sourceFileId + (this.sourceRowNumber == null ? "" : " row=" + this.sourceRowNumber);
	}

}

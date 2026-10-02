package com.fintech.cfo.evidence.enums;

import java.util.List;

import com.fintech.cfo.shared.exception.ValidationException;

/**
 * What kind of proof an evidence row carries, persisted as
 * {@code evidences.evidence_type VARCHAR(32)} in V7.
 *
 * <p>A closed set rather than free text, because the type decides what a row is
 * required to point at. {@link #requiresSourceFile()} and
 * {@link #requiresSourceRow()} are enforced by
 * {@link com.fintech.cfo.evidence.model.Evidence}'s compact constructor, so a
 * {@code SOURCE_ROW} evidence row that names no file and no row cannot exist in
 * memory - it is not a weak row, it is an unusable one, and letting it be stored
 * would put an unverifiable claim into the evidence set.
 *
 * <p>The split between {@link #isDerived()} and {@link #isSourceBearing()} is the
 * distinction the traceability chain turns on. Derived evidence (a calculation
 * result, a snapshot) is only meaningful together with the source-bearing evidence
 * beneath it; source-bearing evidence is meaningful on its own because it names the
 * row it came from.
 */
public enum EvidenceType implements CodedEnum {

	/** The literal source row an amount was read from. File and row number mandatory. */
	SOURCE_ROW,

	/** An entire uploaded file, referenced by storage key and checksum, never copied. */
	SOURCE_FILE,

	/** A contractual term that produced an expected amount. File reference mandatory. */
	CONTRACT_TERM,

	/** A normalized invoice line, which must remain addressable to the file it came from. */
	INVOICE_LINE,

	/** A normalized financial transaction, which must remain addressable to its file. */
	FINANCIAL_TRANSACTION,

	/** A data-quality finding raised during ingestion, addressable to the offending row. */
	DATA_QUALITY_FINDING,

	/** A frozen rendering of a calculation result at the moment it was asserted. */
	CALCULATION_RESULT,

	/** The reproducible run that produced a calculation result. */
	CALCULATION_RUN,

	/** An immutable snapshot captured by this module. */
	SNAPSHOT,

	/** A reviewer's own statement. Carries an opinion, not a machine-checkable proof. */
	REVIEW_NOTE,

	/** A cryptographic attestation that a stored document is unaltered. */
	INTEGRITY_ATTESTATION;

	/** Width of {@code evidences.evidence_type} in V7. */
	public static final int MAX_CODE_LENGTH = 32;

	/**
	 * Every variant in a fixed declaration order that does not depend on hashing or on
	 * the runtime's locale, so a UI or report that lists them renders identically on
	 * every machine.
	 *
	 * <p>Declaration order, not {@code EnumSet} iteration order: this list feeds a
	 * picker and a validation message, and both must read the same way regardless of
	 * how the JVM happens to order hash buckets.
	 *
	 * @return an immutable list of all eleven variants
	 */
	public static List<EvidenceType> all() {
		return List.of(values());
	}

	/**
	 * Resolves a stored {@code evidence_type} value.
	 *
	 * @param code value read from {@code evidences.evidence_type}
	 * @return the matching variant, never {@code null}
	 * @throws ValidationException if the value is blank or unknown
	 */
	public static EvidenceType fromCode(String code) {
		// Normalise first so the stored value is compared against the enum's own
		// name() form; a value stored by this module and one typed by hand compare
		// equal regardless of case, and an unknown code fails here rather than
		// returning null and letting a caller dereference it.
		String normalized = CodedEnum.normalise(code, "EvidenceType");
		for (EvidenceType candidate : values()) {
			if (candidate.code().equals(normalized)) {
				// Width re-checked on the way out, not only at declaration: the guard
				// belongs on the path that reads a stored value, so a value written by
				// hand cannot slip past it.
				candidate.requireColumnWidth(MAX_CODE_LENGTH);
				return candidate;
			}
		}
		// No fallback and no default variant. An unrecognised code means the column
		// and this enum have drifted apart, and resolving it to something plausible
		// would mis-attribute a proof to the wrong kind of claim.
		throw new ValidationException("unknown evidence type code: " + code);
	}

	/** Value written to and read from the column.
	 *
	 * @return the constant's own name, which is also its persisted string
	 */
	@Override
	public String code() {
		return this.name();
	}

	/**
	 * Whether the row is meaningless without a source file. Enforced on construction:
	 * evidence that cannot name its file cannot be independently re-read, so it is
	 * not evidence.
	 *
	 * <p>An exhaustive {@code switch} over {@code this}, so adding a variant later is
	 * a compile error here rather than a silently default {@code false} that would
	 * let a new proof type skip the source requirement.
	 *
	 * @return true for the variants that must name a {@code source_files} row
	 */
	public boolean requiresSourceFile() {
		// Both cases listed explicitly rather than using a default: an omitted
		// constant would compile, and the omission would read as "this new type
		// needs no source file", which is exactly the claim this method exists to
		// force someone to make on purpose.
		return switch (this) {
			case SOURCE_ROW, SOURCE_FILE, INVOICE_LINE, FINANCIAL_TRANSACTION, CONTRACT_TERM,
					DATA_QUALITY_FINDING -> true;
			case CALCULATION_RESULT, CALCULATION_RUN, SNAPSHOT, REVIEW_NOTE, INTEGRITY_ATTESTATION -> false;
		};
	}

	/**
	 * Whether the row is meaningless without a row number as well as a file. A file
	 * reference alone identifies a container, not the fact being asserted.
	 *
	 * <p>Strictly narrower than {@link #requiresSourceFile()}: {@code CONTRACT_TERM}
	 * and {@code SOURCE_FILE} need a file but legitimately have no single row, which
	 * is why one flag cannot substitute for the other.
	 *
	 * @return true for the variants that must name a 1-based row within the file
	 */
	public boolean requiresSourceRow() {
		return switch (this) {
			case SOURCE_ROW, INVOICE_LINE, FINANCIAL_TRANSACTION, DATA_QUALITY_FINDING -> true;
			case SOURCE_FILE, CONTRACT_TERM, CALCULATION_RESULT, CALCULATION_RUN, SNAPSHOT, REVIEW_NOTE,
					INTEGRITY_ATTESTATION -> false;
		};
	}

	/**
	 * Whether the proof is computed from other facts rather than read from a file.
	 *
	 * <p>Derived evidence is admissible only alongside the source-bearing evidence it
	 * was computed from, which is what
	 * {@link com.fintech.cfo.evidence.service.LineageService#requireTraceableToSource} enforces.
	 *
	 * <p>That enforcement does not exist yet: {@code LineageService} is still a
	 * placeholder. The predicate is the contract a walk will apply.
	 *
	 * @return true for the three computed variants
	 */
	public boolean isDerived() {
		// INVOICE_LINE is deliberately not derived: it is a normalisation of a file
		// row, and its addressability to the upload is exactly what makes it
		// source-bearing evidence rather than a derived claim.
		return switch (this) {
			case CALCULATION_RESULT, CALCULATION_RUN, SNAPSHOT -> true;
			case SOURCE_ROW, SOURCE_FILE, INVOICE_LINE, FINANCIAL_TRANSACTION, CONTRACT_TERM, DATA_QUALITY_FINDING,
					REVIEW_NOTE, INTEGRITY_ATTESTATION -> false;
		};
	}

	/**
	 * Whether the proof can be verified without trusting this system. A reviewer's
	 * note cannot: it is an assertion, and the chain must reach a file for the figure
	 * to be defensible.
	 *
	 * <p>Not the complement of {@link #isDerived()}. {@code INTEGRITY_ATTESTATION} is
	 * derived in spirit but is machine-checkable, because a reviewer can re-hash the
	 * stored object and compare. Treating "derived" and "unverifiable" as one axis
	 * would put a cryptographic proof in the same bucket as an opinion.
	 *
	 * @return true for the variants a third party can independently re-check
	 */
	public boolean isIndependentlyVerifiable() {
		return switch (this) {
			case SOURCE_ROW, SOURCE_FILE, INVOICE_LINE, FINANCIAL_TRANSACTION, CONTRACT_TERM,
					DATA_QUALITY_FINDING, INTEGRITY_ATTESTATION -> true;
			case CALCULATION_RESULT, CALCULATION_RUN, SNAPSHOT, REVIEW_NOTE -> false;
		};
	}

}

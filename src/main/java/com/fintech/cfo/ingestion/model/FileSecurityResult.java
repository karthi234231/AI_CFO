package com.fintech.cfo.ingestion.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.fintech.cfo.ingestion.enums.FileSecurityStatus;
import com.fintech.cfo.ingestion.enums.FileType;
import com.fintech.cfo.ingestion.enums.IngestionErrorType;
import com.fintech.cfo.ingestion.enums.RejectionReason;

/**
 * Outcome of the file-security and file-type gates.
 *
 * <p>{@link #allowsParsing()} is the only question the parser asks, and it is
 * false for everything except a clean pass. That single boolean is the whole
 * reason the gates are separate from parsing: once a file reaches a parser it
 * has already been proven to be a readable tabular file.
 */
public final class FileSecurityResult {

	/** The combined verdict: a pass or a rejection from the security/type gates. */
	private final FileSecurityStatus status;

	/**
	 * Safe leaf name, present only on a pass. Wrapped in {@link Optional} on the
	 * way out because a rejected file has no trustworthy name to expose.
	 */
	private final SanitisedFilename filename;

	/** What the client claimed the file was, from the extension or content type. */
	private final FileType declaredFileType;

	/**
	 * What the bytes actually say, from content sniffing. Null when detection
	 * never ran — which is itself a signal, since a pass always sniffs.
	 */
	private final FileType detectedFileType;

	/** Raw content type as supplied, retained for the audit record. */
	private final String contentType;

	/** Every problem found, including non-fatal ones on an otherwise clean pass. */
	private final List<ValidationFinding> findings;

	/**
	 * Sole constructor, private to force construction through the two named
	 * factories below.
	 *
	 * <p>{@code requireNonNull} on the three fields that describe a decision
	 * (status and both file types) because a result with no verdict cannot be
	 * reasoned about. The other three are normalised instead: a missing name
	 * means "rejected" rather than being an error, a null content type becomes
	 * the empty string so callers never have to null-check it, and the finding
	 * list is defensively copied so a caller cannot mutate it after construction.
	 */
	private FileSecurityResult(FileSecurityStatus status, SanitisedFilename filename, FileType declaredFileType,
			FileType detectedFileType, String contentType, List<ValidationFinding> findings) {
		this.status = Objects.requireNonNull(status, "status must not be null");
		this.filename = filename;
		this.declaredFileType = Objects.requireNonNull(declaredFileType, "declaredFileType must not be null");
		this.detectedFileType = detectedFileType;
		this.contentType = contentType == null ? "" : contentType;
		this.findings = List.copyOf(findings);
	}

	/**
	 * A clean pass. The parser may proceed.
	 *
	 * <p>Note that findings may be non-empty even here: warnings such as a
	 * declared/detected mismatch that was tolerated do not block parsing, and
	 * keeping them attached means the reason for a warning is not lost just
	 * because the file was accepted.
	 */
	public static FileSecurityResult passed(SanitisedFilename filename, FileType declaredFileType,
			FileType detectedFileType, String contentType, List<ValidationFinding> findings) {
		return new FileSecurityResult(FileSecurityStatus.PASSED, filename, declaredFileType, detectedFileType,
				contentType, findings);
	}

	/**
	 * A rejection. The parser must not be invoked.
	 *
	 * <p>A finding is synthesised here from the reason and detail, so a rejected
	 * file always carries at least one error explaining why. Constructing it in
	 * one place is what prevents a rejection from ever travelling without an
	 * accompanying finding.
	 *
	 * <p>The error-type classification distinguishes <i>security</i> failures
	 * (traversal, unsafe filename, executable content) from <i>type</i> failures
	 * (anything else, e.g. a disallowed extension or a format the parser cannot
	 * read). The split matters to the operator: a security rejection may indicate
	 * an attack attempt, whereas a type rejection is usually just a user mistake.
	 * {@code detectedFileType} and {@code filename} stay null because nothing was
	 * trusted enough to sniff or to keep.
	 */
	public static FileSecurityResult rejected(RejectionReason reason, String detail, FileType declaredFileType,
			String contentType) {
		return new FileSecurityResult(FileSecurityStatus.REJECTED, null, declaredFileType, null, contentType,
				List.of(ValidationFinding.file(
						reason == RejectionReason.PATH_TRAVERSAL_ATTEMPT || reason == RejectionReason.UNSAFE_FILENAME
								|| reason == RejectionReason.EXECUTABLE_CONTENT
										? IngestionErrorType.FILE_SECURITY
										: IngestionErrorType.FILE_TYPE,
						reason, detail)));
	}

	/** The raw verdict, for audit and persistence. */
	public FileSecurityStatus status() {
		return this.status;
	}

	/**
	 * The single question the ingestion pipeline asks before parsing.
	 *
	 * <p>Delegated to the status rather than re-derived from the findings, so the
	 * rule for "may I read this file" is defined in exactly one enum and cannot
	 * drift from the verdict that was actually recorded. False for every status
	 * other than a full pass.
	 */
	public boolean allowsParsing() {
		return this.status.allowsParsing();
	}

	/**
	 * The safe name to use, empty when the file was rejected. Forcing the
	 * {@link Optional} makes the null case impossible to overlook at the call
	 * site, where using a rejected file's name would be a security bug.
	 */
	public Optional<SanitisedFilename> filename() {
		return Optional.ofNullable(this.filename);
	}

	/** What the client said the file was. */
	public FileType declaredFileType() {
		return this.declaredFileType;
	}

	/**
	 * What content sniffing determined. Comparing this against
	 * {@link #declaredFileType()} is how a renamed executable is caught.
	 */
	public FileType detectedFileType() {
		return this.detectedFileType;
	}

	/** Raw content type, for the audit trail. Never null; empty when absent. */
	public String contentType() {
		return this.contentType;
	}

	/** All findings, immutable. */
	public List<ValidationFinding> findings() {
		return this.findings;
	}

	/**
	 * The first error-severity finding, which is the one to surface to the user.
	 * Errors are checked before warnings because a warning on its own does not
	 * explain a rejection.
	 */
	public Optional<ValidationFinding> firstError() {
		return this.findings.stream().filter(ValidationFinding::isError).findFirst();
	}

	/**
	 * Deliberately excludes the filename: it can be long, can contain user-supplied
	 * text, and is already recorded elsewhere. This shape is for logs, so it
	 * summarises the verdict rather than reproducing the input.
	 */
	@Override
	public String toString() {
		return "FileSecurityResult[" + this.status + " " + this.declaredFileType + " findings=" + this.findings.size()
				+ "]";
	}

}
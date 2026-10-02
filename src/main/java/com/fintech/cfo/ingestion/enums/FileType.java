package com.fintech.cfo.ingestion.enums;

import java.util.Locale;
import java.util.Set;

/**
 * Upload formats the ingestion milestone can actually read.
 *
 * <p>The declared type (from the filename extension) and the detected type
 * (from magic bytes) are recorded separately and compared. A name that claims
 * CSV while the bytes are a ZIP container is a rejection, not something to
 * "make work" — that mismatch is how a renamed executable gets in.
 *
 * <p>The string values are the values persisted to
 * {@code source_files.declared_file_type} / {@code detected_file_type} in
 * {@code V3__create_ingestion.sql}, so they must stay stable.
 */
public enum FileType {

	/**
	 * Delimited text. {@code text/plain} is accepted as an alternate content type
	 * because browsers frequently label a CSV download that way.
	 */
	CSV("csv", "text/csv", "text/plain", Set.of("csv", "txt", "tsv")),

	/**
	 * An OOXML spreadsheet package. The alternate content type covers macro-enabled
	 * workbooks; the archive walk reads this as a container either way.
	 */
	XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", null,
			Set.of("xlsx", "xlsm")),

	/**
	 * A recognised container that this milestone deliberately refuses to read
	 * (legacy {@code .xls}, OLE2 documents, PDF, archives, executables). Kept as
	 * a distinct value rather than {@code null} so the rejection reason can state
	 * what was actually uploaded.
	 */
	UNSUPPORTED("unsupported", null, null, Set.of()),

	/**
	 * The extension or content type named nothing this module recognises. Distinct
	 * from {@link #UNSUPPORTED} so a rejection can tell a typo from a format that
	 * was deliberately refused.
	 */
	UNKNOWN("unknown", null, null, Set.of());

	private final String code;

	private final String canonicalContentType;

	/**
	 * A second MIME type that also legitimately maps to this format, or null.
	 *
	 * <p>Exists because clients send the wrong-but-common type rather than none at
	 * all. Accepting a narrow, enumerated alternate is safe; accepting a pattern
	 * would not be.
	 */
	private final String alternateContentType;

	/** Extensions that resolve to this type. Empty for non-readable types. */
	private final Set<String> extensions;

	/**
	 * Extensions that are recognised as real, but that this milestone refuses to
	 * read. Distinguishing them from a typo lets the rejection say "legacy .xls is
	 * not supported, export as .xlsx" instead of "unknown file type".
	 */
	private static final Set<String> KNOWN_UNSUPPORTED = Set.of("xls", "xlsb", "ods", "pdf", "doc", "docx", "zip",
			"gz", "tgz", "tar", "rar", "7z", "exe", "dll", "sh", "bat", "cmd", "js", "vbs", "jar", "html", "htm",
			"xml", "json");

	FileType(String code, String canonicalContentType, String alternateContentType, Set<String> extensions) {
		this.code = code;
		this.canonicalContentType = canonicalContentType;
		this.alternateContentType = alternateContentType;
		this.extensions = Set.copyOf(extensions);
	}

	/**
	 * Resolves an extension to a type, distinguishing "refused on purpose" from
	 * "never heard of it".
	 *
	 * <p>Three outcomes rather than two, and the distinction is the point.
	 * {@code KNOWN_UNSUPPORTED} lists formats that are perfectly valid files which
	 * this milestone simply cannot read, so {@link #UNSUPPORTED} is returned and
	 * the user can be told "export as .xlsx". Anything outside both the readable
	 * extensions and that list is {@link #UNKNOWN}, which reads as a typo or an
	 * entirely foreign format. Collapsing the two would tell a user with a legacy
	 * {@code .xls} that they uploaded an unknown file type, which is both wrong and
	 * unactionable.
	 *
	 * <p>Never throws. An unrecognisable extension is a normal outcome of inspecting
	 * untrusted input, not an exceptional one, and the caller decides what to do
	 * with {@code UNKNOWN}.
	 *
	 * <p>Leading-dot stripping and lower-casing make {@code ".CSV"}, {@code "csv"}
	 * and {@code " .csv "} all resolve identically — presentation noise from
	 * browsers and spreadsheets should not decide a security-relevant
	 * classification. {@link Locale#ROOT} rather than the default locale, so a
	 * Turkish default cannot mangle the letter {@code i} and misclassify
	 * {@code .xls}.
	 */
	public static FileType fromExtension(String extension) {
		if (extension == null || extension.isBlank()) {
			return UNKNOWN;
		}
		String normalised = extension.trim().toLowerCase(Locale.ROOT);
		// Callers may pass either "csv" or ".csv"; normalise to the bare form.
		if (normalised.startsWith(".")) {
			normalised = normalised.substring(1);
		}
		// Check the readable types first, since their extension sets are non-empty.
		for (FileType candidate : values()) {
			if (candidate.extensions.contains(normalised)) {
				return candidate;
			}
		}
		// Not readable, but is it a format we know about?
		return KNOWN_UNSUPPORTED.contains(normalised) ? UNSUPPORTED : UNKNOWN;
	}

	/**
	 * Derives the declared type from the trailing extension only. Any directory
	 * component is ignored here on purpose — traversal defence belongs to the
	 * filename sanitiser, and this method must never agree with a caller that
	 * supplied {@code ../../etc/passwd.csv}.
	 *
	 * <p>Uses the <i>last</i> dot so a name like {@code report.csv.exe} resolves to
	 * {@code exe}, which lands in {@code UNSUPPORTED} and is refused. Using the
	 * first dot instead would report {@code csv} and wave through a dangerous file
	 * wearing an innocuous extension.
	 *
	 * <p>Returns {@code UNKNOWN} rather than throwing for a name with no dot or a
	 * trailing dot, because this inspects untrusted input.
	 */
	public static FileType fromFilename(String filename) {
		if (filename == null || filename.isBlank()) {
			return UNKNOWN;
		}
		int lastDot = filename.lastIndexOf('.');
		// No dot, or a dot at the very end (a dotfile or "name."): nothing usable.
		if (lastDot < 0 || lastDot == filename.length() - 1) {
			return UNKNOWN;
		}
		return fromExtension(filename.substring(lastDot + 1));
	}

	/**
	 * Resolves a declared HTTP content type to a type.
	 *
	 * <p>Strips any trailing parameters before matching, so
	 * {@code text/csv; charset=UTF-8} resolves the same as {@code text/csv}. Real
	 * clients send the parameterized form constantly, and without this an ordinary
	 * upload would be misreported as unknown.
	 *
	 * <p>Matches the canonical content type and, where one exists, the alternate.
	 * Only exact equality is used — never a prefix or substring match — because a
	 * loose match would let a crafted type such as {@code text/csvx} or an
	 * attacker-supplied type be read as CSV.
	 *
	 * <p>Returns {@code UNKNOWN} for anything unrecognised rather than defaulting
	 * to a readable type. A content type is a client-supplied claim, so an
	 * unrecognised one carries no information and must not be resolved into
	 * permission. The authoritative signal is the sniffed content, not this value.
	 */
	public static FileType fromContentType(String contentType) {
		if (contentType == null || contentType.isBlank()) {
			return UNKNOWN;
		}
		String normalised = contentType.trim().toLowerCase(Locale.ROOT);
		// Cut at the first ';' to drop "charset=..." and any further parameters.
		int parameters = normalised.indexOf(';');
		if (parameters >= 0) {
			normalised = normalised.substring(0, parameters).trim();
		}
		for (FileType candidate : values()) {
			// equals, not startsWith: an untrusted type must match exactly or not at all.
			if (normalised.equals(candidate.canonicalContentType)
					|| normalised.equals(candidate.alternateContentType)) {
				return candidate;
			}
		}
		return UNKNOWN;
	}

	/**
	 * The value persisted to {@code source_files}. Stable strings, because rows
	 * already in the database must keep resolving.
	 */
	public String code() {
		return this.code;
	}

	/** The MIME type this format is normally sent as. */
	public String canonicalContentType() {
		return this.canonicalContentType;
	}

	/**
	 * The readable extensions for this type, immutable.
	 *
	 * <p>Returned as the copy taken in the constructor, so a caller cannot add to
	 * the set and thereby change what the security gate will accept.
	 */
	public Set<String> extensions() {
		return this.extensions;
	}

	/**
	 * Whether a parser exists for this type.
	 *
	 * <p>Identity comparison against the two supported constants, so the rule lives
	 * in exactly one place and adding a readable format means editing this method
	 * rather than hunting for every place that assumed only CSV and XLSX existed.
	 */
	public boolean isReadable() {
		return this == CSV || this == XLSX;
	}

}
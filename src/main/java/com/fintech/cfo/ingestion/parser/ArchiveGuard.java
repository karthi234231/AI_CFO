package com.fintech.cfo.ingestion.parser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.fintech.cfo.ingestion.enums.RejectionReason;
import com.fintech.cfo.ingestion.model.IngestionLimits;

/**
 * Decompression-bomb defence for OOXML containers.
 *
 * <p>An {@code .xlsx} is a zip, so "check the extension and the magic bytes" is
 * not enough: a 40&nbsp;KB upload can inflate to 40&nbsp;GB and take the process
 * out before a single row is read. POI will happily inflate everything it is
 * given, so the container is walked here first, under a hard ceiling on entry
 * count, total inflated bytes and inflation ratio, and only then handed over.
 *
 * <p>Declared entry sizes are not trusted — a zip bomb lies in its headers — so
 * the bytes are actually inflated and measured, and the walk aborts the moment a
 * ceiling is crossed. That costs at most the configured ceiling.
 *
 * <p>The ratio is only enforced from {@link IngestionLimits#ratioCheckMinBytes}
 * upwards. A legitimate small workbook can easily compress 20:1, and refusing it
 * would train users to bypass the check.
 *
 * <p>Entry names are read but never used to create a file: this module is
 * entirely in-memory, so there is no extraction step for a zip-slip entry name to
 * attack. They are still normalised (path separators unified, control characters
 * removed, lower-cased) so a crafted name cannot forge a log line, and so the
 * package-shape test below compares against real OOXML paths.
 */
public final class ArchiveGuard {

	/** Path of the part every OOXML package carries. */
	private static final String OOXML_CONTENT_TYPES = "[content_types].xml";

	/** Path prefix of the spreadsheet parts. */
	private static final String WORKBOOK_PART_PREFIX = "xl/";

	/** Path suffix of the workbook part. */
	private static final String WORKBOOK_PART_SUFFIX = "/workbook.xml";

	/** Relationship parts live under {@code _rels/} and are never the workbook. */
	private static final String RELATIONSHIPS = "_rels/";

	private static final int READ_BUFFER = 8192;

	private ArchiveGuard() {
	}

	/**
	 * @param content the container bytes, already size-checked
	 * @param limits  the ceilings to enforce
	 * @return what was found inside
	 * @throws ArchiveRejection when the bytes are not a container at all, or when
	 * a ceiling is crossed; the message states only the limit and the observed
	 * figure, never any entry content (rule 5)
	 */
	public static ArchiveInspection inspect(byte[] content, IngestionLimits limits) {
		Objects.requireNonNull(content, "content must not be null");
		Objects.requireNonNull(limits, "limits must not be null");

		List<String> entryNames = new ArrayList<>();
		long totalUncompressed = 0L;
		int entries = 0;
		byte[] buffer = new byte[READ_BUFFER];

		try (InputStream raw = new ByteArrayInputStream(content); ZipInputStream zip = new ZipInputStream(raw)) {
			ZipEntry entry = zip.getNextEntry();
			while (entry != null) {
				entries++;
				if (entries > limits.maxArchiveEntries()) {
					throw new ArchiveRejection(RejectionReason.ARCHIVE_LIMIT_EXCEEDED,
							"the container holds more than the permitted " + limits.maxArchiveEntries()
									+ " archive entries");
				}
				entryNames.add(normaliseName(entry.getName()));
				int read;
				while ((read = zip.read(buffer)) != -1) {
					totalUncompressed += read;
					if (totalUncompressed > limits.maxUncompressedBytes()) {
						throw new ArchiveRejection(RejectionReason.ARCHIVE_LIMIT_EXCEEDED,
								"the container inflates to more than the permitted " + limits.maxUncompressedBytes()
										+ " bytes");
					}
					if (exceedsRatio(totalUncompressed, content.length, limits)) {
						throw new ArchiveRejection(RejectionReason.ZIP_BOMB_SUSPECTED,
								"the container inflates by more than the permitted factor of "
										+ limits.maxCompressionRatio());
					}
				}
				zip.closeEntry();
				entry = zip.getNextEntry();
			}
		}
		catch (ArchiveRejection ex) {
			throw ex;
		}
		catch (IOException ex) {
			throw new ArchiveRejection(RejectionReason.CORRUPT_FILE,
					"the container is not a readable zip archive (" + ex.getClass().getSimpleName() + ")");
		}

		if (entries == 0) {
			throw new ArchiveRejection(RejectionReason.CORRUPT_FILE,
					"the content is not a zip container, so it cannot be a spreadsheet package");
		}
		return new ArchiveInspection(entries, totalUncompressed, List.copyOf(entryNames));
	}

	private static boolean exceedsRatio(long uncompressed, int compressedLength, IngestionLimits limits) {
		if (compressedLength < limits.ratioCheckMinBytes() || compressedLength == 0) {
			return false;
		}
		return uncompressed > limits.maxCompressionRatio() * compressedLength;
	}

	/**
	 * Unified separators, control characters removed, lower-cased. The full path
	 * is kept because the package-shape test needs it: {@code xl/workbook.xml} and
	 * a bare {@code workbook.xml} are different parts, and collapsing the prefix
	 * would make every real workbook look like something else.
	 */
	private static String normaliseName(String name) {
		if (name == null || name.isEmpty()) {
			return "";
		}
		String unified = name.replace('\\', '/');
		StringBuilder cleaned = new StringBuilder(unified.length());
		for (int index = 0; index < unified.length(); index++) {
			char character = unified.charAt(index);
			if (character >= ' ' && character != '\u007F') {
				cleaned.append(character);
			}
		}
		return cleaned.toString().toLowerCase(Locale.ROOT);
	}

	/**
	 * A refusal raised by the archive walk, carrying the reason to report.
	 *
	 * <p>Typed rather than a bare message so the caller maps it to a
	 * {@link RejectionReason} without matching on the wording of the text, which
	 * would break the moment the message is reworded.
	 */
	public static final class ArchiveRejection extends RuntimeException {

		private static final long serialVersionUID = 1L;

		private final RejectionReason reason;

		ArchiveRejection(RejectionReason reason, String detail) {
			super(detail);
			this.reason = Objects.requireNonNull(reason, "reason must not be null");
		}

		public RejectionReason reason() {
			return this.reason;
		}

	}

	/**
	 * What the walk found.
	 *
	 * @param entryCount        number of zip entries
	 * @param uncompressedBytes bytes actually inflated while walking
	 * @param entryNames        normalised, lower-cased archive paths
	 */
	public record ArchiveInspection(int entryCount, long uncompressedBytes, List<String> entryNames) {

		/** @return true when the entries look like a spreadsheet package */
		public boolean looksLikeSpreadsheetPackage() {
			return this.entryNames.contains(OOXML_CONTENT_TYPES) && this.entryNames.stream()
				.anyMatch(ArchiveInspection::isWorkbookPart);
		}

		/** @return true when the entries look like a Word or PowerPoint package */
		public boolean looksLikeOtherOoxmlPackage() {
			return this.entryNames.contains(OOXML_CONTENT_TYPES) && this.entryNames.stream()
				.noneMatch(ArchiveInspection::isWorkbookPart);
		}

		private static boolean isWorkbookPart(String path) {
			return path.startsWith(WORKBOOK_PART_PREFIX) && path.endsWith(WORKBOOK_PART_SUFFIX)
					&& !path.contains(RELATIONSHIPS);
		}

	}

}

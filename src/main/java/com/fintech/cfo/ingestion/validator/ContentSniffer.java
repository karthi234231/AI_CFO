package com.fintech.cfo.ingestion.validator;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import com.fintech.cfo.ingestion.enums.FileType;

/**
 * Identifies what a file actually is from its leading bytes, independently of its
 * name.
 *
 * <p>A filename is a claim; this is the evidence. A file called
 * {@code ledger.csv} whose first two bytes are {@code PK} is a ZIP archive, not a
 * ledger, and ingestion must say so. The check is deliberately conservative: it
 * only recognises the packages it can positively identify, and leaves everything
 * else as text or unknown rather than guessing.
 */
public final class ContentSniffer {

	private static final byte[] ZIP_MAGIC = { 0x50, 0x4B, 0x03, 0x04 };

	private static final byte[] ZIP_EMPTY_MAGIC = { 0x50, 0x4B, 0x05, 0x06 };

	private static final byte[] OLE2_MAGIC = { (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0 };

	private static final byte[] PDF_MAGIC = "%PDF".getBytes(StandardCharsets.US_ASCII);

	private static final byte[] UTF8_BOM = { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };

	private static final byte[] UTF16_BOM = { (byte) 0xFF, (byte) 0xFE };

	private static final byte[] UTF16_BE_BOM = { (byte) 0xFE, (byte) 0xFF };

	private ContentSniffer() {
	}

	/**
	 * What the leading bytes say the file is.
	 */
	public enum Kind {

		/** A text file in UTF-8 or UTF-16 without an unrecognised binary payload. */
		TEXT,
		/** A ZIP container: the package format of both XLSX and ODS. */
		ZIP_PACKAGE,
		/** A legacy OLE2 container: the package format of XLS and DOC. */
		OLE2_PACKAGE,
		PDF,
		/** A native executable or shared library. */
		EXECUTABLE,
		/** A byte stream that is neither recognisable text nor a known container. */
		BINARY,
		EMPTY

	}

	public static Kind sniff(byte[] content) {
		if (content == null || content.length == 0) {
			return Kind.EMPTY;
		}
		if (startsWith(content, ZIP_MAGIC) || startsWith(content, ZIP_EMPTY_MAGIC)) {
			return Kind.ZIP_PACKAGE;
		}
		if (startsWith(content, OLE2_MAGIC)) {
			return Kind.OLE2_PACKAGE;
		}
		if (startsWith(content, PDF_MAGIC)) {
			return Kind.PDF;
		}
		if (looksExecutable(content)) {
			return Kind.EXECUTABLE;
		}
		return isPlausibleText(content) ? Kind.TEXT : Kind.BINARY;
	}

	/**
	 * The file type the content itself implies, or {@link FileType#UNKNOWN} when
	 * the bytes cannot positively identify a type this module can read.
	 *
	 * <p>A ZIP can only be positively claimed as XLSX by looking inside it, which
	 * the parser does; here it is reported as {@link FileType#XLSX} only when the
	 * caller already asserted XLSX, so that the sniff can confirm rather than
	 * override the claim. Otherwise it stays {@link FileType#UNKNOWN}.
	 */
	public static FileType detectedType(byte[] content, FileType declared) {
		Kind kind = sniff(content);
		return switch (kind) {
			case ZIP_PACKAGE -> declared == FileType.XLSX ? FileType.XLSX : FileType.UNKNOWN;
			case TEXT -> declared == FileType.CSV ? FileType.CSV : FileType.UNKNOWN;
			case OLE2_PACKAGE, PDF, EXECUTABLE, BINARY, EMPTY -> FileType.UNKNOWN;
		};
	}

	/**
	 * @return true when the bytes look like portable or native executable code
	 */
	public static boolean looksExecutable(byte[] content) {
		if (content.length >= 2 && content[0] == 'M' && content[1] == 'Z') {
			return true;
		}
		if (content.length >= 4 && content[0] == 0x7F && content[1] == 'E' && content[2] == 'L'
				&& content[3] == 'F') {
			return true;
		}
		if (content.length >= 4 && content[0] == (byte) 0xCA && content[1] == (byte) 0xFE
				&& content[2] == (byte) 0xBA && content[3] == (byte) 0xBE) {
			return true;
		}
		if (content.length >= 2 && content[0] == '#' && content[1] == '!') {
			return true;
		}
		return false;
	}

	private static boolean isPlausibleText(byte[] content) {
		// A byte-order mark is proof of a text encoding, so it short-circuits the scan.
		if (startsWith(content, UTF8_BOM)) {
			return true;
		}
		if (startsWith(content, UTF16_BOM) || startsWith(content, UTF16_BE_BOM)) {
			return true;
		}
		// Sample only the head of the file: enough to classify it, bounded so a
		// 20 MB upload is not walked here purely to be sniffed.
		int inspected = Math.min(content.length, 4096);
		int suspicious = 0;
		for (int i = 0; i < inspected; i++) {
			int value = content[i] & 0xFF;
			// A NUL anywhere means this is not text; UTF-16 was already handled above.
			if (value == 0x00) {
				return false;
			}
			// Tab, LF and CR are the only control characters legitimate text contains.
			boolean allowedControl = value == 0x09 || value == 0x0A || value == 0x0D;
			if (value < 0x20 && !allowedControl) {
				suspicious++;
			}
		}
		// 1% tolerance rather than zero: some exports carry the occasional stray
		// control byte and refusing the whole file over one is unhelpful.
		return suspicious <= inspected / 100;
	}

	private static boolean startsWith(byte[] content, byte[] prefix) {
		if (content.length < prefix.length) {
			return false;
		}
		for (int i = 0; i < prefix.length; i++) {
			if (content[i] != prefix[i]) {
				return false;
			}
		}
		return true;
	}

	/**
	 * @return a lower-case, best-effort description for messages that must not
	 * echo file content (rule 5)
	 */
	public static String describe(byte[] content) {
		return sniff(content).name().toLowerCase(Locale.ROOT);
	}

}
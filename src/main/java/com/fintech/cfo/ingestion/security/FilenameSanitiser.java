package com.fintech.cfo.ingestion.security;

import java.util.Locale;
import java.util.Objects;

import com.fintech.cfo.ingestion.model.SanitisedFilename;

/**
 * Turns an untrusted upload filename into a {@link SanitisedFilename}.
 *
 * <p>The incoming name is attacker-controlled and may contain a path
 * ({@code ../../etc/passwd}), a Windows drive, a NUL, a bidi override, whitespace
 * or a reserved device name. All of that is removed or folded before the name is
 * ever combined with a storage prefix, so the result is a leaf name that cannot
 * address anything outside its own directory.
 *
 * <p>The transformation is intentionally lossy and one-way: it is not reversible
 * and is not used to locate the file again. Storage keys are generated separately
 * and are not derived from user input at all.
 *
 * <p><b>Why the whole name is rebuilt rather than merely escaped.</b> Escaping a
 * filename — replacing {@code /} with {@code _}, say — leaves the class of bug
 * where a new dangerous sequence appears in the <i>result</i> of the
 * transformation, and where the result is still only as safe as every future
 * consumer of it. Rebuilding from an allow-list means the output is safe by
 * construction: each surviving character was individually approved, so there is no
 * combination left to reason about. That property is what makes the result safe
 * to concatenate with a storage prefix without further checks.
 *
 * <p><b>Order of operations matters.</b> The leaf name is extracted before
 * characters are filtered, and filtering happens before the extension is split
 * off. Extracting first means path separators cannot survive into the output at
 * all; splitting the extension after filtering means the extension is derived
 * from already-vetted text rather than from raw attacker input.
 *
 * <p>Stateless and therefore thread-safe: all behaviour is in the passed-in
 * string, and the shared state is limited to immutable constants.
 */
public final class FilenameSanitiser {

	/**
	 * Cap on the final name, so a pathological upload cannot produce an unbounded
	 * storage key or overflow a filesystem's per-component length limit.
	 */
	private static final int MAX_LENGTH = 120;

	/**
	 * Cap on the extension. Anything longer is discarded rather than trusted,
	 * since a long "extension" is a way to smuggle a second filename past
	 * extension-based type checks.
	 */
	private static final int MAX_EXTENSION_LENGTH = 8;

	/** Used when stripping leaves nothing usable, so the result is never empty. */
	private static final String FALLBACK_STEM = "upload";

	/**
	 * Windows reserved device names.
	 *
	 * <p>Relevant even on Linux: these are stored in object storage and may later
	 * be synced to a Windows share, where a file called {@code CON} or
	 * {@code COM1} cannot be created and, depending on the layer, can behave
	 * unexpectedly. Prefixing the stem sidesteps the problem at a point where the
	 * original name is already known to be untrusted.
	 */
	private static final java.util.Set<String> RESERVED_DEVICE_NAMES = java.util.Set.of("CON", "PRN", "AUX", "NUL",
			"COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4",
			"LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

	/**
	 * The sanitisation pipeline. Each step narrows what the next step has to
	 * consider.
	 *
	 * <ol>
	 * <li>Reduce to a leaf name, discarding any directory component.</li>
	 * <li>Fold every character against the allow-list, dropping control and
	 * bidirectional characters outright.</li>
	 * <li>Collapse runs of separators and trim leading ones.</li>
	 * <li>Split off a vetted extension.</li>
	 * <li>Guarantee a non-empty stem, escape reserved names, cap total length.</li>
	 * </ol>
	 *
	 * <p>Steps 1-3 operate on the whole string while step 4 onwards treats the
	 * stem and extension differently, because the extension is the only part that
	 * may influence later type decisions and so needs its own validation.
	 */
	public SanitisedFilename sanitise(String originalFilename) {
		String candidate = extractLeafName(originalFilename);
		candidate = stripUnsafeCharacters(candidate);
		candidate = collapseAndTrim(candidate);

		String extension = extractExtension(candidate);
		// Remove the extension, and its dot, from the stem to avoid "name.ext.ext".
		String stem = extension.isEmpty() ? candidate : candidate.substring(0, candidate.length() - extension.length() - 1);
		// A name of only dots or a lone extension would otherwise produce an empty stem.
		if (stem.isEmpty()) {
			stem = FALLBACK_STEM;
		}
		stem = avoidReservedDeviceName(stem);
		// Truncate last so the cap applies to the final assembled name, not the stem alone.
		stem = truncateStem(stem, extension);
		return new SanitisedFilename(stem + (extension.isEmpty() ? "" : "." + extension), extension);
	}

	/**
	 * Prefixes a stem that collides with a Windows device name.
	 *
	 * <p>Upper-cases before comparing because the reserved names are
	 * case-insensitive on Windows, so {@code con} and {@code CON} are equally
	 * unusable. {@link Locale#ROOT} rather than the default locale, so a Turkish
	 * default cannot turn {@code "i"} into {@code "İ"} and defeat the match.
	 */
	private static String avoidReservedDeviceName(String stem) {
		if (RESERVED_DEVICE_NAMES.contains(stem.toUpperCase(Locale.ROOT))) {
			return "file-" + stem;
		}
		return stem;
	}

	/**
	 * Keeps only the final path segment, defeating path traversal.
	 *
	 * <p>Three separate defences are applied to the same idea, each covering a
	 * case the others miss:
	 *
	 * <ul>
	 * <li>Backslashes are folded to forward slashes first, so a Windows-style
	 * {@code ..\\..\\etc\\passwd} is reduced to a form the next step understands.
	 * Without this, a name surviving as a single backslash-free-looking segment
	 * could still be interpreted as a path by a Windows consumer.</li>
	 * <li>A NUL byte is folded to a separator too, so an embedded
	 * {@code "report.pdf\0.exe"} cannot smuggle a second extension past a
	 * consumer that stops reading at the NUL.</li>
	 * <li>Everything before the <i>last</i> separator is discarded. The last one is
	 * used rather than the first so that nested traversal collapses to a single
	 * harmless leaf; either would neutralise {@code ../} for storage purposes, but
	 * taking the last also strips the leading directories from an ordinary
	 * absolute path.</li>
	 * </ul>
	 *
	 * <p>Null yields the empty string, letting the caller proceed to the fallback
	 * stem rather than throwing — a missing name is a sanitisation outcome, not a
	 * programming error.
	 */
	private static String extractLeafName(String filename) {
		if (filename == null) {
			return "";
		}
		String normalised = filename.replace('\\', '/').replace('\u0000', '/');
		int lastSlash = normalised.lastIndexOf('/');
		String leaf = lastSlash >= 0 ? normalised.substring(lastSlash + 1) : normalised;
		return leaf.trim();
	}

	/**
	 * Rebuilds the string character by character from the allow-list.
	 *
	 * <p>Three classes of character are treated differently:
	 *
	 * <ul>
	 * <li><b>Control characters and bidi/format characters are dropped</b>, not
	 * replaced. Replacing them would leave a {@code _} in their place and could
	 * make two distinct inputs collide into the same output, which matters because
	 * the result may be used in comparisons. The bidi class is the important one:
	 * a right-to-left override can make a filename render as a different
	 * extension than it actually has, defeating a visual extension check by a
	 * reviewer while the bytes say otherwise.</li>
	 * <li><b>Whitespace becomes {@code _}</b> rather than being deleted, so
	 * "my report" and "myreport" stay distinguishable and a truncated-looking name
	 * is not silently created.</li>
	 * <li><b>Allowed characters pass through; everything else becomes {@code _}.</b>
	 * Substituting rather than dropping keeps a name from collapsing into a
	 * different valid name, and preserves a rough shape that helps a human
	 * recognise their own upload.</li>
	 * </ul>
	 */
	private static String stripUnsafeCharacters(String value) {
		Objects.requireNonNull(value, "value must not be null");
		StringBuilder builder = new StringBuilder(value.length());
		for (int i = 0; i < value.length(); i++) {
			char character = value.charAt(i);
			if (Character.isISOControl(character) || isBidiOrFormat(character)) {
				// Dropped entirely so it cannot affect rendering or reordering.
				continue;
			}
			if (Character.isWhitespace(character)) {
				builder.append('_');
				continue;
			}
			if (isAllowed(character)) {
				builder.append(character);
			}
			else {
				// Non-allow-listed printable character, e.g. '&' or a non-Latin letter.
				builder.append('_');
			}
		}
		return builder.toString();
	}

	/**
	 * Collapses repeated separators and removes leading ones.
	 *
	 * <p>Tracking the previous character rather than using a regular expression
	 * for runs is what keeps the scan single-pass and allocation-light on a path
	 * that runs for every upload. Leading separators are then removed because
	 * a name beginning with {@code _} or {@code -} is a common outcome of the
	 * previous step replacing a stripped character, and it reads as noise in
	 * object storage listings.
	 */
	private static String collapseAndTrim(String value) {
		StringBuilder builder = new StringBuilder(value.length());
		boolean previousWasSeparator = false;
		for (int i = 0; i < value.length(); i++) {
			char character = value.charAt(i);
			boolean separator = character == '_' || character == '-';
			if (separator && previousWasSeparator) {
				continue;
			}
			builder.append(character);
			previousWasSeparator = separator;
		}
		// Strip any leading run of '.', '_' and '-' left by the loop above.
		return builder.toString().replaceAll("^[._\\-]+", "");
	}

	/**
	 * Extracts a lower-cased extension if one is present and looks legitimate.
	 *
	 * <p>Returns the empty string — meaning "no extension" — in three cases: there
	 * is no dot, the dot is the final character, or what follows fails the length
	 * or character checks. Treating a doubtful extension as absent rather than as
	 * present is deliberate: the extension is used downstream to help decide the
	 * file type, and a fabricated one must not be able to steer that decision.
	 * Requiring alphanumeric-only also blocks a second embedded dot, which would
	 * otherwise turn {@code report.csv.exe} into a stem ending in {@code .csv}.
	 */
	private static String extractExtension(String value) {
		int lastDot = value.lastIndexOf('.');
		if (lastDot < 0 || lastDot == value.length() - 1) {
			return "";
		}
		String extension = value.substring(lastDot + 1).toLowerCase(Locale.ROOT);
		if (extension.length() > MAX_EXTENSION_LENGTH || !extension.chars().allMatch(Character::isLetterOrDigit)) {
			return "";
		}
		return extension;
	}

	/**
	 * Caps the stem so that stem plus extension stays within {@link #MAX_LENGTH}.
	 *
	 * <p>The budget is computed as the cap minus the room the extension needs,
	 * including its dot — the total is what gets stored and length-limited, so
	 * truncating the stem alone would not actually enforce the limit. The
	 * {@code Math.max(budget, 1)} guarantees a non-empty result even in the
	 * degenerate case of an extension that consumes the whole budget.
	 */
	private static String truncateStem(String stem, String extension) {
		int budget = MAX_LENGTH - (extension.isEmpty() ? 0 : extension.length() + 1);
		if (stem.length() <= budget) {
			return stem;
		}
		return stem.substring(0, Math.max(budget, 1));
	}

	/**
	 * The allow-list: ASCII letters, digits and three separators.
	 *
	 * <p>Deliberately ASCII-only. Unicode letters are excluded because homoglyphs
	 * — Cyrillic {@code а} rendered identically to Latin {@code a} — are a
	 * standard way to disguise an executable as a document. Restricting to
	 * {@code . _ -} beyond alphanumerics also removes every character with a
	 * meaning to a shell, a URL or a filesystem.
	 */
	private static boolean isAllowed(char character) {
		return (character >= 'a' && character <= 'z') || (character >= 'A' && character <= 'Z')
				|| (character >= '0' && character <= '9') || character == '.' || character == '_' || character == '-';
	}

	/**
	 * Detects bidirectional and invisible formatting characters.
	 *
	 * <p>Covers the embedding and override ranges (which can reverse how a
	 * filename <i>appears</i> without changing its bytes), the isolate and
	 * directional-mark ranges, zero-width characters, and the byte-order mark.
	 * A name containing these is either attempting to deceive a human reviewer
	 * or is simply malformed; both warrant rejection at this stage.
	 */
	private static boolean isBidiOrFormat(char character) {
		return (character >= '\u202A' && character <= '\u202E') || (character >= '\u2066' && character <= '\u2069')
				|| character == '\u200B' || character == '\u200C' || character == '\u200D' || character == '\u200E'
				|| character == '\u200F' || character == '\uFEFF';
	}

}
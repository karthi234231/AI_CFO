package com.fintech.cfo.ingestion.model;

import java.util.Locale;
import java.util.Objects;

/**
 * A filename that has been proven safe to use as a leaf name.
 *
 * <p>Three properties are guaranteed by construction, which is why this is a
 * type rather than a {@code String} that callers are asked to be careful with:
 *
 * <ul>
 * <li>no directory separator, no {@code ..} segment and no whitespace, so it
 * cannot escape a storage prefix or need quoting to be safe in a key;</li>
 * <li>no control characters, no bidi override and no NUL, so it cannot forge or
 * hide characters in a log line, an operator console or an export;</li>
 * <li>no leading dot and not a Windows reserved device name.</li>
 * </ul>
 *
 * @param displayName the name shown to users and used as the file's leaf name
 * @param extension   the lowercase extension without the dot, {@code ""} when absent
 */
public record SanitisedFilename(String displayName, String extension) {

	private static final java.util.Set<String> RESERVED_DEVICE_NAMES = java.util.Set.of("CON", "PRN", "AUX", "NUL", "COM1",
			"COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5",
			"LPT6", "LPT7", "LPT8", "LPT9");

	public SanitisedFilename {
		displayName = Objects.requireNonNull(displayName, "displayName must not be null");
		extension = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
		if (displayName.isBlank()) {
			throw new IllegalArgumentException("displayName must not be blank");
		}
		if (displayName.contains("/") || displayName.contains("\\") || displayName.contains("\u0000")) {
			throw new IllegalArgumentException("displayName must be a leaf name, was " + displayName);
		}
		if (".".equals(displayName) || "..".equals(displayName) || displayName.startsWith(".")) {
			throw new IllegalArgumentException("displayName must not be a dot segment, was " + displayName);
		}
		if (containsAnyWhitespaceOrControl(displayName)) {
			throw new IllegalArgumentException("displayName must not contain whitespace or control characters");
		}
		String stem = displayName;
		int dot = stem.indexOf('.');
		if (dot > 0) {
			stem = stem.substring(0, dot);
		}
		if (RESERVED_DEVICE_NAMES.contains(stem.toUpperCase(Locale.ROOT))) {
			throw new IllegalArgumentException("displayName must not be a reserved device name, was " + displayName);
		}
	}

	private static boolean containsAnyWhitespaceOrControl(String value) {
		for (int i = 0; i < value.length(); i++) {
			char character = value.charAt(i);
			if (Character.isWhitespace(character) || Character.isISOControl(character)) {
				return true;
			}
		}
		return false;
	}

	public boolean hasExtension() {
		return !this.extension.isEmpty();
	}

	@Override
	public String toString() {
		return this.displayName;
	}

}
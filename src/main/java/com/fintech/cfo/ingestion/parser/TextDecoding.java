package com.fintech.cfo.ingestion.parser;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Byte-order-mark aware decoding for delimited text.
 *
 * <p>An Excel "CSV UTF-8" export starts with {@code EF BB BF}. Left in the
 * buffer, that mark becomes part of the first header name and every later lookup
 * of that column fails with an error nobody can see. Detecting and stripping it
 * here — and, just as importantly, <em>not</em> silently guessing when the bytes
 * are not valid UTF-8 — is the difference between a file that parses and a file
 * that is rejected for a stated reason.
 */
final class TextDecoding {

	private TextDecoding() {
	}

	/**
	 * @param content       raw bytes
	 * @param defaultCharset used only when no byte-order mark is present
	 * @return the charset actually used and the offset at which the body starts
	 */
	static Decoded decode(byte[] content, Charset defaultCharset) {
		Objects.requireNonNull(content, "content must not be null");
		Charset fallback = defaultCharset == null ? StandardCharsets.UTF_8 : defaultCharset;
		// Each mark is 2 or 3 bytes wide and is reported as a body offset so the caller
		// never has to strip it again. Order matters only in that the checks are exact.
		if (startsWith(content, 0xEF, 0xBB, 0xBF)) {
			return new Decoded(StandardCharsets.UTF_8, 3, true);
		}
		if (startsWith(content, 0xFE, 0xFF)) {
			return new Decoded(StandardCharsets.UTF_16BE, 2, true);
		}
		if (startsWith(content, 0xFF, 0xFE)) {
			return new Decoded(StandardCharsets.UTF_16LE, 2, true);
		}
		// No mark: the configured default is used and, critically, not guessed at.
		// Invalid bytes under it are refused by decodesCleanly rather than replaced.
		return new Decoded(fallback, 0, false);
	}

	static String toText(byte[] content, int offset, Charset charset) throws CharacterCodingException {
		CharsetDecoderHolder.decodeStrictly(content, offset, charset);
		return new String(content, offset, content.length - offset, charset);
	}

	/**
	 * @return true when the bytes decode cleanly under {@code charset}; a text
	 * parser must refuse mojibake rather than turn it into plausible-looking data
	 */
	static boolean decodesCleanly(byte[] content, int offset, Charset charset) {
		try {
			CharsetDecoderHolder.decodeStrictly(content, offset, charset);
			return true;
		}
		catch (CharacterCodingException ex) {
			return false;
		}
	}

	private static boolean startsWith(byte[] content, int... prefix) {
		if (content.length < prefix.length) {
			return false;
		}
		for (int index = 0; index < prefix.length; index++) {
			if ((content[index] & 0xFF) != prefix[index]) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Byte-order mark result.
	 *
	 * @param charset    charset to decode the body with
	 * @param bodyOffset first byte of the body, past any mark
	 * @param hadMark    whether a mark was present and stripped
	 */
	record Decoded(Charset charset, int bodyOffset, boolean hadMark) {
	}

	/** Isolated so the strict decoder configuration lives in exactly one place. */
	private static final class CharsetDecoderHolder {

		private CharsetDecoderHolder() {
		}

		static void decodeStrictly(byte[] content, int offset, Charset charset) throws CharacterCodingException {
			java.nio.charset.CharsetDecoder decoder = charset.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT);
			decoder.decode(java.nio.ByteBuffer.wrap(content, offset, content.length - offset));
		}

	}

}
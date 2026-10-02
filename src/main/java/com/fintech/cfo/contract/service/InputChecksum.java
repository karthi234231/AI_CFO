package com.fintech.cfo.contract.service;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;
import java.util.function.Function;

import com.fintech.cfo.contract.model.VersionedTerm;
import com.fintech.cfo.shared.util.HashUtils;

/**
 * Builds the stable input checksum that makes a past calculation verifiable.
 *
 * <p>A stored financial result is only reproducible if the terms that produced it
 * can be proven. This helper renders every input to canonical text, sorts the rows
 * so that hash or query order cannot reach the digest, and hashes the result with
 * {@link HashUtils#sha256(byte[])}.
 *
 * <p>Two properties matter and are both deliberate:
 *
 * <ul>
 * <li><strong>Order independence.</strong> A repository that returns the same rows
 * in a different order is describing the same commercial position, so the checksum
 * must not move. Rows are sorted by their canonical form before hashing.</li>
 * <li><strong>Loss capture of the losers.</strong> Every <em>candidate</em> is
 * hashed, not just the winner. If a term that nearly won was displaced by an
 * overlapping correction, the difference lives in the candidate set, and a
 * checksum of the winners alone would attest to an outcome without attesting to
 * the decision.</li>
 * </ul>
 */
public final class InputChecksum {

	private InputChecksum() {
	}

	/**
	 * Digest of a query key plus a labelled group of canonical values per input
	 * list.
	 *
	 * @param queryKey already-rendered identification of what was asked
	 * @param groups label and values pairs, in a fixed order by the caller
	 * @return lowercase hex SHA-256
	 */
	public static String of(String queryKey, Group... groups) {
		Objects.requireNonNull(queryKey, "queryKey must not be null");
		StringBuilder builder = new StringBuilder(512);
		// Prefixed so a query key can never be mistaken for the first group's first
		// value in the rendered text, however the caller's parts happen to be shaped.
		builder.append("query:").append(queryKey).append('\n');
		for (Group group : groups) {
			Objects.requireNonNull(group, "group must not be null");
			// Group labels are part of the digest, so moving a value from one labelled
			// group to another changes the checksum even when the multiset of values
			// is identical.
			group.appendTo(builder);
		}
		return HashUtils.sha256(builder.toString().getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Digest of a single canonical value, for a result that stands on its own.
	 */
	public static String ofValue(String label, String... parts) {
		Objects.requireNonNull(label, "label must not be null");
		StringBuilder builder = new StringBuilder(128);
		builder.append(label).append(':');
		for (int index = 0; index < parts.length; index++) {
			if (index > 0) {
				builder.append('|');
			}
			builder.append(parts[index]);
		}
		return HashUtils.sha256(builder.toString());
	}

	/**
	 * One labelled, sorted group of canonical values inside a checksum.
	 *
	 * <p>Values are sorted so the group is independent of the order rows arrived
	 * in, and nulls are skipped so "not supplied" and "supplied as null" cannot
	 * produce different digests for the same commercial position.
	 */
	public record Group(String label, Collection<String> canonicalValues) {

		public Group {
			Objects.requireNonNull(label, "label must not be null");
			canonicalValues = canonicalValues == null ? java.util.List.of() : canonicalValues.stream()
					.filter(Objects::nonNull)
					.sorted(Comparator.naturalOrder())
					.toList();
		}

		/**
		 * A group of terms, hashed by each row's own canonical form.
		 */
		public static Group ofTerms(String label, Collection<? extends VersionedTerm> terms) {
			Objects.requireNonNull(terms, "terms must not be null");
			return new Group(label, terms.stream()
					.filter(Objects::nonNull)
					.map(VersionedTerm::canonical)
					.toList());
		}

		/**
		 * A group of arbitrary values, hashed through a caller-supplied renderer.
		 */
		public static <T> Group of(String label, Collection<T> values, Function<T, String> canonical) {
			Objects.requireNonNull(canonical, "canonical must not be null");
			return new Group(label, values.stream().filter(Objects::nonNull).map(canonical).toList());
		}

		private void appendTo(StringBuilder builder) {
			builder.append(this.label).append(':');
			for (String value : this.canonicalValues) {
				builder.append('\n').append(value);
			}
			builder.append('\n');
		}

	}

}

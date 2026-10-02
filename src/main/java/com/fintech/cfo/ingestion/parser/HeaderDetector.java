package com.fintech.cfo.ingestion.parser;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

import com.fintech.cfo.ingestion.enums.HeaderMode;

/**
 * Chooses where the header row is and turns header cells into usable column names.
 *
 * <p>Shared by the CSV and Excel readers so both make the same decision for the
 * same content — a header detected differently per format would mean the same
 * export validated differently depending on how it arrived.
 *
 * <p>The {@link HeaderMode#AUTO} test is a heuristic, and a deliberately
 * conservative one. A candidate row is treated as a header when it has at least
 * two non-blank cells, when most non-blank cells are not numbers and not dates,
 * and when at least one cell looks like a label (starts with a letter, short,
 * no digits-only shape). Requiring a letter-initial cell is what stops a
 * headerless numeric export like {@code INV-1,2024-01-31,100.50} from having its
 * own first data row promoted to a header and silently lost.
 *
 * <p>Where the heuristic is wrong, callers pin the answer with {@link
 * HeaderMode#FIRST_RECORD} or {@link HeaderMode#NONE}.
 */
public final class HeaderDetector {

	/** Longest value still plausible as a column label. */
	private static final int MAX_LABEL_LENGTH = 64;

	private static final Pattern LABEL = Pattern.compile("^[A-Za-z][A-Za-z0-9 _./()%'\\-]*$");

	private static final Pattern ISO_DATE = Pattern
		.compile("^\\d{4}-\\d{2}-\\d{2}([T ]\\d{2}:\\d{2}(:\\d{2})?)?$");

	private static final Pattern SLASHED_DATE = Pattern.compile("^\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}$");

	private HeaderDetector() {
	}

	/**
	 * @param rows       records in file order, index 0 is the first row of the file
	 * @param scanWindow how many rows to inspect; 1 means "only the first"
	 * @return 0-based index of the header row, or {@code -1} when no header was found
	 */
	public static int detect(List<List<String>> rows, int scanWindow) {
		Objects.requireNonNull(rows, "rows must not be null");
		if (scanWindow <= 0) {
			scanWindow = 1;
		}
		int limit = Math.min(scanWindow, rows.size());
		for (int index = 0; index < limit; index++) {
			if (looksLikeHeader(rows.get(index))) {
				return index;
			}
		}
		return -1;
	}

	public static boolean looksLikeHeader(List<String> cells) {
		int nonBlank = 0;
		int labelish = 0;
		int letterInitial = 0;
		for (String cell : cells) {
			if (cell == null || cell.isBlank()) {
				continue;
			}
			nonBlank++;
			// A cell that reads as data disqualifies itself from being a label, so a
			// row of dates and amounts never wins the vote.
			if (looksLikeValue(cell)) {
				continue;
			}
			String trimmed = cell.trim();
			if (trimmed.length() <= MAX_LABEL_LENGTH) {
				labelish++;
				// Requiring the letter-initial pattern is what stops a headerless numeric
				// export from promoting its own first data row and losing it.
				if (LABEL.matcher(trimmed).matches()) {
					letterInitial++;
				}
			}
		}
		// All three conditions must hold: at least two populated cells, at least one
		// that is unambiguously a label, and at least half the cells label-shaped.
		// Deliberately conservative — a false negative yields positional names, which
		// is recoverable; a false positive silently drops a data row.
		return nonBlank >= 2 && letterInitial >= 1 && labelish * 2 >= nonBlank;
	}

	/**
	 * Builds unique, addressable column names.
	 *
	 * <p>Blank headers become {@code column_n} and repeats become {@code name_2},
	 * {@code name_3}. Normalising here rather than failing keeps the parse total —
	 * a duplicate header is a schema problem the validator reports, not a reason
	 * to throw away the rows underneath it. The returned list preserves the order
	 * and the exact count of the header so a data row's field count can still be
	 * checked against it.
	 */
	public static List<String> normalise(List<String> headerCells) {
		Objects.requireNonNull(headerCells, "headerCells must not be null");
		List<String> names = new ArrayList<>(headerCells.size());
		for (int index = 0; index < headerCells.size(); index++) {
			String raw = headerCells.get(index);
			String candidate = raw == null ? "" : raw.trim();
			if (candidate.isEmpty()) {
				candidate = "column_" + (index + 1);
			}
			candidate = unique(candidate, names);
			names.add(candidate);
		}
		return names;
	}

	/**
	 * @return positional names for a file read with {@link HeaderMode#NONE}
	 */
	public static List<String> positionalNames(int count) {
		List<String> names = new ArrayList<>(Math.max(count, 0));
		for (int index = 1; index <= count; index++) {
			names.add("column_" + index);
		}
		return names;
	}

	/** @return true when this header contains a repeated name, ignoring case */
	public static boolean hasDuplicates(List<String> columnNames) {
		Objects.requireNonNull(columnNames, "columnNames must not be null");
		List<String> seen = new ArrayList<>();
		for (String name : columnNames) {
			String key = name.toLowerCase(Locale.ROOT);
			if (seen.contains(key)) {
				return true;
			}
			seen.add(key);
		}
		return false;
	}

	/** @return the first name that occurs more than once, or {@code null} */
	public static String firstDuplicate(List<String> columnNames) {
		Objects.requireNonNull(columnNames, "columnNames must not be null");
		List<String> seen = new ArrayList<>();
		for (String name : columnNames) {
			String key = name.toLowerCase(Locale.ROOT);
			if (seen.contains(key)) {
				return name;
			}
			seen.add(key);
		}
		return null;
	}

	private static String unique(String candidate, List<String> taken) {
		String result = candidate;
		int suffix = 2;
		while (containsIgnoreCase(taken, result)) {
			result = candidate + "_" + suffix;
			suffix++;
		}
		return result;
	}

	private static boolean containsIgnoreCase(List<String> values, String candidate) {
		for (String value : values) {
			if (value.equalsIgnoreCase(candidate)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * @return true when the cell carries data rather than a label: a decimal, a
	 * date in either common layout, or a boolean
	 */
	static boolean looksLikeValue(String cell) {
		String trimmed = cell.trim();
		if (trimmed.isEmpty()) {
			return false;
		}
		if (ISO_DATE.matcher(trimmed).matches() || SLASHED_DATE.matcher(trimmed).matches()) {
			return true;
		}
		if ("true".equalsIgnoreCase(trimmed) || "false".equalsIgnoreCase(trimmed)) {
			return true;
		}
		return isDecimal(trimmed);
	}

	private static boolean isDecimal(String value) {
		try {
			new BigDecimal(value);
			return true;
		}
		catch (NumberFormatException ex) {
			return false;
		}

	}

}
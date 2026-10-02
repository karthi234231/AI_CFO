package com.fintech.cfo.contract.extraction;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fintech.cfo.contract.enums.ContractTermType;
import com.fintech.cfo.shared.exception.ValidationException;

/**
 * Pulls clause candidates out of contract text.
 *
 * <p>This is a <strong>heuristic reader, not a parser of legal meaning</strong>, and
 * its output is only ever a proposal: {@link ExtractedContractTerm#needsReview()}
 * is true unless every part of a clause was matched literally. A clause found here
 * has to be confirmed by a person before it becomes a stored term, because the
 * alternative - an inferred start date becoming the effective date of a payment
 * term - is the kind of error that is invisible until an invoice is disputed.
 *
 * <p>Recognised clause types are a closed set keyed on heading text, so an
 * unrecognised heading yields no candidate at all rather than a wrong one. The
 * patterns are deliberately narrow: matching a heading is cheap, and a missed
 * clause costs an author one confirmation, while a false match costs a term that
 * silently applied to the wrong dates.
 *
 * <p>Stateless and side-effect free.
 *
 * <p>This is the deterministic implementation of {@link ContractTermExtractor}. A
 * document-interpreting adapter belongs to the AI milestone and is deliberately
 * absent here: this module states the capability and satisfies it without an
 * outbound call, so nothing in the pricing path can depend on a model being
 * reachable.
 */
public final class ContractTermExtractionService implements ContractTermExtractor {

	/**
	 * Confidence for a clause whose heading, window and text were all recognised.
	 */
	public static final int CONFIDENT = 100;

	/**
	 * Confidence for a clause whose heading was recognised but whose window was
	 * only partially present. A partial window is still extracted, because the
	 * author is better placed to confirm an open end than to supply a start date
	 * they already wrote.
	 */
	public static final int REVIEW_REQUIRED = 70;

	/**
	 * Confidence for a clause identified by body text alone, with no heading match.
	 */
	public static final int LOW_CONFIDENCE = 40;

	private static final Pattern HEADING = Pattern.compile("^\\s*(?:section|clause|article)?\\s*"
			+ "(?<type>[A-Z][A-Z _-]{2,40})\\s*(?:\\((?<title>[^)]*)\\))?\\s*[:.-]?\\s*$",
			Pattern.CASE_INSENSITIVE);

	private static final Pattern ISO_DATE = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");

	private static final Pattern LONG_DATE = Pattern.compile(
			"\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+(january|february|march|april|may|june|july|august|september|october|november|december)\\s+(\\d{4})\\b",
			Pattern.CASE_INSENSITIVE);

	private static final Pattern DOTTED_DATE = Pattern.compile("\\b(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})\\b");

	private static final String[] MONTHS = {"january", "february", "march", "april", "may", "june", "july", "august",
			"september", "october", "november", "december"};

	private final Set<ContractTermType> enabledClauseTypes;

	/**
	 * Recognises every clause type.
	 */
	public ContractTermExtractionService() {
		this(ContractTermType.all());
	}

	/**
	 * @param enabledClauseTypes the clause types this reader is allowed to
	 * recognise, so a document set can be limited to the vocabulary it uses
	 */
	public ContractTermExtractionService(List<ContractTermType> enabledClauseTypes) {
		Objects.requireNonNull(enabledClauseTypes, "enabledClauseTypes must not be null");
		this.enabledClauseTypes = new LinkedHashSet<>(enabledClauseTypes);
	}

	@Override
	public List<ExtractedContractTerm> extract(UUID contractId, String documentText) {
		Objects.requireNonNull(contractId, "contractId must not be null");
		Objects.requireNonNull(documentText, "documentText must not be null");
		List<ExtractedContractTerm> extracted = new ArrayList<>();
		for (String line : documentText.split("\\R")) {
			extractLine(contractId, line).ifPresent(extracted::add);
		}
		return List.copyOf(extracted);
	}

	private Optional<ExtractedContractTerm> extractLine(UUID contractId, String line) {
		// The whole pattern must match the whole line. A clause has to be a line of
		// its own; a heading embedded in a sentence is not read, because a false
		// positive here becomes a payment term applied to the wrong dates.
		Matcher heading = HEADING.matcher(line);
		if (!heading.matches()) {
			// No candidate at all rather than an unrecognised one: an empty list and a
			// guess must not look alike to the reviewer.
			return Optional.empty();
		}
		String headingText = heading.group("type").trim();
		String title = heading.group("title");
		Optional<ContractTermType> clauseType = matchClauseType(title == null ? headingText : title + " " + headingText);
		if (clauseType.isEmpty() || !this.enabledClauseTypes.contains(clauseType.get())) {
			return Optional.empty();
		}
		String description = headingText;
		if (title != null && !title.isBlank()) {
			description = description + " (" + title.trim() + ")";
		}
		return Optional.of(clause(contractId, clauseType.get(), description, line));
	}

	/**
	 * Builds the candidate. Confidence is derived from how much of the clause was
	 * matched literally rather than guessed, so a reader can triage by confidence
	 * instead of re-reading every clause.
	 */
	private static ExtractedContractTerm clause(UUID contractId, ContractTermType clauseType, String description,
			String line) {
		List<LocalDate> dates = readDates(line);
		// Positional: first date is the start, second is the end. A line naming one
		// date yields an open-ended clause, which is the honest reading and the one
		// an author is better placed to correct than to have invented.
		LocalDate from = dates.size() > 0 ? dates.get(0) : null;
		LocalDate to = dates.size() > 1 ? dates.get(1) : null;
		if (from != null && to != null && to.isBefore(from)) {
			// A reversed pair is a misreading, not a window. Keeping it would let a
			// clause whose dates are swapped become a term that can never apply.
			to = null;
		}
		// Confidence is the amount of the clause that was matched literally, not a
		// probability. No date at all is the weakest case: the heading was recognised
		// but there is nothing to attach a window to. CONFIDENT is reserved for a
		// fully literal match and is not reachable from this heuristic reader, so
		// every candidate it returns carries needsReview() == true.
		int confidence = from == null ? LOW_CONFIDENCE : REVIEW_REQUIRED;
		return new ExtractedContractTerm(contractId, clauseType, description, from, to, confidence);
	}

	/**
	 * All dates the line names, in the order they appear.
	 *
	 * <p>ISO first, then {@code 1 January 2026}, then {@code 01.02.2026}. A line
	 * that mixes formats yields whichever it matched; reading {@code 01.02.2026}
	 * as day-first or month-first is a judgement this reader refuses to make, so
	 * the dotted form is accepted only in the unambiguous day-first reading and the
	 * result is marked for review either way.
	 */
	private static List<LocalDate> readDates(String line) {
		List<LocalDate> dates = new ArrayList<>(2);
		Matcher iso = ISO_DATE.matcher(line);
		while (iso.find()) {
			dates.add(parseDate(iso.group(1)));
		}
		if (dates.isEmpty()) {
			Matcher longForm = LONG_DATE.matcher(line);
			while (longForm.find()) {
				dates.add(parseDate(longForm.group(3) + "-" + monthNumber(longForm.group(2)) + "-" + longForm.group(1)));
			}
		}
		if (dates.isEmpty()) {
			Matcher dotted = DOTTED_DATE.matcher(line);
			while (dotted.find()) {
				// Dotted form read day-first only. A month-first `01.02.2026` would
				// yield the opposite date and the reader refuses to make that
				// judgement; the clause still surfaces, marked for review.
				dates.add(parseDate(dotted.group(3) + "-" + dotted.group(2) + "-" + dotted.group(1)));
			}
		}
		return dates;
	}

	private static int monthNumber(String month) {
		String candidate = month.toLowerCase(Locale.ROOT);
		for (int index = 0; index < MONTHS.length; index++) {
			if (MONTHS[index].equals(candidate)) {
				return index + 1;
			}
		}
		throw new IllegalArgumentException("unmatched month: " + month);
	}

	/**
	 * @throws ValidationException when the matched text is not a real date, which
	 * means the pattern was widened into matching something else
	 */
	private static LocalDate parseDate(String text) {
		try {
			return LocalDate.parse(text);
		}
		catch (DateTimeParseException exception) {
			throw new ValidationException("unreadable date in contract text: " + text, exception);
		}
	}

	/**
	 * Matches a heading against the closed clause vocabulary.
	 *
	 * <p>Tried in both directions because contracts are inconsistent about whether
	 * the heading or the bracketed title holds the clause name.
	 */
	private static Optional<ContractTermType> matchClauseType(String text) {
		String normalized = text.toUpperCase(Locale.ROOT);
		for (ContractTermType candidate : ContractTermType.all()) {
			String code = candidate.code();
			if (normalized.contains(code) || normalized.replace('_', ' ').contains(code.replace('_', ' '))) {
				return Optional.of(candidate);
			}
		}
		return Optional.empty();
	}

	/**
	 * The clause types this reader recognises.
	 */
	public Set<ContractTermType> supportedClauseTypes() {
		return Set.copyOf(this.enabledClauseTypes);
	}

}
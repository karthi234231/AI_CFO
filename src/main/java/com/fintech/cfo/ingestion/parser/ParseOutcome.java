package com.fintech.cfo.ingestion.parser;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.fintech.cfo.ingestion.enums.ParseStatus;
import com.fintech.cfo.ingestion.enums.RejectionReason;
import com.fintech.cfo.ingestion.model.FileParseResult;
import com.fintech.cfo.ingestion.model.FileRejection;
import com.fintech.cfo.ingestion.model.ParsedRow;

/**
 * What the parse stage produced, expressed as the four states that actually
 * exist.
 *
 * <p>A {@link FileParseResult} carries a status enum, and an enum alone is a weak
 * promise: nothing stops a caller treating a failed parse as an empty one, or
 * accepting rows out of a partial result without saying so. A sealed hierarchy
 * makes the compiler enforce the distinction at every call site, so "rows were
 * read but the file was only partly seen" cannot be silently treated as "rows
 * were read".
 *
 * <ul>
 * <li>{@link Succeeded} — the whole file was seen and the rows are trustworthy;</li>
 * <li>{@link PartiallyRead} — rows were recovered, and the run must be reported
 * as partial with the reason attached;</li>
 * <li>{@link Empty} — the file parsed cleanly and held no rows;</li>
 * <li>{@link Refused} — the file could not be read at all; there are no rows and
 * a stated {@link FileRejection}.</li>
 * </ul>
 */
public sealed interface ParseOutcome
		permits ParseOutcome.Succeeded, ParseOutcome.PartiallyRead, ParseOutcome.Empty, ParseOutcome.Refused {

	/**
	 * @return the rows the reader produced; empty for {@link Refused} and
	 * {@link Empty}. Callers that need the rejected/skipped split must switch on
	 * the subtype and read it from the {@link FileParseResult}
	 */
	List<ParsedRow> rows();

	/** @return the parse status this outcome represents */
	ParseStatus status();

	/**
	 * @return the refusal reason, present only for {@link Refused}; the optional
	 * form is what keeps a caller from reading a reason off an outcome that has
	 * none
	 */
	default Optional<FileRejection> refusal() {
		return switch (this) {
			case Refused refused -> Optional.of(refused.rejection());
			case Succeeded _, PartiallyRead _, Empty _ -> Optional.empty();
		};
	}

	/**
	 * Classifies a parser result without re-deciding anything: the parser already
	 * knows whether it saw the whole file.
	 */
	static ParseOutcome of(FileParseResult result) {
		Objects.requireNonNull(result, "result must not be null");
		return switch (result.status()) {
			case SUCCESS -> new Succeeded(result);
			case PARTIAL -> new PartiallyRead(result);
			case EMPTY -> new Empty(result);
			case FAILED -> new Refused(rejectionOf(result));
		};
	}

	/**
	 * Takes the reason from the finding the parser already recorded rather than
	 * inventing one, so the reason reported here and the reason a future
	 * {@code ingestion_errors} insert writes are the same value.
	 */
	private static FileRejection rejectionOf(FileParseResult result) {
		return result.findings()
			.stream()
			.filter(finding -> finding.isError())
			.findFirst()
			.map(finding -> FileRejection.of(finding.reason(), finding.message()))
			.orElseGet(() -> FileRejection.of(RejectionReason.CORRUPT_FILE,
					result.failureReason().isEmpty() ? "the file could not be read" : result.failureReason()));
	}

	/** The whole file was read. */
	record Succeeded(FileParseResult result) implements ParseOutcome {

		public Succeeded {
			Objects.requireNonNull(result, "result must not be null");
		}

		@Override
		public List<ParsedRow> rows() {
			return this.result.rows();
		}

		@Override
		public ParseStatus status() {
			return ParseStatus.SUCCESS;
		}

	}

	/**
	 * Some rows were recovered and some of the file was never seen — a truncated
	 * quoted record, or the configured row limit reached. The rows are usable; the
	 * shortfall is not, and must be reported rather than left as a silent gap in a
	 * row count.
	 */
	record PartiallyRead(FileParseResult result) implements ParseOutcome {

		public PartiallyRead {
			Objects.requireNonNull(result, "result must not be null");
		}

		@Override
		public List<ParsedRow> rows() {
			return this.result.rows();
		}

		@Override
		public ParseStatus status() {
			return ParseStatus.PARTIAL;
		}

	}

	/** The file was readable and contained no rows. */
	record Empty(FileParseResult result) implements ParseOutcome {

		public Empty {
			Objects.requireNonNull(result, "result must not be null");
		}

		@Override
		public List<ParsedRow> rows() {
			return List.of();
		}

		@Override
		public ParseStatus status() {
			return ParseStatus.EMPTY;
		}

	}

	/** The file could not be read; there is nothing downstream to validate. */
	record Refused(FileRejection rejection) implements ParseOutcome {

		public Refused {
			Objects.requireNonNull(rejection, "rejection must not be null");
		}

		@Override
		public List<ParsedRow> rows() {
			return List.of();
		}

		@Override
		public ParseStatus status() {
			return ParseStatus.FAILED;
		}

	}

}
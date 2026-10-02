package com.fintech.cfo.opportunity.enums;

import java.util.List;

import com.fintech.cfo.shared.exception.ValidationException;

/**
 * What a finding explains about the opportunity it belongs to
 * ({@code opportunity_findings.finding_type VARCHAR(48)}).
 *
 * <p>A finding is the record's answer to "why should I believe this?" - it is not a
 * second amount. Findings explain the composition of the impact and the limits of
 * the inputs, so a reader can see which parts are arithmetic and which parts rest on
 * an assumption.
 *
 * <p>The variants here are the ones {@code OpportunityDetectionService} and
 * {@code OpportunityValidationService} actually raise. There is deliberately no
 * generic {@code NOTE} variant: a bucket that everything eventually lands in is how
 * a "3 unexplained findings" count becomes the normal state without anyone noticing
 * it.
 */
public sealed interface FindingType extends CodedEnum permits FindingType.VarianceComponent,
		FindingType.DataQuality, FindingType.TermGap {

	/** Width of {@code opportunity_findings.finding_type} in V8. */
	int MAX_CODE_LENGTH = 48;

	/**
	 * Every variant, in a fixed order that never depends on declaration order.
	 *
	 * <p>A method rather than a constant: a static field holding nested
	 * {@code INSTANCE} references cannot initialise, because the nested classes are
	 * themselves subclasses of the interface being initialised.
	 */
	static List<FindingType> all() {
		return List.of(VarianceComponent.INSTANCE, DataQuality.INSTANCE, TermGap.INSTANCE);
	}

	/**
	 * Resolves a stored {@code finding_type} value.
	 *
	 * @throws ValidationException if the value is blank or unknown
	 */
	static FindingType fromCode(String code) {
		String normalized = CodedEnum.normalise(code, "FindingType");
		for (FindingType candidate : all()) {
			if (candidate.code().equals(normalized)) {
				candidate.requireColumnWidth(MAX_CODE_LENGTH);
				return candidate;
			}
		}
		throw new ValidationException("unknown finding type code: " + code);
	}

	/**
	 * Whether a finding of this type reduces the confidence of the figure.
	 *
	 * <p>Drives the confidence ceiling {@code OpportunityValidationService} applies when
	 * a reviewer confirms a record that carries such a finding. A record explained by
	 * data-quality limits may be real and correctly quantified, and still not be
	 * {@code HIGH} confidence - the two questions are independent.
	 *
	 * @return true for findings that describe a limit on the inputs, false for a
	 *         finding that merely explains the arithmetic
	 */
	default boolean limitsConfidence() {
		return switch (this) {
			case VarianceComponent component -> false;
			case DataQuality dataQuality -> true;
			case TermGap termGap -> true;
		};
	}

	/** One component of the deviation, tied to the calculation result that produced it. */
	record VarianceComponent() implements FindingType {

		public static final VarianceComponent INSTANCE = new VarianceComponent();

		@Override
		public String code() {
			return "VARIANCE_COMPONENT";
		}

	}

	/** The underlying data limited or qualified the figure. */
	record DataQuality() implements FindingType {

		public static final DataQuality INSTANCE = new DataQuality();

		@Override
		public String code() {
			return "DATA_QUALITY";
		}

	}

	/**
	 * A commercial term was missing, ambiguous or outside its effective window, so the
	 * expected amount rests on an assumption that a reader must be told about.
	 */
	record TermGap() implements FindingType {

		public static final TermGap INSTANCE = new TermGap();

		@Override
		public String code() {
			return "TERM_GAP";
		}

	}

}
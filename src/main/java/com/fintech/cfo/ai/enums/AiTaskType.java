package com.fintech.cfo.ai.enums;

import java.util.List;

import com.fintech.cfo.shared.exception.ValidationException;

/**
 * Which kind of AI work a request performs.
 *
 * <p>The distinction matters because the two tasks are fenced differently:
 * {@link #EXTRACTION} pulls structured terms out of contract text and the
 * validator blocks any field that looks like a computed monetary figure, while
 * {@link #EXPLANATION} writes prose about an already-computed result and the
 * guardrail blocks any number the explanation did not receive in its context.
 * Keeping the enum means a prompt template can never be matched to the wrong
 * task by a typo in a string.
 */
public enum AiTaskType {

	/**
	 * Read a contract document and emit structured commercial terms only. The
	 * prompt points at {@code prompts/contract-term-extraction.txt}.
	 */
	EXTRACTION("prompts/contract-term-extraction.txt"),

	/**
	 * Explain a deterministic financial result in plain prose. The prompt points
	 * at {@code prompts/opportunity-explanation.txt}.
	 */
	EXPLANATION("prompts/opportunity-explanation.txt");

	private final String promptTemplate;

	AiTaskType(String promptTemplate) {
		this.promptTemplate = promptTemplate;
	}

	/**
	 * @return the classpath resource that holds this task's prompt template
	 */
	public String promptTemplate() {
		return this.promptTemplate;
	}

	/**
	 * Every variant, in declaration order.
	 *
	 * @return an unmodifiable list of all task types
	 */
	public static List<AiTaskType> all() {
		return List.of(EXTRACTION, EXPLANATION);
	}

	/**
	 * Resolves a task type from its persisted code.
	 *
	 * @param code the stored code ({@link #name()})
	 * @return the matching variant
	 * @throws ValidationException if the code is blank or unknown
	 */
	public static AiTaskType fromCode(String code) {
		if (code == null || code.isBlank()) {
			throw new ValidationException("AiTaskType code must not be blank");
		}
		for (AiTaskType candidate : all()) {
			if (candidate.name().equals(code.trim().toUpperCase())) {
				return candidate;
			}
		}
		throw new ValidationException("unknown AI task type code: " + code);
	}

}

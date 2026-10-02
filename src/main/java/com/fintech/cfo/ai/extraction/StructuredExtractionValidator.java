package com.fintech.cfo.ai.extraction;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import com.fintech.cfo.ai.enums.AiValidationStatus;
import com.fintech.cfo.ai.model.ExtractedCommercialTerm;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Validates the structure of an LLM's extraction output before it is trusted.
 *
 * <p>This is the extraction-side expression of the "AI never produces numbers"
 * rule (ADR-002): the model is asked for clauses, and the validator enforces
 * that it did not quietly hand back a figure. It does three things: it parses
 * the reply as JSON (rejecting prose), it checks every term against a fixed
 * vocabulary (rejecting invented categories), and it rejects any field name that
 * smells like a monetary amount — so a model that wanted to supply a computed
 * discount or a variance instead of a clause is stopped at the schema, before
 * the value can reach a report.
 */
public final class StructuredExtractionValidator {

	/**
	 * ObjectMapper is thread-safe once configured, so a single shared instance is
	 * safe across concurrent extraction calls; recreating one per call would only
	 * pay construction cost for nothing.
	 */
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/**
	 * Codes this module is allowed to emit. A duplicate of
	 * {@code com.fintech.cfo.contract.enums.ContractTermType}, kept in sync by
	 * hand: the {@code ai} module is forbidden from importing the {@code contract}
	 * package, so the vocabulary is copied here and the validator rejects any
	 * category that is not on this list — which is exactly how a model gets
	 * prevented from inventing a "DISCOUNT_PERCENT" clause that is really a
	 * computed financial quantity.
	 */
	static final Set<String> ALLOWED_TERM_TYPES = Set.of(
			"DELIVERY_TERMS", "PAYMENT_TERMS", "SERVICE_LEVEL", "RENEWAL", "LIABILITY_CAP",
			"TERMINATION_NOTICE", "DATA_RETENTION", "OTHER");

	/**
	 * Field names an extraction must never carry. Their presence means the model
	 * crossed the line from "clause" into "figure", and the item is rejected
	 * outright rather than trimmed, so a hallucinated amount can never leak into
	 * a term record.
	 */
	static final Set<String> FORBIDDEN_FIELDS = Set.of(
			"amount", "money", "price", "value", "total", "subtotal", "charge", "discount",
			"discount_amount", "discount_percent", "discountPercent", "netAmount", "grossAmount",
			"taxAmount", "variance", "variance_amount", "varianceAmount", "expected_amount",
			"expectedAmount", "actual_amount", "actualAmount");

	/**
	 * @param json the model's raw reply
	 * @return the validated terms, each marked {@link AiValidationStatus#InReview}
	 *         because an LLM-extracted clause is unconfirmed until inspected
	 * @throws com.fintech.cfo.shared.exception.ValidationException if the reply is
	 *         not JSON, not an array, or any term is missing, unknown or monetary
	 */
	public List<ExtractedCommercialTerm> validate(String json) {
		JsonNode root = parseJson(json);
		// The contract is an array of clause objects; an object or a bare string is
		// a model that did not follow its instructions, and is rejected rather than
		// guessed-from.
		if (!root.isArray()) {
			throw new com.fintech.cfo.shared.exception.ValidationException(
					"extraction output must be a JSON array of clause objects");
		}
		List<ExtractedCommercialTerm> terms = new ArrayList<>();
		int index = 0;
		for (JsonNode item : root) {
			terms.add(validateItem(item, index));
			index++;
		}
		return terms;
	}

	private ExtractedCommercialTerm validateItem(JsonNode item, int index) {
		if (!item.isObject()) {
			throw new com.fintech.cfo.shared.exception.ValidationException(
					"extraction item " + index + " is not a JSON object");
		}
		// The monetary-field check runs before anything is read, so a model that
		// tried to masquerade a figure as a clause is rejected wholesale.
		assertNoForbiddenField(item);
		String termType = requireText(item, "termType");
		if (!ALLOWED_TERM_TYPES.contains(termType)) {
			throw new com.fintech.cfo.shared.exception.ValidationException(
					"extraction item " + index + " has unknown termType: " + termType);
		}
		// description/text are aliases: "text" is accepted as a fallback label.
		String description = requireText(item, "description");
		if (description.isBlank() && item.has("text")) {
			description = item.get("text").asText();
		}
		if (description.isBlank()) {
			throw new com.fintech.cfo.shared.exception.ValidationException(
					"extraction item " + index + " must carry a non-blank description");
		}
		int pageNumber = item.has("pageNumber") ? item.get("pageNumber").asInt() : 1;
		if (pageNumber < 1) {
			throw new com.fintech.cfo.shared.exception.ValidationException(
					"extraction item " + index + " pageNumber must be at least 1");
		}
		// IN_REVIEW: extracted, unconfirmed. A human (or downstream check) must
		// promote this before the term is treated as authoritative.
		return new ExtractedCommercialTerm(termType, description, pageNumber, AiValidationStatus.InReview.INSTANCE);
	}

	private void assertNoForbiddenField(JsonNode item) {
		Iterator<String> names = item.fieldNames();
		while (names.hasNext()) {
			String name = names.next();
			if (FORBIDDEN_FIELDS.contains(name.toLowerCase())) {
				// The fence: a clause object carrying a number field is treated as
				// the model attempting to produce a figure, not a term.
				throw new com.fintech.cfo.shared.exception.ValidationException(
						"extraction must not carry a monetary field: " + name);
			}
		}
	}

	private String requireText(JsonNode item, String field) {
		JsonNode node = item.get(field);
		if (node == null || node.isNull() || node.asText().isBlank()) {
			throw new com.fintech.cfo.shared.exception.ValidationException(
					"extraction item is missing required field: " + field);
		}
		return node.asText().trim();
	}

	/**
	 * Recovers a JSON document from a reply that may be fenced in {@code ```}
	 * or padded with prose.
	 *
	 * <p>This is a defensive shim, not a spec parser: it locates the first
	 * structural token and hopes the model balanced its braces, which the parse
	 * that follows will either confirm or reject.
	 */
	private static JsonNode parseJson(String reply) {
		if (reply == null || reply.isBlank()) {
			throw new com.fintech.cfo.shared.exception.ValidationException(
					"extraction output was empty");
		}
		String cleaned = reply.replace("```json", "").replace("```", "").trim();
		int start = Math.min(firstIndexOf(cleaned, '['), firstIndexOf(cleaned, '{'));
		if (start < 0) {
			throw new com.fintech.cfo.shared.exception.ValidationException(
					"extraction output contains no JSON");
		}
		int end = Math.max(lastIndexOf(cleaned, ']'), lastIndexOf(cleaned, '}'));
		String json = (end > start ? cleaned.substring(start, end + 1) : cleaned.substring(start)).trim();
		try {
			return MAPPER.readTree(json);
		}
		catch (java.io.IOException ex) {
			throw new com.fintech.cfo.shared.exception.ValidationException(
					"extraction output is not valid JSON", ex);
		}
	}

	private static int firstIndexOf(String value, char candidate) {
		int at = value.indexOf(candidate);
		return at < 0 ? Integer.MAX_VALUE : at;
	}

	private static int lastIndexOf(String value, char candidate) {
		int at = value.lastIndexOf(candidate);
		return at < 0 ? -1 : at;
	}

}

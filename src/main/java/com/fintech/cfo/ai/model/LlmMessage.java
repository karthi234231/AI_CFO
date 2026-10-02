package com.fintech.cfo.ai.model;

/**
 * One message in an LLM chat request.
 *
 * <p>Bundling role and text in a record — rather than passing two parallel lists
 * — makes a malformed request structurally impossible, which matters here because
 * the {@link Role#SYSTEM} message is where every guardrail instruction lives.
 */
public record LlmMessage(Role role, String content) {

	/**
	 * The role the message plays in the conversation.
	 *
	 * <p>{@link #SYSTEM} is the security-relevant role: it carries the instructions
	 * that fence the model into extraction/explanation only. A system message must
	 * never be assembled from untrusted input, because that is the shape of a
	 * prompt-injection: an attacker who can place text into the system slot can
	 * rewrite the model's behaviour. The type exists partly so callers cannot
	 * accidentally promote untrusted text into the system role.
	 */
	public enum Role {

		/**
		 * Model instructions and guardrails. The only producer of system content
		 * is {@code AiContextService}; untrusted text is never injected here.
		 */
		SYSTEM,

		/** The user's request, including any document text or deterministic context. */
		USER,

		/** Model reply. Never constructed by this module. */
		ASSISTANT

	}

	public LlmMessage {
		if (role == null) {
			throw new IllegalArgumentException("role must not be null");
		}
		if (content == null || content.isEmpty()) {
			throw new IllegalArgumentException("content must not be empty");
		}
	}

}

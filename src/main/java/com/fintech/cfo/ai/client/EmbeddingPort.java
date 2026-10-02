package com.fintech.cfo.ai.client;

/**
 * Port behind which the embedding HTTP client sits.
 *
 * <p>Embeddings are used here only as a similarity signal — for ranking which
 * contract terms are relevant to an explanation — and never as a source of
 * meaning on their own. A vector is a position in space, not a financial
 * statement, so comparing vectors cannot by itself produce or favour a number;
 * that is why an embedding port is safe here even though an LLM port is not.
 *
 * @param text the text to embed
 * @return the embedding vector
 */
public interface EmbeddingPort {

	float[] embed(String text);

}

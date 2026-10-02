/**
 * Value types for the ingestion pipeline and the values persisted to the V3
 * ingestion tables.
 *
 * <p>Immutable throughout: a record that cannot change after the number it
 * justified has been reported is what makes the reported figure auditable
 * (rule 4). Collections are defensively copied, never handed out as the backing
 * list.
 *
 * <p>Every row carries a {@link com.fintech.cfo.ingestion.model.RowCoordinate}
 * naming its file and 1-based row, so any rupee downstream can be traced back to
 * the row that produced it.
 */
@NullMarked
package com.fintech.cfo.ingestion.model;

import org.jspecify.annotations.NullMarked;
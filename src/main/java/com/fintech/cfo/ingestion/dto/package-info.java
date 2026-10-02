/**
 * Transport shapes for the ingestion endpoints.
 *
 * <p>Pure records with {@code from} projections off the model types, carrying no
 * framework annotations: the web boundary is not this module's to define, and
 * keeping transport concerns out of it is what lets these shapes be unit-tested.
 *
 * <p>No response here echoes uploaded content. A rejection names the row, the
 * column and the reason; the file's bytes do not travel back (rule 5).
 */
@NullMarked
package com.fintech.cfo.ingestion.dto;

import org.jspecify.annotations.NullMarked;
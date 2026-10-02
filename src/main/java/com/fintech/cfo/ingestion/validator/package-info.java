/**
 * The validation gates applied to a parsed file.
 *
 * <p>Nothing here ever coerces a value to make it fit: a value that cannot be read
 * as its declared type produces a finding and refuses its row, so that no
 * reported figure was quietly changed on the way in (rule 3).
 *
 * <p>Amounts are parsed through {@code BigDecimal} only. No {@code double} or
 * {@code float} appears anywhere in this package, because binary floating point
 * cannot represent a rupee exactly (rule 2).
 */
@NullMarked
package com.fintech.cfo.ingestion.validator;

import org.jspecify.annotations.NullMarked;
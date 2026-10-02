/**
 * Closed state sets and coded enumerations of the canonical financial model.
 *
 * <p>Every type in this package is a closed set: a sealed interface when the set
 * carries behaviour, an enum when the value is a bare persisted code. Adding a
 * member therefore forces every {@code switch} over the set to be revisited at
 * compile time, which is what stops a new invoice status from silently
 * escaping the arithmetic and reconciliation paths.
 */
@NullMarked
package com.fintech.cfo.financial.enums;

import org.jspecify.annotations.NullMarked;

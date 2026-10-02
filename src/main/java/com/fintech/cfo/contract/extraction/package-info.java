/**
 * Reading commercial terms out of a source document.
 *
 * <p>Extraction is a pure transformation from an author's text to a candidate
 * value type. It does not store anything, does not trust itself, and produces a
 * value that must still satisfy the same invariants as a term read from storage.
 */
@NullMarked
package com.fintech.cfo.contract.extraction;

import org.jspecify.annotations.NullMarked;
/**
 * The untrusted-input gates: filename handling, dangerous content signatures and
 * upload authorisation.
 *
 * <p>Everything a caller passes in leaves this package as a proven type rather than
 * as a raw string — a {@link com.fintech.cfo.ingestion.model.SanitisedFilename}, a
 * {@link com.fintech.cfo.ingestion.model.FileSecurityResult}. There is no code
 * path where a caller can forget to sanitise, because the unsanitised value is
 * never passed on.
 *
 * <p>This is a content screen, not antivirus, and must not be described as
 * malware detection.
 */
@NullMarked
package com.fintech.cfo.ingestion.security;

import org.jspecify.annotations.NullMarked;
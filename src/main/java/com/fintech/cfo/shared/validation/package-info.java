/**
 * Guard clauses shared by every module's compact constructors.
 *
 * <p>These existed as twenty-six hand-copied private helpers before being centralised here.
 * The duplication was not cosmetic: copies had drifted, so a null amount raised
 * {@code NullPointerException} in one model and {@code ValidationException} in another, and
 * the same schema width was reported with two different messages. A guard that behaves
 * differently depending on which file it was copied into is not a guard.
 *
 * <p>All failures are {@link com.fintech.cfo.shared.exception.ValidationException}, so a bad
 * request is reported as a client error rather than surfacing as an unhandled 500.
 */
@NullMarked
package com.fintech.cfo.shared.validation;

import org.jspecify.annotations.NullMarked;

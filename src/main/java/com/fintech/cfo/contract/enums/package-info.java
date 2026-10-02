/**
 * Closed value sets mirroring the {@code VARCHAR} status and type columns of
 * {@code V5__create_contracts.sql}.
 *
 * <p>Each set is a {@link org.jspecify.annotations.NullMarked sealed} interface
 * with one record per variant, so a {@code switch} over it is exhaustive and the
 * compiler refuses to build once a variant is added without being handled.
 */
@NullMarked
package com.fintech.cfo.contract.enums;

import org.jspecify.annotations.NullMarked;

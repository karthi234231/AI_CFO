/**
 * Closed value sets owned by the AI module: task kinds, the processing lifecycle
 * of an AI run, and the validation status of AI-derived output.
 *
 * <p>State sets that carry per-variant behaviour are expressed as sealed
 * interfaces of records so a {@code switch} over them is a compile-time
 * exhaustiveness check, the same convention used in
 * {@code com.fintech.cfo.opportunity.enums} and
 * {@code com.fintech.cfo.financialtruth.enums}. Bare codes that carry no
 * behaviour stay plain enums, as {@code Currency} and
 * {@code ProcessingStatus} do in {@code shared}.
 *
 * <p>This package keeps its own copy of {@link CodedEnum} on purpose: module
 * boundaries forbid {@code ai} from importing the equally-named interface in
 * the {@code opportunity} or {@code contract} packages, and the contract is
 * identical word for word, so the duplication is deliberate rather than drift.
 */
@NullMarked
package com.fintech.cfo.ai.enums;

import org.jspecify.annotations.NullMarked;

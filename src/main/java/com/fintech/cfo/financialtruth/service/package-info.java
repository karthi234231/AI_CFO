/**
 * Orchestration that owns a run's lifecycle and its reproducibility claim. No
 * Spring, no I/O: every service here takes its collaborators, and where it needs
 * the time it takes a {@code Clock}.
 *
 * <p>{@link com.fintech.cfo.financialtruth.service.CalculationRunService} opens,
 * closes and fails a run, and refuses to close one with results that do not belong
 * to it or that cannot be reproduced from the recorded checksum and rule version.
 *
 * <p>{@link com.fintech.cfo.financialtruth.service.CalculationService} is the
 * convenience entry point that drives the whole lifecycle and guarantees a
 * throwaway run never reaches {@code COMPLETED}.
 *
 * <p>{@link com.fintech.cfo.financialtruth.service.ReproducibilityService} replays
 * a stored run against a fresh snapshot and reports which lines moved. It answers
 * the only question that matters about a financial finding months later: would we
 * have reached this number again?
 */
@NullMarked
package com.fintech.cfo.financialtruth.service;

import org.jspecify.annotations.NullMarked;
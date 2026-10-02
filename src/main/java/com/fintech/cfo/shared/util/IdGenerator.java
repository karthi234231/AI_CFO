package com.fintech.cfo.shared.util;

import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * Central identifier generation.
 *
 * <p>All primary keys are UUIDs so records can be created across ingestion,
 * calculation and evidence flows without a central sequence and without
 * leaking record counts.
 *
 * <p><b>Why this is a bean rather than a static call.</b> Collapsing the whole
 * class to {@code UUID.randomUUID()} would be the smaller change, and it was
 * rejected because the id strategy is exactly the kind of decision that
 * migrates — a future move to a snowflake or ULID scheme for index locality is
 * a one-file change when every caller already depends on this component, and a
 * repository-wide sweep when they do not. The bean is the seam.
 *
 * <p><b>Why random UUIDs rather than a sequence.</b> Three properties are bought
 * here. Ingestion can create records on several workers without contending on
 * one sequence, so a batch job does not serialize behind a bottleneck. An id
 * does not reveal how many records precede it, which a monotonically
 * increasing key discloses to anyone who holds one. And an id can be minted
 * before the row is inserted, which lets an object reference its own key
 * during construction rather than after a save.
 *
 * <p><b>What is not guaranteed.</b> {@link UUID#randomUUID()} is a type 4 UUID:
 * it carries no timestamp and does not sort in creation order. Nothing may
 * depend on id ordering — a newest-first query must sort on an explicit
 * timestamp column, not on the primary key.
 */
@Component
public class IdGenerator {

	/**
	 * @return a new random (type 4) UUID suitable for use as a primary key
	 */
	public UUID newId() {
		// Type 4, not type 1: a time-based variant would reintroduce the ordering
		// that the random choice exists to avoid.
		return UUID.randomUUID();
	}

	/**
	 * @return the same value as {@link #newId()} in UUID text form, for the many
	 *         call sites that store or transport an id as a string rather than as
	 *         a {@code UUID} column
	 */
	public String newIdAsString() {
		return newId().toString();
	}

}
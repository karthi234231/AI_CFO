package com.fintech.cfo.contract.model;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Shape shared by every V5 row that takes part in as-of resolution.
 *
 * <p>Exists so one deterministic resolver can serve {@code contract_terms},
 * {@code pricing_terms}, {@code discount_terms} and {@code commercial_rules}
 * instead of four near-identical copies that could drift apart.
 *
 * <p>Note the deliberate split between {@code termVersion} and the optimistic lock
 * {@code version}. {@code term_version} is business history - "the second version
 * of this clause" - and is the only thing resolution orders on. {@code version}
 * is a row write counter with no business meaning; ordering on it would let an
 * unrelated UPDATE change which terms a historical calculation used.
 */
public interface VersionedTerm {

	UUID id();

	EffectiveWindow effectiveWindow();

	/**
	 * The first date this term can be used on.
	 *
	 * <p>Derived from the window rather than declared per row, so a term cannot
	 * report a start date that disagrees with the window the resolver filters on,
	 * and so the precedence order has one accessor to read.
	 */
	default LocalDate effectiveFrom() {
		return effectiveWindow().effectiveFrom();
	}

	int termVersion();

	/**
	 * Stable, order-independent rendering of this row for the input checksum.
	 *
	 * <p>Must be derived from stored column values only. Including a row's
	 * {@code updated_at} timestamp would make a no-op rewrite look like a
	 * commercial change, and including a hash-ordered collection would make the
	 * checksum depend on iteration order.
	 */
	String canonical();

}

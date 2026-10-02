/**
 * Canonical, source-independent financial records.
 *
 * <p>Every type here is an immutable record that mirrors a V4 table column for
 * column: no {@code @Entity}, no lazy proxies, no framework types. The
 * constraints declared by the migration - {@code ck_accounting_periods_range},
 * {@code ck_invoice_lines_total}, the {@code VARCHAR} widths - are enforced in
 * compact constructors so an invalid row cannot exist in memory even before
 * persistence arrives.
 *
 * <p>Each record keeps a {@code SourceReference}. A canonical amount without a
 * pointer back to the source row that produced it is not evidence, so lineage is
 * part of the shape rather than an optional annotation.
 */
@NullMarked
package com.fintech.cfo.financial.model;

import org.jspecify.annotations.NullMarked;

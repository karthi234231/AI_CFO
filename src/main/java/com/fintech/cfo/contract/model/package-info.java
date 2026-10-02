/**
 * Value types mirroring the V5 contract tables.
 *
 * <p>Plain records and immutable types, deliberately not JPA entities: this module
 * owns no persistence. Every field maps one-to-one onto a V5 column so that
 * adding JPA later is a mechanical change rather than a redesign.
 */
@NullMarked
package com.fintech.cfo.contract.model;

import org.jspecify.annotations.NullMarked;

/**
 * Pure commercial-term resolution.
 *
 * <p>No Spring stereotypes, no repository, no clock. Every service is
 * constructor-injected and takes its collaborators as arguments, so the whole
 * engine is unit-testable with no infrastructure at all, and every as-of date
 * arrives as a parameter rather than being read from the system clock.
 */
@NullMarked
package com.fintech.cfo.contract.service;

import org.jspecify.annotations.NullMarked;

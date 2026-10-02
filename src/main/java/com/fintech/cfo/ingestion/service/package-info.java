/**
 * Stage orchestration: the security gate, the parse routing and the validation
 * facade, plus the projection of a finished run onto its persistable records.
 *
 * <p>All plain constructor injection with no Spring annotation. These services are
 * pure enough to construct directly in a test, and keeping the framework out of
 * them is what makes the tests real rather than mock-shaped.
 *
 * <p>No clock is read anywhere in this package: the as-of date and the upload
 * instant are injected, so re-running an upload later reproduces the same record
 * (rule 3).
 */
@NullMarked
package com.fintech.cfo.ingestion.service;

import org.jspecify.annotations.NullMarked;
/**
 * HTTP request and response shapes for the AI controllers.
 *
 * <p>Stubs only: the controllers in {@code com.fintech.cfo.ai.controller} are
 * left as placeholders per the module rules, so these types are not yet wired to
 * an endpoint. They are defined here so the port contracts they depend on are
 * concrete, and so the request bodies deliberately omit any identity or
 * organization field: per the security rules a tenant is taken from the
 * authenticated principal in {@link com.fintech.cfo.shared.security.SecurityContext},
 * never from a client-supplied body.
 */
@NullMarked
package com.fintech.cfo.ai.dto;

import org.jspecify.annotations.NullMarked;

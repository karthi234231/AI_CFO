/**
 * Input/output guardrails for the AI module.
 *
 * <p>All checks here are pure and side-effect-free, so a test can feed a prompt
 * or a reply and assert on the verdict with no model and no network. Two of the
 * checks are security-relevant signals rather than hard blocks: prompt-injection
 * and refusal detection are heuristics that flag an input for review, not a wall
 * that a determined model cannot walk around. The one check that is a hard
 * block on the financial-truth thesis is {@link #findForeignNumbers}, which
 * makes an explanation that cites a figure it was not given a
 * {@link com.fintech.cfo.ai.enums.AiValidationStatus#Disputed} artefact.
 */
@NullMarked
package com.fintech.cfo.ai.service;

import org.jspecify.annotations.NullMarked;

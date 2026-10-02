package com.fintech.cfo.platform.audit;

/**
 * Closed set of auditable business events.
 *
 * <p>Security and data events are deliberately separated from business events
 * so that a compliance reviewer can answer two different questions: "who
 * touched this data?" and "what happened in the business process?".
 *
 * <p><b>Why a closed enum.</b> Every constant becomes a persisted row value, so the
 * set is a storage contract: removing or renaming one orphans history that a
 * retrospective audit still has to read. Adding a constant is the safe direction.
 * A free-text event name would avoid that constraint and, in exchange, make the
 * trail unqueryable — a typo in a string literal produces an event nothing ever
 * groups by, discovered only when someone needs the answer.
 *
 * <p><b>The groups, top to bottom.</b> Authentication and session; tenant and
 * security posture; ingestion; canonical record mutation; contract term lifecycle;
 * the financial truth engine; evidence and lineage; the opportunity lifecycle;
 * investigations; actions and realized value; reporting; and administration.
 * Each constant names a transition, not a state: {@code OPPORTUNITY_ASSIGNED} and
 * {@code OPPORTUNITY_REJECTED} are events, and the current state of an opportunity
 * lives on the opportunity itself.
 *
 * <p><b>Scope of an event.</b> The value says what kind of thing happened; the
 * accompanying {@code entityType}/{@code entityId} says what it happened to.
 * That split is why the list can stay this small — a new entity type does not need
 * a new constant, only a new {@code entityType} string.
 */
public enum AuditEventType {

	// --- authentication / session ---
	LOGIN_SUCCEEDED,
	LOGIN_FAILED,
	LOGOUT,
	TOKEN_REFRESHED,
	ACCESS_DENIED,

	// --- tenant / security posture ---
	TENANT_RESOLVED,
	TENANT_SCOPE_VIOLATION,

	// --- ingestion ---
	FILE_UPLOADED,
	FILE_REJECTED,
	FILE_PARSE_FAILED,
	INGESTION_BATCH_STARTED,
	INGESTION_BATCH_COMPLETED,
	INGESTION_BATCH_FAILED,
	INGESTION_ROW_REJECTED,
	INGESTION_DUPLICATE_DETECTED,

	// --- canonical records ---
	RECORD_CREATED,
	RECORD_UPDATED,
	RECORD_DELETED,
	RECORD_MERGED,

	// --- contract terms ---
	CONTRACT_CREATED,
	CONTRACT_TERMS_CHANGED,
	CONTRACT_TERMS_VERSIONED,

	// --- financial truth engine ---
	CALCULATION_RUN_STARTED,
	CALCULATION_RUN_COMPLETED,
	CALCULATION_RUN_FAILED,
	VARIANCE_DETECTED,

	// --- evidence ---
	EVIDENCE_ATTACHED,
	LINEAGE_RECORDED,

	// --- opportunity lifecycle ---
	OPPORTUNITY_DETECTED,
	OPPORTUNITY_QUANTIFIED,
	OPPORTUNITY_VALIDATED,
	OPPORTUNITY_REJECTED,
	OPPORTUNITY_ASSIGNED,
	OPPORTUNITY_STATUS_CHANGED,

	// --- investigation ---
	INVESTIGATION_OPENED,
	INVESTIGATION_ASSIGNED,
	INVESTIGATION_CLOSED,

	// --- action / value ---
	ACTION_RECORDED,
	ACTION_EXECUTED,
	OUTCOME_RECORDED,
	VALUE_REALIZED,

	// --- reporting ---
	REPORT_GENERATED,
	REPORT_EXPORTED,

	// --- administration ---
	USER_CREATED,
	USER_DEACTIVATED,
	ROLE_ASSIGNED,
	ROLE_REVOKED,
	ORGANIZATION_CREATED,

	/** Administrative catch-all for changes not covered above. */
	SYSTEM_CONFIGURATION_CHANGED;

	/**
	 * Whether this event belongs to the security group.
	 *
	 * <p>A switch over the security constants rather than a test on a group field or
	 * a naming prefix. A prefix convention would silently classify a newly added
	 * constant by how it was spelled, whereas the switch forces the question to be
	 * re-asked whenever a constant is added — which is the point, because the
	 * classification is a judgement about retention and alerting, not a fact about
	 * the name.
	 *
	 * @return true for authentication and tenant-posture events
	 */
	public boolean isSecurityEvent() {
		// Exhaustive over the security cases with a default, so a new constant
		// compiles and reports false rather than failing to compile.
		return switch (this) {
			case LOGIN_SUCCEEDED, LOGIN_FAILED, LOGOUT, TOKEN_REFRESHED, ACCESS_DENIED, TENANT_RESOLVED,
					TENANT_SCOPE_VIOLATION -> true;
			default -> false;
		};
	}

}
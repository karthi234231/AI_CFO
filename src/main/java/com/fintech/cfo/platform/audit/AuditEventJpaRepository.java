package com.fintech.cfo.platform.audit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data access for {@link AuditEventEntity}.
 *
 * <p>Only read access is exposed here. Audit rows are written through
 * {@link AuditService} and are never mutated or deleted by application code.
 */
public interface AuditEventJpaRepository extends JpaRepository<AuditEventEntity, UUID> {

	/**
	 * One request's events, oldest first, paged.
	 *
	 * <p>Ascending because the diagnostic question is "what did this request do, in
	 * what order" — reversing the sequence would obscure the causal chain. Paged
	 * rather than unbounded because a retried or long-running request can log many
	 * events, and this is called from a support-facing path that must stay cheap.
	 *
	 * <p>Backed by {@code ix_audit_events_correlation}, declared on the entity.
	 */
	Page<AuditEventEntity> findByCorrelationIdOrderByOccurredAtAsc(String correlationId, Pageable pageable);

	/**
	 * One record's events, newest first, paged.
	 *
	 * <p>Descending because the question is asked while looking at the record now,
	 * so the most recent change is what matters. Backed by
	 * {@code ix_audit_events_entity}, whose leading column is {@code entityType} to
	 * match this predicate's leading restriction.
	 */
	List<AuditEventEntity> findByEntityTypeAndEntityIdOrderByOccurredAtDesc(String entityType, UUID entityId,
			Pageable pageable);

	/**
	 * One organization's events in a time window, newest first.
	 *
	 * <p>Newest first because compliance queries are asked about the recent past.
	 * Exactly matches {@code ix_audit_events_org_time} on
	 * {@code (organization_id, occurred_at)}, so the window is a range scan on the
	 * index rather than a filter over a tenant's whole history.
	 */
	List<AuditEventEntity> findByOrganizationIdAndOccurredAtBetweenOrderByOccurredAtDesc(UUID organizationId,
			Instant from, Instant to, Pageable pageable);

	/**
	 * Counts one kind of event for one organization.
	 *
	 * <p>Written as an explicit {@code @Query} rather than derived, for two reasons.
	 * A {@code count} projection in JPQL avoids hydrating entities just to discard
	 * them, and the predicate is stated visibly so a reviewer can see that the tenant
	 * is always part of the restriction — a derived method name would obscure
	 * whether scoping was applied at all.
	 *
	 * <p>Named parameters ({@code :organizationId}, {@code :eventType}) rather than
	 * positional ones, so the query and the Java signature can be read side by side
	 * without counting question marks. The {@code @Param} names are required
	 * because the build does not retain parameter names.
	 */
	@Query("""
			select count(e) from AuditEventEntity e
			where e.organizationId = :organizationId
			  and e.eventType = :eventType
			""")
	long countByOrganizationIdAndEventType(@Param("organizationId") UUID organizationId,
			@Param("eventType") AuditEventType eventType);

	/**
	 * One record's full history, newest first.
	 *
	 * <p>Functionally similar to the derived
	 * {@code findByEntityTypeAndEntityIdOrderByOccurredAtDesc} above, and kept
	 * alongside it because the {@code @Query} form states the ordering explicitly
	 * in JPQL rather than encoding it in the method name. Both resolve to the same
	 * index; the explicit form is the one to read when checking that history really
	 * is ordered, and it can express ordering on a column the name cannot.
	 */
	@Query("""
			select e from AuditEventEntity e
			where e.entityType = :entityType
			  and e.entityId = :entityId
			order by e.occurredAt desc
			""")
	List<AuditEventEntity> findEntityHistory(@Param("entityType") String entityType, @Param("entityId") UUID entityId,
			Pageable pageable);

}
package com.fintech.cfo.platform.audit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fintech.cfo.shared.security.SecurityContext;
import com.fintech.cfo.shared.security.SecurityPrincipal;
import com.fintech.cfo.shared.util.DateTimeUtils;
import com.fintech.cfo.shared.util.IdGenerator;

/**
 * Single entry point for recording audit facts.
 *
 * <h2>Why writes are isolated</h2>
 * Audit persistence runs in {@link Propagation#REQUIRES_NEW} so that a record
 * survives independently of the business transaction. The trade-off is
 * deliberate and has two halves that must be read together:
 *
 * <ul>
 * <li>If the business transaction later rolls back, its audit row remains. That
 * is intended: the attempt itself happened and is itself auditable.</li>
 * <li>If the audit write fails, it is swallowed and logged rather than
 * propagated. Losing an audit row must never roll back or fail a legitimate
 * financial operation.</li>
 * </ul>
 *
 * The consequence is that the audit trail is not transactionally consistent
 * with business data. Callers that need "the record exists and is audited, or
 * neither happened" must check the returned {@code AuditOutcome} and treat
 * {@link AuditOutcome#FAILED} as a real failure.
 */
@Service
public class AuditService {

	private static final Logger log = LoggerFactory.getLogger(AuditService.class);

	private static final int DEFAULT_LIMIT = 100;

	private final AuditEventJpaRepository auditEventRepository;

	private final IdGenerator idGenerator;

	private final DateTimeUtils dateTimeUtils;

	public AuditService(AuditEventJpaRepository auditEventRepository, IdGenerator idGenerator,
			DateTimeUtils dateTimeUtils) {
		this.auditEventRepository = auditEventRepository;
		this.idGenerator = idGenerator;
		this.dateTimeUtils = dateTimeUtils;
	}

	/**
	 * Records an event, filling the actor and timestamp from the current
	 * security context and clock when they were not supplied.
	 *
	 * @return the outcome so callers can escalate if the trail must be complete
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public AuditOutcome record(AuditEvent event) {
		SecurityPrincipal principal = SecurityContext.currentPrincipal();
		AuditEvent enriched = this.enrich(event, principal);
		try {
			AuditEventEntity entity = AuditEventEntity.from(enriched, this.idGenerator.newId());
			this.auditEventRepository.save(entity);
			return AuditOutcome.recorded(enriched.eventType(), enriched.entityType(), enriched.entityId());
		}
		catch (DataAccessException ex) {
			log.error("Audit write failed for eventType={} entityType={} entityId={} correlationId={}",
					enriched.eventType(), enriched.entityType(), enriched.entityId(), enriched.correlationId(), ex);
			return AuditOutcome.failed(enriched.eventType(), ex.getMessage());
		}
	}

	/**
	 * Records several events as one isolated unit. Either all rows are stored
	 * or none are, so a partially-written batch is never visible.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public AuditOutcome recordAll(List<AuditEvent> events) {
		if (events == null || events.isEmpty()) {
			return AuditOutcome.recorded(null, null, null);
		}
		SecurityPrincipal principal = SecurityContext.currentPrincipal();
		List<AuditEventEntity> entities = new ArrayList<>(events.size());
		try {
			for (AuditEvent event : events) {
				entities.add(AuditEventEntity.from(this.enrich(event, principal), this.idGenerator.newId()));
			}
			this.auditEventRepository.saveAll(entities);
			return AuditOutcome.recorded(null, null, null);
		}
		catch (DataAccessException ex) {
			log.error("Audit batch write failed for {} event(s)", Integer.valueOf(events.size()), ex);
			return AuditOutcome.failed(null, ex.getMessage());
		}
	}

	/**
	 * Convenience for the common case: an event about an entity in a tenant,
	 * attributed to the current user.
	 */
	public AuditOutcome recordForEntity(AuditEventType eventType, String entityType, UUID entityId, String details) {
		return record(new AuditEvent(tenantId(), actorId(), eventType, entityType, entityId, null, null, null, null,
				null, null, details, null, null, this.dateTimeUtils.now()));
	}

	@Transactional(readOnly = true)
	public List<AuditEvent> findByCorrelationId(String correlationId, int limit) {
		Integer size = normalizeLimit(limit);
		return this.auditEventRepository.findByCorrelationIdOrderByOccurredAtAsc(correlationId,
				org.springframework.data.domain.PageRequest.of(0, size)).stream().map(AuditService::toEvent).toList();
	}

	@Transactional(readOnly = true)
	public List<AuditEvent> findEntityHistory(String entityType, UUID entityId, int limit) {
		Integer size = normalizeLimit(limit);
		return this.auditEventRepository.findByEntityTypeAndEntityIdOrderByOccurredAtDesc(entityType, entityId,
				org.springframework.data.domain.PageRequest.of(0, size)).stream().map(AuditService::toEvent).toList();
	}

	@Transactional(readOnly = true)
	public List<AuditEvent> findByOrganizationAndPeriod(UUID organizationId, Instant from, Instant to, int limit) {
		Integer size = normalizeLimit(limit);
		return this.auditEventRepository
			.findByOrganizationIdAndOccurredAtBetweenOrderByOccurredAtDesc(organizationId, from, to,
					org.springframework.data.domain.PageRequest.of(0, size))
			.stream()
			.map(AuditService::toEvent)
			.toList();
	}

	@Transactional(readOnly = true)
	public long countByOrganizationAndType(UUID organizationId, AuditEventType eventType) {
		return this.auditEventRepository.countByOrganizationIdAndEventType(organizationId, eventType);
	}

	private AuditEvent enrich(AuditEvent event, SecurityPrincipal principal) {
		if (event.occurredAt() != null) {
			return event;
		}
		return new AuditEvent(event.organizationId(), event.actorId(), event.eventType(), event.entityType(),
				event.entityId(), event.correlationId(), event.requestId(), event.dataSource(), event.calculationRunId(),
				event.opportunityId(), event.outcome(), event.details(), event.ipAddress(), event.userAgent(),
				this.dateTimeUtils.now());
	}

	private static UUID tenantId() {
		SecurityPrincipal principal = SecurityContext.currentPrincipal();
		return principal == null ? null : principal.organizationUuid();
	}

	private static UUID actorId() {
		SecurityPrincipal principal = SecurityContext.currentPrincipal();
		return principal == null ? null : principal.userId().value();
	}

	private static int normalizeLimit(int limit) {
		if (limit <= 0) {
			return DEFAULT_LIMIT;
		}
		return Math.min(limit, 1000);
	}

	private static AuditEvent toEvent(AuditEventEntity entity) {
		return new AuditEvent(entity.getOrganizationId(), entity.getActorId(), entity.getEventType(),
				entity.getEntityType(), entity.getEntityId(), entity.getCorrelationId(), null, null,
				entity.getCalculationRunId(), entity.getOpportunityId(), entity.getOutcome(), entity.getDetails(), null,
				null, entity.getOccurredAt());
	}

	/**
	 * Result of an audit attempt.
	 *
	 * @param recorded   whether the row was durably written
	 * @param failure    diagnostic when {@code recorded} is {@code false}
	 * @param eventType  event that was attempted, for logging/correlation
	 * @param entityType entity the event concerned
	 * @param entityId   entity the event concerned
	 */
	public record AuditOutcome(boolean recorded, String failure, AuditEventType eventType, String entityType,
			UUID entityId) {

		static AuditOutcome recorded(AuditEventType eventType, String entityType, UUID entityId) {
			return new AuditOutcome(true, null, eventType, entityType, entityId);
		}

		static AuditOutcome failed(AuditEventType eventType, String failure) {
			return new AuditOutcome(false, failure, eventType, null, null);
		}
	}

}
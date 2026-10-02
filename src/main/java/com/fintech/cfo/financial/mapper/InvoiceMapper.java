package com.fintech.cfo.financial.mapper;

import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import com.fintech.cfo.financial.dto.InvoiceLineResponse;
import com.fintech.cfo.financial.dto.InvoiceResponse;
import com.fintech.cfo.financial.enums.ReconciliationStatus;
import com.fintech.cfo.financial.model.Invoice;
import com.fintech.cfo.financial.model.InvoiceLine;

/**
 * Maps the canonical invoice aggregate onto its API representation.
 *
 * <p>The reconciliation outcome is not derivable from the {@link Invoice}
 * record alone: it depends on the lines, which are loaded separately. It is
 * therefore supplied as an explicit second source parameter rather than being
 * guessed inside the mapper, so the reported status is always the one the
 * service actually computed from the detail.
 */
@Mapper(config = FinancialMappingSupport.class, uses = FinancialMappingSupport.class)
public interface InvoiceMapper {

	/**
	 * Currency of the invoice header.
	 *
	 * <p>Qualified by name and sourced from the whole record rather than from
	 * {@code subtotalAmount}, because the header has no {@code currency} component
	 * of its own: the currency is carried by the {@code Money} amounts and proven
	 * consistent by the compact constructor.
	 *
	 * @param invoice canonical invoice
	 * @return the ISO-4217 code shared by all three header amounts
	 */
	@Named("invoiceCurrency")
	default String invoiceCurrency(Invoice invoice) {
		return invoice.currency().value();
	}

	/**
	 * Currency of a single line.
	 *
	 * <p>Sourced from {@code unitPrice} because the line has no currency component
	 * either; the compact constructor has already proved the other three amounts
	 * agree with it, so any of the four would give the same answer.
	 *
	 * @param line canonical invoice line
	 * @return the ISO-4217 code shared by all four line amounts
	 */
	@Named("lineCurrency")
	default String lineCurrency(InvoiceLine line) {
		return line.unitPrice().currency().value();
	}

	// Header projection. Everything is qualified through FinancialMappingSupport
	// because none of the domain accessors is a JavaBean getter, so an implicit
	// mapping would silently produce nulls. Unlisted targets (id, customerId,
	// invoiceNumber, dates, externalKey, version) match by name and need no
	// annotation.
	//
	// Projects the invoice aggregate.
	//
	// @param invoice         canonical header
	// @param lines           its lines, in line-number order; empty when the caller
	//                        did not request them
	// @param reconciliation outcome already computed by the service from these very
	//                        lines
	// @return the wire representation
	@Mapping(target = "organizationId", source = "invoice.organizationId", qualifiedByName = "organizationUuid")
	@Mapping(target = "status", source = "invoice.status", qualifiedByName = "invoiceStatusCode")
	// The three reported amounts are passed through untouched: they are source
	// facts. Nothing here recomputes or corrects them, which is why the
	// reconciliation status is a separate explicit parameter.
	@Mapping(target = "subtotalAmount", source = "invoice.subtotalAmount", qualifiedByName = "amount")
	@Mapping(target = "taxAmount", source = "invoice.taxAmount", qualifiedByName = "amount")
	@Mapping(target = "totalAmount", source = "invoice.totalAmount", qualifiedByName = "amount")
	@Mapping(target = "currency", source = "invoice", qualifiedByName = "invoiceCurrency")
	// All four lineage fields come off the single SourceReference component; they
	// are flattened so a client can filter or display provenance without parsing a
	// nested object.
	@Mapping(target = "sourceSystem", source = "invoice.source", qualifiedByName = "sourceSystem")
	@Mapping(target = "sourceRecordId", source = "invoice.source", qualifiedByName = "sourceRecordId")
	@Mapping(target = "sourceFileId", source = "invoice.source", qualifiedByName = "sourceFileId")
	@Mapping(target = "sourceRowNumber", source = "invoice.source", qualifiedByName = "sourceRowNumber")
	// The nested list is mapped element-wise by the single-argument toResponse
	// overload below. Passing an empty list yields an empty list, not null: the
	// response contract says "not requested", never "unknown".
	@Mapping(target = "lines", source = "lines")
	// The reconciliation outcome is injected, not derived. The mapper has no way
	// to know whether the reported totals agree with the lines, and guessing
	// MATCHED would mark unverified money as verified.
	@Mapping(target = "reconciliationStatus", source = "reconciliation", qualifiedByName = "reconciliationCode")
	InvoiceResponse toResponse(Invoice invoice, List<InvoiceLine> lines, ReconciliationStatus reconciliation);

	// Line projection. Same contract as the header: amounts and lineage are
	// qualified, everything else matches by name. Note there is no
	// reconciliation field on a line - reconciliation is a header-level judgement
	// over the sum of the lines, and a per-line verdict would invite a client to
	// add up mismatched verdicts and think it had reconciled the invoice.
	//
	// Projects one line.
	//
	// @param line canonical invoice line
	// @return the wire representation
	@Mapping(target = "unitPrice", source = "unitPrice", qualifiedByName = "amount")
	@Mapping(target = "discountAmount", source = "discountAmount", qualifiedByName = "amount")
	@Mapping(target = "taxAmount", source = "taxAmount", qualifiedByName = "amount")
	@Mapping(target = "lineTotal", source = "lineTotal", qualifiedByName = "amount")
	@Mapping(target = "currency", source = "line", qualifiedByName = "lineCurrency")
	@Mapping(target = "sourceSystem", source = "source", qualifiedByName = "sourceSystem")
	@Mapping(target = "sourceRecordId", source = "source", qualifiedByName = "sourceRecordId")
	// No sourceFileId on the line DTO: the owning invoice carries it, and
	// duplicating it per line would let the two disagree.
	@Mapping(target = "sourceRowNumber", source = "source", qualifiedByName = "sourceRowNumber")
	// InvoiceLine is a version-less value record (no version component);
	// the header carries the optimistic-lock version, so there is nothing to project here.
	@Mapping(target = "version", ignore = true)
	InvoiceLineResponse toResponse(InvoiceLine line);

}

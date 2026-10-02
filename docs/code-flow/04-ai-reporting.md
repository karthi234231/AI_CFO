# 04 AI + Reporting + Investigation

## A. WHY
Explain trusted numbers (never make them), pack period
close PDFs, let auditors drill lineage. All read from
persisted truth.

## B. FLOW explain-only
Persisted run+opp -> AiContextService loads amounts+terms
+lineage -> LlmClient(prompt template) -> Guardrail checks
-> ExplanationResponse(text+citations+verdict). Extract
path: PDF text -> template -> StructuredValidator ->
human review queue (never direct to pricing).

## C. FILES all PH
ai 24: client 3, ctrl 2, dto 4, enum 3, extract 3,
model 3, pdf 2, svc 4. reporting 10, investigation 7.
prompts/*.txt are TODO stubs (must add placeholders).

## D. CONTRACTS TO HONOR
- cfo.ai.enabled=false default; timeout 60s retry 2.
- Guardrail: every Money in output exists in input; no FX;
  citations non-empty else BLOCKED.
- AiTaskType EXPLAIN_OPPORTUNITY/EXTRACT_TERMS;
  AiValidationStatus PASSED/FLAGGED/BLOCKED.
- Reporting: totals from FinancialImpact per currency,
  top opps, attribution summary -> PDF Content-Disposition.
- Investigation: GET lineage?opportunityId -> nodes+edges
  source->row->result->opp->action->outcome.

## E. GOTCHAS
- LLM inventing a number is P0. Extracted terms need
  reviewer before pricing. Prompts must say do-not-invent.

## F. IMPLEMENT
LlmClient chat(); Context loader; Guardrail validator;
Explanation/Interpretation ctrls; PdfExtraction (PDFBox);
ReportingService+renderer; InvestigationService DAG walk.

## G. NEXT -> 05 cross-cutting + audit V10.

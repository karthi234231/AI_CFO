# placeholders grouped (172 = 24 SPEC + 148 STUB)

## SPEC (design spec present, implement to spec)
financial controller 3: Customer/Invoice/ProductController.
financial normalization 4: Customer/FinancialData/Invoice/
ProductNormalizer. financial repository 5 + service 4
(Customer/FinancialData/Invoice/Product + InvoiceLine repo).
ingestion controller 1: IngestionController (thin adapter
over IngestionService+StatusService, 4 DTOs).
processing ingestion job 4: Configuration/Launcher/
Processor/Writer. contract extra: 3 repo are STUB-light.

## STUB by module (empty shell, see chapter F + tests)
- contract 5: ContractController, ContractTermController,
  3 repos (CommercialRule/Contract/ContractTerm).
- ingestion repos 3: Ingestion/Error/SourceFile.
- evidence 21: 2 ctrl, 3 dto, 4 model, 3 repo, 3 svc,
  +6 misc (see 03-4).
- opportunity 40, value 24, ai 24, reporting 10,
  investigation 7, identity 30: all STUB (see 03/04/05-F).

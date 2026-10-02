# Adr 004 Source Data Lineage

<!-- STUB. The decision record has not been written. This file is not inventing its
     content; it is recording what is already in force and where the reasoning lives.

     The decision is implemented and the most strongly tested of the four. The rule
     is stated in docs/architecture/module-implementation-rules.md section 5: a
     canonical record carries a SourceReference (source system, record id, file id,
     row number); a full source document is never stored inside a business entity,
     only a reference and a checksum; and a reported amount with no way back to its
     source row is not shippable. The chain itself is documented in
     docs/architecture/flow of files section 8 as calculation -> affected
     transaction -> canonical record -> source row -> source file.

     It is enforced by tests rather than merely asserted. FinancialTruthEngineTest
     pins that every result carries its source row and the contract terms evaluated;
     FinancialRegressionTest pins that every reported figure has both, and makes a
     monetary result without them a failure; CsvFileParserTest and
     ExcelFileParserTest pin that every parsed row carries file id, file name and
     row number; and FileValidationServiceTest pins that every rejected row carries
     coordinates too, so a row that was refused can still be found.

     An ADR would add what is missing: the storage decision behind "reference and
     checksum, never the document", the retention story for source files once the
     referring calculation is gone, and what happens when a source file is
     re-exported under the same identity with different content. -->

TODO: Add documentation content.

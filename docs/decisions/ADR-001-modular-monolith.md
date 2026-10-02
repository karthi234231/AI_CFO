# Adr 001 Modular Monolith

<!-- STUB. The decision record has not been written. This file is not inventing
     its content; it is recording what a reader can rely on today and where the
     evidence for the decision currently lives.

     The decision itself is already in force, not merely proposed:
       - the project is a single Maven module (pom.xml) with one deployable
         application (src/main/java/com/fintech/cfo/CfoApplication.java)
       - internal separation is by Java package, one package per business module
         (identity, ingestion, financial, contract, financialtruth, evidence,
         opportunity, investigation, value, ai, reporting, processing) plus shared
         and platform
       - the permitted dependency direction is stated in
         docs/architecture/module-implementation-rules.md section 2: a module may
         import shared and platform only, never another business module
       - exchange between modules is via a consumer-owned type or a port
         interface, never an import of the other module's internals

     So an ADR here would record the decision and its consequences; it would not
     change behaviour. What it would usefully add, and what is currently missing
     anywhere in the repository: the alternatives that were weighed (microservices
     per module, a shared-kernel library, a single-layer application), why a
     modular monolith was preferred, and what evidence would justify revisiting it.

     Note also that the boundary is currently enforced only by review. ArchUnit is
     a declared test dependency but the two classes in
     src/test/java/com/fintech/cfo/architecture/ are still placeholders, so nothing
     in the build enforces the rule this ADR would record. -->

TODO: Add documentation content.

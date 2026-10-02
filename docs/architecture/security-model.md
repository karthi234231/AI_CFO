# Security Model

<!-- STUB. Not yet written, and deliberately not filled in from guesswork.
     This file is reserved for the description of the security model: how a
     request is authenticated, where the SecurityPrincipal and tenant scope come
     from, how authorization is decided, and what the platform refuses to log.

     Until it is written, the substance lives in:
       docs/architecture/module-implementation-rules.md  sections 6 (Tenancy) and
         the audit note about never logging monetary values or tokens
       docs/explain/02-identity-tenancy-security.md      the full explanation
       src/main/java/com/fintech/cfo/identity/security/  the filter chain and
         the JWT authentication converter
       src/test/java/com/fintech/cfo/ingestion/FileValidationServiceTest.java
         the upload gate, which is the security-critical part that is actually
         covered by tests today

     Note that no test currently exercises the authentication or authorization
     chain end to end: the four classes under src/test/java/com/fintech/cfo/security/
     are generated placeholders. Treat that as a known gap rather than an implied
     guarantee. -->

TODO: Add documentation content.

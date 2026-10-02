# 00 Boot and boundaries

## A. WHY
JVM entry + DI root + config binding root. Without it no
beans, no JPA, no security chain. Also owns module boundary
rules (ArchUnit) so later teams cannot wire ingestion
directly to opportunity.

## B. FLOW startup
main() -> SpringApplication.run -> component scan
com.fintech.cfo.* -> ConfigurationPropertiesScan binds
cfo.* yml -> Flyway V1-V10 -> virtual threads on ->
filters (05-2) -> Tomcat :8080 -> /health ready.

## C. FILES
| file | st | job | keys |
|---|---|---|---|
| CfoApplication.java | REAL | entry | main L11-13 run() |
| pom.xml | REAL | deps | Boot 4.1.1, Java25, mapstruct ERROR on unmapped |
| application.yml | REAL | wiring | see 05-6 |

## D. DEEP DIVE CfoApplication.java
- L1 package: ArchUnit boundary root.
- L3-5 imports: SpringApplication=context+Tomcat+Flyway;
  SpringBootApplication=Config+AutoConfig+Scan;
  ConfigPropertiesScan=binds platform/config records.
- L7-8 annotations: enables above. Missing Scan =>
  properties null => NPE on upload.
- L11-13 main: forwards args so --profiles + env
  CFO_DB_URL/CFO_SERVER_PORT work in prod.

## E. GOTCHAS
- Removing Scan silently nulls IngestionProperties.
- Changing package breaks @ComponentScan discovery.

## G. NEXT -> 05-2 filters, 01 ingest.

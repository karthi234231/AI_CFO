# Adr 003 Economic Opportunity Record

<!-- STUB. The decision record has not been written. This file is not inventing its
     content; it is recording what is already in force and what an ADR would add.

     The decision is implemented in part. The record is the central artefact of the
     opportunity module (src/main/java/com/fintech/cfo/opportunity/), and the
     architecture fixes its shape in docs/architecture/flow of files section 9:
     identity, type, status, financial impact, affected transactions, evidence,
     calculation reference, confidence, validation status, owner, business context,
     next action, timestamps and lifecycle history. The controlled lifecycle
     detected -> evidenced -> quantified -> validated -> recommended ->
     approved/rejected -> acted -> measured -> attributed -> realized is stated in
     docs/architecture/all phases final goal.

     The reason this record exists rather than a per-detection alert is that the
     product promises an auditable chain from raw enterprise data to actual
     financial impact, and an alert cannot hold a chain.

     Not yet built: the module is substantially placeholder, and none of
     OpportunityDetectionTest, OpportunityLifecycleTest or OpportunityValidationTest
     exists as anything but a stub. So the lifecycle described above is currently a
     design rather than verified behaviour. An ADR would need to settle the parts
     still open - notably the deduplication rule, which decides when two detected
     line-level variances merge into one opportunity and when they stay separate. -->

TODO: Add documentation content.

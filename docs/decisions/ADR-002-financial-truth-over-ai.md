# Adr 002 Financial Truth Over Ai

<!-- STUB. The decision record has not been written. This file is not inventing
     its content; it is recording what is already in force and where the reasoning
     currently lives.

     The decision is already implemented and enforced throughout:
       - monetary results are produced by deterministic code only -
         ExpectedAmountCalculator, ActualAmountCalculator, VarianceCalculator and
         the rule classes in financialtruth - with BigDecimal, never a model
       - no AI component exists in the calculation path. src/main/java/com/fintech/cfo/ai/
         currently holds enums and model types only, no client
       - the calculation input must carry an explicit as-of date and rejects an
         as-of earlier than the invoice date
       - each result carries the source row, the contract terms evaluated, the rule
         code and version, and a human-readable explanation
       - the reproducibility suite (CalculationReproducibilityTest) exists
         specifically to prove the same snapshot yields the same answer, and to
         report any difference rather than hide it

     The rule it enforces is written down as a prohibition in
     docs/architecture/flow of files section 12: raw data -> LLM -> rupee variance
     is never an allowed flow. AI is positioned downstream, for explanation and
     extraction, never for the arithmetic.

     An ADR would add what is missing: the rejected alternatives, and specifically
     the failure mode being avoided - a probabilistic component in the path that
     produces a different number for the same input, which cannot be reconciled
     with a customer, reproduced in an audit, or attributed to a contract clause. -->

TODO: Add documentation content.

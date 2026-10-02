# Chapter template — every chapter in this handbook follows this exactly

This file is the contract for the handbook. Every chapter `NN-*.md` has the same
seven sections in the same order, so you always know where to look. If you are
reading a chapter and cannot find section **D**, the chapter is broken.

---

## A. WHY this module exists

One paragraph, business language, no class names in the first sentence. Answer:
*what problem does this solve for the finance team, and what would be unsafe
without it?* If a module's value is defensive (it stops something bad from
happening), say what the bad outcome would be.

State the module's hard invariants here as a short bulleted list — the rules a
future change must not break. These are the promises the rest of the chapter
keeps referring back to.

## B. FLOW — the runtime journey

A `mermaid` diagram, then the same path as **numbered steps**. Each step names
the file and the method that does the work, so a reader can jump straight to it.

Numbered steps must state, per step:

1. **Trigger** — what causes this to run (an HTTP call, a job, a rule match)
2. **Where** — file and method
3. **What it does** — in one sentence
4. **Why it does it that way** — the justification, not a restatement

Where a stage is designed but not implemented, mark it `[PLANNED]` inline and
say so plainly. Never describe unimplemented behaviour as though it runs.

## C. FILES — every file in the module

A table with **one row per file, no exceptions, no omissions, no "and friends"**.
Every file in the module must appear, including empty stubs.

| file | status | what it is for | key methods / lines |

- `status` is one of: `BUILT` (real implementation), `STUB` (empty shell — the
  chapter must say what belongs there), `CONFIG`, `MIGRATION`, `TEST`.
- "what it is for" is one sentence a non-author could follow.
- "key methods" lists the methods a reader should read first, with line numbers.

State the count explicitly: *"43 files: 27 built, 16 stub."* Reconcile it against
your own `Get-ChildItem` count before you finish.

## D. DEEP DIVE — method by method

**The heart of the chapter, and the reason this handbook exists.**

For each significant type, take it method by method. For **every** public method
and every non-obvious private one, document:

- **Signature** — copy it exactly
- **What it returns** — the contract, including the null / empty / zero cases
- **Step by step** — numbered, in execution order
- **Guards** — what is rejected, and with which exception
- **WHY** — the justification. This is the part that matters: why this logic
  and not the obvious alternative, what invariant it protects, what defect it
  prevents, what breaks if someone "simplifies" it. A method whose design is
  self-evident needs no WHY paragraph; say so in one line instead.

Where a value is a deliberate non-default (a rounding mode, a scale, a
threshold, a tolerance, an ordering), **name the trade-off it makes** and what
the other option would have cost.

Where the code and an existing comment disagree, or where behaviour looks wrong,
say so in a clearly marked **⚠ Review** note. Do not silently present a suspect
behaviour as correct. Do not change code — this is a handbook.

## E. GOTCHAS — what breaks if you get it wrong

Concrete failure modes, not general advice. For each:

- **Symptom** — what you would actually see
- **Cause** — the line or decision responsible
- **Blast radius** — what else is wrong with it (money, tenancy, determinism,
  evidence, security)
- **Fix** — the correct change

Rank them by consequence. A rounding-mode mistake that corrupts a reported
figure outranks a log-format mistake.

## F. TESTS — what locks this down

The test classes covering this module, and for each: **the invariant it
protects, stated as a business rule**, plus the highest-value individual test
cases. Say plainly what is **not** covered — an untested rule is a claim, not a
guarantee.

## G. WIRING — where this connects

What this module consumes, what is designed to consume it, and what must happen
before the wiring is real. Use the module-boundary rule from chapter 01: a
module may import `shared` and `platform` only, so cross-module exchange happens
through consumer-owned types or port interfaces at the integration milestone.
Name those types explicitly.

---

## Symbols used throughout

| symbol | meaning |
|---|---|
| `BUILT` | real implementation |
| `STUB` | empty shell; the chapter says what belongs there |
| `[PLANNED]` | designed, not yet executable |
| `→` | calls |
| `!` | gotcha or defect risk |
| ⚠ **Review** | something looks wrong; stated, not fixed |
| `file.java:42` | line 42 in the real file |

Line numbers refer to the working tree at the time of writing and are a reading
aid, not a contract — prefer method names when citing.

## Writing rules

- Explain **why**, never narrate what the code obviously does. `// increment i` teaches nothing.
- Every non-obvious decision gets a justification sentence. If you cannot justify it, flag it.
- Never invent behaviour. If the code does not do it, the chapter says the module is `STUB` and describes the contract instead.
- Keep prose in plain English. Business reason first, mechanism second.
- Prefer a table over a paragraph for anything enumerable.

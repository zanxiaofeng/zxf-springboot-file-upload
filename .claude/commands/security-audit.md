---
description: "Security audit for OWASP Top 10 compliance, vulnerability detection, and secure coding practices in Java/Spring Boot projects"
agent: security-auditor
context: fork
argument-hint: "file-or-directory path to audit"
---

Audit the target below by following the **security-auditor agent** instructions in full — the agent definition is the single source for OWASP scan categories, grep patterns, the security checklist, and the output format.

**Audit target:** $ARGUMENTS (if omitted, audit `src/main/` in full)

Additional emphasis for this pass:

- Error responses must not leak internals: verify `GlobalExceptionHandler` masking and the fixed generic 500 message
- Downstream notifications: fire-and-forget failures must be logged without propagating internal state
- Sensitive fields masked in logs per `logging.instructions.md` (`MaskUtils`)

Produce the risk report exactly as specified in the agent's Output Format (risk summary, critical findings, warnings, OWASP Top 10 compliance table).

# Ambiguous: "make links safer"

**Scenario:** AMBIGUOUS · **Recording:** `ambiguous`

## Requirement

Make the short links safer and more reliable for our users.

## What should happen

The requirement does not say what "safer" or "reliable" means (expiry? malicious-URL blocking? rate
limiting? private links?). The requirements agent should list these as open questions, assume sensible
defaults where it can, and leave the scope-changing ones blocking so the run pauses with a
`CLARIFICATION` approval. Answer by rejecting the approval with your decisions as the comment, e.g.:

> Links expire after 90 days by default (configurable per link). Rate-limit link creation to 30 per minute per IP. Do not add malicious-URL scanning in this change.

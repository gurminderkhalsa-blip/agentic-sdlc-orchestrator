# Greenfield: build the URL shortener

**Scenario:** GREENFIELD · **Recording:** `greenfield`

## Requirement

Build a URL shortener HTTP service.
- Clients submit a long http(s) URL and get back a short code and short URL. Submitting the same URL twice may return a new code.
- Visiting /{code} redirects to the original URL. Unknown codes return 404.
- Each link records how many times it was followed; clients can fetch a link's details and click count.
- Invalid URLs are rejected with a clear error. Codes must be unique, short (about 7 characters) and hard to guess.
- Store links with Spring Data JPA in the H2 database already configured in the project (persistence across restarts is not required).

## Recording note

`recordings/greenfield.json` was recorded with the final orchestrator (after the fixes listed in the
incident log). `recordings/greenfield-v1.json` is the first greenfield run, recorded while those fixes were
being made; its storage decision (JPA instead of an in-memory map) was a human rejection at the spec
checkpoint and is now part of the requirement, so a replay needs approvals only.

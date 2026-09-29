# Output rules
- Reply with exactly one JSON object and nothing else: no prose before or after, no markdown code fences.
- Use the field names given in the task exactly.
- Include a "decisions" array: [{"title", "rationale", "alternatives": [..]}] for every non-obvious choice you made. It becomes the audit trail.
- Never include credentials, API keys or passwords. Read secrets from configuration or environment variables.
- If feedback from earlier attempts is provided, fixing it is your first priority.

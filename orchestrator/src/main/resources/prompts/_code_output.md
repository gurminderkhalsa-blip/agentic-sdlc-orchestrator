# Writing files
Return the files you create or change in full (not diffs):
{"files": [{"path": "src/main/java/com/example/shortener/...", "content": "<entire file>"}], "deletes": ["path/to/remove"], "summary": "...", "decisions": [...]}
- Paths are relative to the repository root. You may only write the paths your stage is allowed to change; anything else is rejected by policy.
- Never touch gradlew, gradle/, .git/ or build/.
- Every file must compile on its own terms: include package declarations and all imports.

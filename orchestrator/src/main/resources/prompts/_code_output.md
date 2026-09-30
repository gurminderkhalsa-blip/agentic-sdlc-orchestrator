# Writing files
Return the files you create or change in full (not diffs):
{"files": [{"path": "src/main/java/com/example/shortener/...", "content": "<entire file>"}], "deletes": ["path/to/remove"], "summary": "...", "commitMessage": "...", "decisions": [...]}
- commitMessage is the git commit message for your change, in Conventional Commits form: a subject line "type(scope): what changed" (type is feat, fix, test, docs, refactor or chore; imperative mood; at most 72 characters), then a blank line and a short body explaining why and listing the user stories / acceptance criteria it serves.
- Paths are relative to the repository root. You may only write the paths your stage is allowed to change; anything else is rejected by policy.
- Never touch gradlew, gradle/, .git/ or build/.
- Every file must compile on its own terms: include package declarations and all imports.

package com.agentic.sdlc.workspace;

/**
 * One file an attempt changed.
 *
 * @param content       new content, or null when the file was deleted
 * @param previous      content at the last checkpoint, or null when the file is new
 */
public record FileChange(String path, String content, String previous) {

    public boolean isDelete() {
        return content == null;
    }

    public boolean existedBefore() {
        return previous != null;
    }
}

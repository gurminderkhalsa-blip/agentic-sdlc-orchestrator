package com.agentic.sdlc.llm;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Reads and writes recordings as pretty-printed JSON files, one per scenario. */
public class RecordingStore {

    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]+");

    private final Path dir;
    private final ObjectMapper json = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    private final Map<String, Recording> cache = new ConcurrentHashMap<>();

    public RecordingStore(Path dir) {
        this.dir = dir;
    }

    public Optional<Recording> load(String name) {
        return Optional.ofNullable(cache.computeIfAbsent(checked(name), n -> {
            Path file = dir.resolve(n + ".json");
            return Files.exists(file) ? json.readValue(file.toFile(), Recording.class) : null;
        }));
    }

    public synchronized void append(String name, Recording.Call call) {
        Recording recording = load(name).orElseGet(() -> new Recording(checked(name), null));
        recording.calls().removeIf(c -> c.nodeId().equals(call.nodeId()) && c.attemptNo() == call.attemptNo());
        recording.calls().add(call);
        cache.put(name, recording);
        try {
            Files.createDirectories(dir);
            json.writeValue(dir.resolve(name + ".json").toFile(), recording);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String checked(String name) {
        if (name == null || !SAFE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid recording name: " + name);
        }
        return name;
    }
}

package com.agentic.sdlc.common;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Name-to-component lookup, built from all Spring beans of one type. Fails fast on duplicate names. */
public class Registry<T extends Named> {

    private final String kind;
    private final Map<String, T> byName;

    public Registry(String kind, Collection<T> components) {
        this.kind = kind;
        this.byName = components.stream().collect(Collectors.toMap(Named::name, Function.identity(),
                (a, b) -> {
                    throw new IllegalStateException("Duplicate " + kind + " name: " + a.name());
                }, TreeMap::new));
    }

    public T get(String name) {
        return find(name).orElseThrow(() -> new NotFoundException("Unknown " + kind + ": " + name));
    }

    public Optional<T> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    public boolean contains(String name) {
        return byName.containsKey(name);
    }

    public Set<String> names() {
        return byName.keySet();
    }
}

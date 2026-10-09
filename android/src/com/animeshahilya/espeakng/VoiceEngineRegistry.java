/*
 * Copyright (C) 2026 Animesh Ahilya
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 */

package com.animeshahilya.espeakng;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central registry of pluggable TTS voice synthesis engines.
 *
 * <p>Enables clean modular extension of speech engines while unifying
 * interruption, lifecycle, and memory management.
 */
public final class VoiceEngineRegistry {

    private static final VoiceEngineRegistry INSTANCE = new VoiceEngineRegistry();

    public static VoiceEngineRegistry getInstance() {
        return INSTANCE;
    }

    private final Map<String, VoiceEngine> mEngines = new ConcurrentHashMap<>();

    private VoiceEngineRegistry() {
    }

    /** Registers a voice synthesis engine. */
    public void register(VoiceEngine engine) {
        if (engine != null && engine.getId() != null) {
            mEngines.put(engine.getId(), engine);
        }
    }

    /** Unregisters an engine by id. */
    public VoiceEngine unregister(String id) {
        return id != null ? mEngines.remove(id) : null;
    }

    /** Returns the engine registered with {@code id}, or null if not found. */
    public VoiceEngine getEngine(String id) {
        return id != null ? mEngines.get(id) : null;
    }

    /** Returns all currently registered engines. */
    public Collection<VoiceEngine> getEngines() {
        return Collections.unmodifiableCollection(snapshot());
    }

    /** Snapshot: an engine callback may register/unregister mid-broadcast. */
    private java.util.List<VoiceEngine> snapshot() {
        return new java.util.ArrayList<>(mEngines.values());
    }

    /** Runs {@code action} on every engine; one engine's failure never stops the rest. */
    private void forEachEngine(java.util.function.Consumer<VoiceEngine> action) {
        for (VoiceEngine engine : snapshot()) {
            try {
                action.accept(engine);
            } catch (Throwable ignored) {
            }
        }
    }

    /** Broadcasts stop to all registered engines. */
    public void stopAll() {
        forEachEngine(VoiceEngine::onStop);
    }

    /** Broadcasts memory trim to all registered engines. */
    public void trimAll(int level) {
        forEachEngine(engine -> engine.onTrimMemory(level));
    }

    /** Unloads all loaded models across all registered engines. */
    public void unloadAll() {
        forEachEngine(VoiceEngine::unloadAll);
    }

    /**
     * Clears all registered engines.
     *
     * <p>Test-only: production code must {@link #unregister(String)} individual
     * engines instead. Clearing the process-wide singleton from a unit test
     * that shares the process with the service would drop live engines.
     */
    public void clear() {
        mEngines.clear();
    }
}

/*
 * Copyright (C) 2026 Animesh Ahilya
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 */

package com.animeshahilya.espeakng;

import android.content.Context;
import android.content.SharedPreferences;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class VoiceEngineRegistryTest {

    private static class TestEngine implements VoiceEngine {
        final String id;
        final String name;
        final AtomicBoolean stopped = new AtomicBoolean(false);
        final AtomicInteger trimmedLevel = new AtomicInteger(-1);
        final AtomicBoolean unloaded = new AtomicBoolean(false);

        TestEngine(String id, String name) {
            this.id = id;
            this.name = name;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public boolean isAvailable(Context context) {
            return true;
        }

        @Override
        public boolean isEnabled(SharedPreferences prefs) {
            return true;
        }

        @Override
        public void onStop() {
            stopped.set(true);
        }

        @Override
        public void onTrimMemory(int level) {
            trimmedLevel.set(level);
        }

        @Override
        public void unloadAll() {
            unloaded.set(true);
        }
    }

    private final VoiceEngineRegistry registry = VoiceEngineRegistry.getInstance();

    @Before
    @After
    public void cleanup() {
        registry.clear();
    }

    @Test
    public void registersAndRetrievesEngines() {
        TestEngine engine1 = new TestEngine("engine1", "Engine One");
        TestEngine engine2 = new TestEngine("engine2", "Engine Two");

        registry.register(engine1);
        registry.register(engine2);

        assertEquals(2, registry.getEngines().size());
        assertSame(engine1, registry.getEngine("engine1"));
        assertSame(engine2, registry.getEngine("engine2"));

        assertNull(registry.getEngine("unknown"));
        assertSame(engine1, registry.unregister("engine1"));
        assertNull(registry.getEngine("engine1"));
        assertEquals(1, registry.getEngines().size());
    }

    @Test
    public void broadcastsStopTrimAndUnloadToAllRegisteredEngines() {
        TestEngine engine1 = new TestEngine("e1", "E1");
        TestEngine engine2 = new TestEngine("e2", "E2");

        registry.register(engine1);
        registry.register(engine2);

        registry.stopAll();
        assertTrue(engine1.stopped.get());
        assertTrue(engine2.stopped.get());

        registry.trimAll(80);
        assertEquals(80, engine1.trimmedLevel.get());
        assertEquals(80, engine2.trimmedLevel.get());

        registry.unloadAll();
        assertTrue(engine1.unloaded.get());
        assertTrue(engine2.unloaded.get());
    }
}

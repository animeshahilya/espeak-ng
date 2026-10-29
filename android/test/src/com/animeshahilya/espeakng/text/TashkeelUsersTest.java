/*
 * Copyright (C) 2026 Animesh Ahilya
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 */

package com.animeshahilya.espeakng.text;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.File;

/** The vowel-mark session stays open until the last loaded copy of its voice closes. */
public class TashkeelUsersTest {
    @Test
    public void lastCopyCloses() {
        final File voice = new File("build/tmp/tashkeel-users/ar_JO-kareem-medium");
        Tashkeel.retain(voice);
        Tashkeel.retain(voice); // a second copy (a preload racing a load)
        Tashkeel.close(voice); // the duplicate is dropped
        assertEquals(1, Tashkeel.users(voice));
        Tashkeel.close(voice);
        assertEquals(0, Tashkeel.users(voice));
        Tashkeel.close(voice); // an extra close is harmless
        assertEquals(0, Tashkeel.users(voice));
    }
}

/*
 * Copyright (C) 2026 Animesh Ahilya
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 */

#ifndef PIPER_PHONEMIZER_H
#define PIPER_PHONEMIZER_H

/*
 * Phonemizes text for a Piper neural voice with this fork's own eSpeak NG
 * dictionaries and rules, exactly the way piper-phonemize does it upstream,
 * so a stock Piper model hears the phoneme strings it was trained on - but
 * with every pronunciation fix (Indian schwa rules, en-in, user dictionary
 * [[phoneme]] overrides) this fork adds.
 *
 * Output: malloc'd UTF-8, one record per eSpeak clause, records separated by
 * PIPER_RECORD_SEP and fields by PIPER_FIELD_SEP:
 *
 *   <kind> FS <start> FS <end> FS <ipa> RS
 *
 *   kind   'S' when the clause ends a sentence, 'C' otherwise
 *   start  code point offset of the clause in the input text
 *   end    code point offset just past the clause
 *   ipa    IPA phonemes, language-switch flags "(en)" removed, the clause's
 *          own punctuation appended as Piper expects (". " ", " "? " ...)
 *
 * Returns NULL on failure (unknown eSpeak voice, OOM). The caller holds
 * whatever lock serializes the rest of the eSpeak API; this changes the
 * active eSpeak voice.
 */
#define PIPER_RECORD_SEP '\x1e'
#define PIPER_FIELD_SEP  '\x1f'

char *piper_phonemize(const char *espeak_voice, const char *text_utf8,
                      int allow_phoneme_input);

#endif

/*
 * Copyright (C) 2026 Animesh Ahilya
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 */

/*
 * eSpeak NG -> Piper phonemizer. See piperPhonemizer.h for the contract.
 *
 * Mirrors piper-phonemize's phonemize_eSpeak(): one
 * espeak_TextToPhonemesWithTerminator() call per clause, IPA output, the
 * "(lang)" switch flags filtered out, and the clause terminator turned back
 * into the punctuation phoneme the models were trained with. The only
 * deliberate difference: a clause that ends without punctuation Piper knows
 * (a bare line break, a very long run-on clause eSpeak split itself) gets a
 * space, where piper-phonemize glued the next clause's first word onto the
 * previous clause's last one; and full stops written without a following
 * space (danda, CJK) and paragraph breaks still end in "." (see
 * PIPER_TERMINATOR_MASK).
 */

#include <stdlib.h>
#include <string.h>

#include <espeak-ng/speak_lib.h>

#include "piperPhonemizer.h"

/* translate.h (internal): allows [[phonemes]] in the input, which is how the
 * user dictionary's phoneme overrides reach the engine. espeak_Synth() sets it
 * from its espeakPHONEMES flag; espeak_TextToPhonemes() has no such flag, so
 * it is set here for the duration of the call and restored afterwards. */
extern int option_phoneme_input;

/* Clause terminator encoding, translate.h. */
#define CLAUSE_INTONATION_FULL_STOP   0x00000000
#define CLAUSE_INTONATION_COMMA       0x00001000
#define CLAUSE_INTONATION_QUESTION    0x00002000
#define CLAUSE_INTONATION_EXCLAMATION 0x00003000
#define CLAUSE_TYPE_CLAUSE            0x00040000
#define CLAUSE_TYPE_SENTENCE          0x00080000
#define CLAUSE_PERIOD      (40 | CLAUSE_INTONATION_FULL_STOP   | CLAUSE_TYPE_SENTENCE)
#define CLAUSE_COMMA       (20 | CLAUSE_INTONATION_COMMA       | CLAUSE_TYPE_CLAUSE)
#define CLAUSE_QUESTION    (40 | CLAUSE_INTONATION_QUESTION    | CLAUSE_TYPE_SENTENCE)
#define CLAUSE_EXCLAMATION (45 | CLAUSE_INTONATION_EXCLAMATION | CLAUSE_TYPE_SENTENCE)
#define CLAUSE_COLON       (30 | CLAUSE_INTONATION_FULL_STOP   | CLAUSE_TYPE_CLAUSE)
#define CLAUSE_SEMICOLON   (30 | CLAUSE_INTONATION_COMMA       | CLAUSE_TYPE_CLAUSE)
#define CLAUSE_PARAGRAPH   (70 | CLAUSE_INTONATION_FULL_STOP   | CLAUSE_TYPE_SENTENCE)
#define CLAUSE_OPTIONAL_SPACE_AFTER   0x00008000
/* Piper masks the terminator down to pause + intonation + type, but keeps
 * CLAUSE_OPTIONAL_SPACE_AFTER, which eSpeak sets on terminators that need no
 * space after them: the Devanagari danda, the Urdu full stop, CJK and
 * Arabic marks. None of those then matched below, so their sentences reached
 * the model with no final punctuation (flat, unfinished intonation). Masking
 * the bit out too makes "।" a "." as it is for eSpeak itself. */
#define PIPER_TERMINATOR_MASK (0x000FFFFF & ~CLAUSE_OPTIONAL_SPACE_AFTER)

typedef struct {
  char *data;
  size_t len;
  size_t cap;
  int failed;
  int is_allocated;
} buf_t;

static void buf_init_stack(buf_t *b, char *stack_buf, size_t stack_cap)
{
  b->data = stack_buf;
  b->len = 0;
  b->cap = stack_cap;
  b->failed = 0;
  b->is_allocated = 0;
  if (stack_cap > 0) b->data[0] = '\0';
}

static void buf_free(buf_t *b)
{
  if (b->is_allocated && b->data) {
    free(b->data);
  }
  b->data = NULL;
  b->len = 0;
  b->cap = 0;
  b->is_allocated = 0;
}

static void buf_append(buf_t *b, const char *s, size_t n)
{
  if (b->failed || n == 0) return;
  if (b->len + n + 1 > b->cap) {
    size_t cap = b->cap ? b->cap : 256;
    while (b->len + n + 1 > cap) cap *= 2;
    char *p;
    if (b->is_allocated) {
      p = (char *)realloc(b->data, cap);
    } else {
      p = (char *)malloc(cap);
      if (p && b->len > 0) {
        memcpy(p, b->data, b->len + 1);
      }
      b->is_allocated = 1;
    }
    if (p == NULL) {
      b->failed = 1;
      return;
    }
    b->data = p;
    b->cap = cap;
  }
  memcpy(b->data + b->len, s, n);
  b->len += n;
  b->data[b->len] = '\0';
}

static void buf_char(buf_t *b, char c)
{
  buf_append(b, &c, 1);
}

static void buf_int(buf_t *b, long v)
{
  char tmp[24];
  int i = (int)sizeof(tmp);
  int neg = v < 0;
  unsigned long u = neg ? (unsigned long)(-v) : (unsigned long)v;
  do {
    tmp[--i] = (char)('0' + (u % 10));
    u /= 10;
  } while (u && i > 1);
  if (neg) tmp[--i] = '-';
  buf_append(b, tmp + i, sizeof(tmp) - (size_t)i);
}

/* Code points in [s, s + bytes): counts UTF-8 lead bytes. */
static long count_code_points(const char *s, size_t bytes)
{
  long n = 0;
  for (size_t i = 0; i < bytes; i++) {
    if (((unsigned char)s[i] & 0xC0) != 0x80) n++;
  }
  return n;
}

/* Appends phonemes minus "(xx)" language-switch flags. eSpeak wraps words it
 * reads with another language's rules in them; they are not phonemes, and a
 * Piper model has no ids for the letters inside. */
static void append_without_language_flags(buf_t *b, const char *ph)
{
  int in_flag = 0;
  const char *run = ph;
  for (const char *p = ph; *p; p++) {
    if (in_flag) {
      if (*p == ')') {
        in_flag = 0;
        run = p + 1;
      }
    } else if (*p == '(') {
      buf_append(b, run, (size_t)(p - run));
      in_flag = 1;
    }
  }
  if (!in_flag) buf_append(b, run, strlen(run));
}

char *piper_phonemize(const char *espeak_voice, const char *text_utf8,
                      int allow_phoneme_input)
{
  if (espeak_voice == NULL || text_utf8 == NULL) return NULL;
  if (espeak_SetVoiceByName(espeak_voice) != EE_OK) return NULL;

  const int saved_phoneme_input = option_phoneme_input;
  option_phoneme_input = allow_phoneme_input ? 1 : 0;

  buf_t out = {0};
  out.is_allocated = 1;
  buf_append(&out, "", 0);
  const size_t text_len = strlen(text_utf8);
  const void *cursor = text_utf8;
  long cp_pos = 0; /* code points consumed so far */

  while (cursor != NULL) {
    const char *before = (const char *)cursor;
    int terminator = 0;
    const char *ph = espeak_TextToPhonemesWithTerminator(
        &cursor, espeakCHARS_UTF8, espeakPHONEMES_IPA, &terminator);
    const char *after = (const char *)cursor;

    /* The decoder hands back a pointer into our own buffer while text
     * remains; anything else means we can no longer map clauses back to the
     * text, so the rest is reported as one span. */
    size_t consumed;
    if (after == NULL || after < text_utf8 || after > text_utf8 + text_len
        || before < text_utf8 || before > text_utf8 + text_len || after < before) {
      consumed = (size_t)(text_utf8 + text_len - (before >= text_utf8 && before <= text_utf8 + text_len ? before : text_utf8 + text_len));
      cursor = NULL;
    } else {
      consumed = (size_t)(after - before);
    }
    const long start = cp_pos;
    cp_pos += count_code_points(before, consumed);

    if (ph == NULL) break;

    char clause_stack[512];
    buf_t clause;
    buf_init_stack(&clause, clause_stack, sizeof(clause_stack));
    append_without_language_flags(&clause, ph);

    const int punct = terminator & PIPER_TERMINATOR_MASK;
    const int ends_sentence =
        (terminator & CLAUSE_TYPE_SENTENCE) == CLAUSE_TYPE_SENTENCE || cursor == NULL;
    if (clause.len == 0) {
      /* Nothing speakable (a leading "¿", a lone mark): a punctuation-only
       * record would become a model run of its own. */
    } else if (punct == CLAUSE_PERIOD || punct == CLAUSE_PARAGRAPH) {
      buf_char(&clause, '.');
    } else if (punct == CLAUSE_QUESTION) {
      buf_char(&clause, '?');
    } else if (punct == CLAUSE_EXCLAMATION) {
      buf_char(&clause, '!');
    } else if (punct == CLAUSE_COMMA) {
      buf_append(&clause, ", ", 2);
    } else if (punct == CLAUSE_COLON) {
      buf_append(&clause, ": ", 2);
    } else if (punct == CLAUSE_SEMICOLON) {
      buf_append(&clause, "; ", 2);
    } else if (!ends_sentence) {
      buf_char(&clause, ' ');
    }

    if (clause.failed) {
      buf_free(&clause);
      out.failed = 1;
      break;
    }
    if (clause.len > 0) {
      buf_char(&out, ends_sentence ? 'S' : 'C');
      buf_char(&out, PIPER_FIELD_SEP);
      buf_int(&out, start);
      buf_char(&out, PIPER_FIELD_SEP);
      buf_int(&out, cp_pos);
      buf_char(&out, PIPER_FIELD_SEP);
      buf_append(&out, clause.data, clause.len);
      buf_char(&out, PIPER_RECORD_SEP);
    }
    buf_free(&clause);
  }

  option_phoneme_input = saved_phoneme_input;
  if (out.failed) {
    buf_free(&out);
    return NULL;
  }
  return out.data;
}

---
name: translating
description: Adds/updates a message key across all three locale bundles — @core/src/main/resources/messages_fa.properties, @core/src/main/resources/messages.properties, @core/src/main/resources/messages_ar.properties — Farsi is the reference locale; English and Arabic are translations of it, each written to read naturally in that language's own web/UI register, not as a literal word-for-word rendering. Trigger whenever a message key (error, validation, success, or email template) is added, removed, or reworded in any one of the three files.
---

# Translating (`core`)

@core/src/main/resources/messages_fa.properties is the reference locale — its header says
so, and it's where a key's intended meaning is decided first. @core/src/main/resources/messages.properties
(en) and @core/src/main/resources/messages_ar.properties (ar) are translations of that
meaning, not of each other and not of literal English phrasing.

## Process

1. Write/edit the Farsi string first. Decide the meaning there.
2. Translate to English and Arabic from the Farsi meaning, not by mechanically converting
   the English string to Arabic (English is itself already one translation step removed).
3. Add the key to all three files in the same change, same key name, same position
   (keep the three files' key ordering identical so a reviewer can diff them line-by-line).
4. Keep placeholders identical across all three: `{0}`, `{min}`, `{max}` etc. must appear
   in every locale's version of a key, untranslated and untouched.

## Register and fluency

- Match what a real website in that language actually says for this kind of message —
  not a textbook-literal translation. Each language has idiomatic patterns for errors,
  confirmations, and form validation that don't map 1:1 onto each other.
- Farsi: formal register (فرمایید-style imperative), matches this codebase's existing
  `validation.*`/`error.*` keys (e.g. `لطفاً ... فرمایید`, not the blunt imperative `کن`).
- Arabic: formal MSA register with plural/formal address (`يُرجى`, `كم`-suffixed possessives
  for "your"), matching the existing keys — not Egyptian/Gulf colloquial, not literal
  word-for-word Farsi-to-Arabic.
- English: plain formal web-copy register ("Please...", "This link is no longer valid"),
  matching the existing keys — not a stiff, transliterated rendering of the Farsi syntax.
- Short but descriptive: state what happened/what's needed in one or two sentences. Don't
  pad with filler ("We would like to kindly inform you that...") in any of the three
  languages just because one of them uses a more formal construction — formality and
  verbosity are independent.
- RTL languages (fa, ar) still use Latin-script placeholders and punctuation for
  interpolated values (`{0}`, `{min}`) exactly as the existing keys do — don't invent a
  localized placeholder syntax.

## Key categories already established (match their tone)

- `error.*` — states what went wrong and, where applicable, what to do next
  (e.g. `error.invalidPasswordResetToken`: invalid/expired + "submit a new request").
- `validation.*` — states the requirement as a rule, not a reprimand; `*.prefix` keys
  introduce a list of `*.rule.*` keys that must each read as a completable clause
  (English: capitalized fragment; Farsi/Arabic: verb-first clause), not a sentence
  fragment that only makes sense in one language's word order.
- `success.*` — confirms completion; past tense, no exclamation marks.
- `email.*.subject`/`email.*.body` — subject is a short noun phrase, not a sentence; body
  keeps the existing greeting/sign-off shape (`Dear user,` / `با احترام،`) and the
  `\n\n`-separated paragraph structure already used by every email key. Never widen who
  can trigger a body key's content — that's `security-hardening`'s territory if the key is
  tied to a token flow.

## What NOT to do

- Don't translate English → Arabic or English → Farsi as an intermediate step; always
  derive from the Farsi meaning directly for each target language.
- Don't leave a key missing from one of the three files — a key present in
  @core/src/main/resources/messages_fa.properties but absent from
  @core/src/main/resources/messages.properties or @core/src/main/resources/messages_ar.properties
  breaks locale fallback at runtime for those locales.
- Don't change a placeholder name/format in only one locale (e.g. `{min}` in Farsi but
  `{0}` in English for the same key) — Spring's `MessageFormat` resolves placeholders
  positionally/by name per key, so a mismatch breaks interpolation in that locale.
- Don't reuse a rule/prefix key's phrasing across unrelated validation groups just because
  the English happens to read the same — check the Farsi meaning first, since ar/en may
  need to diverge in phrasing where fa doesn't.

## Verifying

After adding/editing a key, confirm the three files still have matching key sets:

```bash
for f in messages messages_ar messages_fa; do
  grep -oE '^[a-zA-Z][a-zA-Z0-9.]*=' core/src/main/resources/$f.properties | sed 's/=$//' | sort
done > /tmp/keys_check.txt
# or diff pairwise:
diff <(grep -oE '^[a-zA-Z][a-zA-Z0-9.]*=' core/src/main/resources/messages.properties | sort) \
     <(grep -oE '^[a-zA-Z][a-zA-Z0-9.]*=' core/src/main/resources/messages_fa.properties | sort)
diff <(grep -oE '^[a-zA-Z][a-zA-Z0-9.]*=' core/src/main/resources/messages_ar.properties | sort) \
     <(grep -oE '^[a-zA-Z][a-zA-Z0-9.]*=' core/src/main/resources/messages_fa.properties | sort)
```

Both diffs should be empty.

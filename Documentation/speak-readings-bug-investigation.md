# Speak Readings: Bug Investigation & Fixes

Branch: `fix-schedule-dst` (forked from `rob-speak-schedule`)  
Date: 2026-05-10

---

## Symptoms

Users reported that the "Speak Readings" feature (BgToSpeech) would correctly announce
two or three glucose readings after the phone was unlocked or the app was in the
foreground, then go silent for an extended period — even though readings continued to
arrive and the configured interval had elapsed.  The same pattern was observed on two
independent devices:

- A Samsung phone running as a Nightscout follower (no direct CGM).
- A different phone connected directly to a Libre sensor.

The fact that both devices showed the same pattern ruled out the Nightscout network
as the sole cause and confirmed a code-level bug.

---

## Investigation

### 1. Logs showed nothing for BgToSpeech

Exported xDrip logs contained no `BgToSpeech` entries at all during the silent
periods.  Initial hypothesis: `speak()` was not being called.

**Finding:** `UserError.Log.d()` only writes to the xDrip in-app log store if the tag
is present in the `extra_tags_for_logging` preference.  Without `BgToSpeech:d` in that
list, all debug-level messages go to system logcat only — invisible in exported logs.
The `uel()` / `ueh()` variants always store, which is why threshold-exceeded events
appeared but interval-based ones did not.

### 2. DST bug in schedule time storage (TimePreference)

The speak schedule (9 am–11 pm) was implemented on the `rob-speak-schedule` branch
in February 2026.  `TimePreference.onDialogClosed()` persisted the value as
`calendar.getTimeInMillis()` — an absolute epoch timestamp.

When the phone transitions between CET (UTC+1) and CEST (UTC+2) at the DST boundary,
reading that epoch back via `Calendar.setTimeInMillis(epochMs).get(HOUR_OF_DAY)` returns
a time shifted by 1 hour.  A schedule end set to 11 pm CET reads as midnight CEST,
cutting off an hour of expected speech.  A schedule start stored as 9 am CET reads as
10 am CEST — silencing the first hour of the day.

### 3. Audio focus not requested (root cause of persistent silence)

Android's `AudioManager` requires an app to call `requestAudioFocus()` to guarantee
audio output when the screen is off or the app is in the background.

Samsung One UI in particular silently discards `STREAM_MUSIC` audio for background apps
that have not claimed focus.  `TextToSpeech.speak()` returns `SUCCESS` (the utterance is
queued internally) but the audio is never sent to the hardware.

The critical consequence: `BgToSpeech.speak()` calls `updateLastSpokenSince()` **before**
`realSpeakNow()`.  The interval timer therefore advances normally even when no audio is
produced.  The next reading arrives, the timer says "recently spoken", threshold is not
exceeded, so the reading is silently skipped again.  This self-reinforcing loop keeps the
feature silent until something grants implicit audio focus — typically the user unlocking
the screen or opening the app.

---

## Changes Made

### `TimePreference.java` — DST-safe storage

**Old behavior:** `persistLong(calendar.getTimeInMillis())` — absolute epoch ms.

**New behavior:** `persistLong(hour * 60 + minute)` — minutes since midnight (0–1439).
This is a DST-safe integer; the same value is recovered regardless of the UTC offset at
read time.

**Migration:** `migrateToMinutes(long stored)` transparently converts any legacy epoch-ms
value (> 1439) to the equivalent minutes-since-midnight using the current timezone, so
existing user preferences are preserved on first read.

```java
static int migrateToMinutes(final long stored) {
    if (stored <= 0) return 0;
    if (stored <= 1439) return (int) stored;
    final Calendar c = Calendar.getInstance();
    c.setTimeInMillis(stored);
    return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
}
```

### `BgToSpeech.java` — consume minutes-since-midnight; auto-register debug tag

Replaced the previous `getScheduleTimeSafe()` helper (which returned raw epoch-ms and
did its own Calendar arithmetic inline) with `getScheduleMinutes(key, defaultIfUnset)`,
which reads the stored long, migrates legacy values via `TimePreference.migrateToMinutes()`,
and returns an integer in 0–1439.

`isWithinSchedule()` and `getMillisUntilScheduleStart()` now compare plain integer
minute values instead of epoch timestamps.

A static initialiser registers the `BgToSpeech` tag for debug-level UserError logging so
that all `Log.d()` calls from this class are visible in exported logs without requiring
the user to manually configure `extra_tags_for_logging`:

```java
static {
    UserError.ExtraLogTags.ensureDebugTag(TAG);
}
```

### `UserError.java` — `ExtraLogTags.ensureDebugTag()`

New public method on the `ExtraLogTags` inner class:

```java
public static void ensureDebugTag(final String tag) {
    if (tag == null) return;
    extraTags.putIfAbsent(tag.toLowerCase(), android.util.Log.DEBUG);
}
```

`putIfAbsent` means any tag level already set by the user's `extra_tags_for_logging`
preference is left untouched; the method only adds when the tag is absent.

### `SpeechUtil.java` — audio focus management

This is the fix for the primary silent-speech bug.

**New fields / methods:**

```java
private static volatile AudioManager.OnAudioFocusChangeListener audioFocusListener = null;

private static void requestAudioFocus() { ... }
static void releaseAudioFocus() { ... }
```

**In `initialize()`:** after language setup succeeds, a `UtteranceProgressListener` is
registered on the TTS instance.  Its `onDone()` and `onError()` callbacks call
`releaseAudioFocus()` so focus is returned to the system as soon as speech finishes.

**In `say()`:** `requestAudioFocus()` is called immediately before `tts.speak()`.
`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` is used so background music is ducked rather than
fully interrupted.

**In `shutdown()`:** `releaseAudioFocus()` is called before destroying the TTS instance.

**API level compatibility:** the `tts.speak()` call now uses the API 21+ four-argument
form (with utterance ID string) on Lollipop and above, and falls back to the
`HashMap`-based deprecated three-argument form on older devices.  The utterance ID
enables the `UtteranceProgressListener` to fire correctly.

---

## Why the "unlock fixes it" pattern

When the user unlocks the phone or brings xDrip to the foreground, the system
automatically grants audio focus to the foreground app.  The next `tts.speak()` call
succeeds audibly, `updateLastSpokenSince()` was already called beforehand, so the
interval clock is running from the moment the reading arrived — not from when audio was
actually heard.  This gives the illusion of "a couple of readings after unlock, then
silence" because:

1. Reading N: interval elapsed → `updateLastSpokenSince()` → `realSpeakNow()` → audible
   (app in foreground / screen on).
2. Reading N+1: interval elapsed → `updateLastSpokenSince()` → `realSpeakNow()` → audible.
3. Screen off, app backgrounded.
4. Reading N+2: interval elapsed → `updateLastSpokenSince()` → `realSpeakNow()` → queued
   but silent (no audio focus).
5. Reading N+3: interval NOT elapsed (timer advanced in step 4) → skipped.
6. … continues silently until unlock.

The audio focus fix breaks this cycle at step 4.

---

## Files Changed

| File | Change |
|------|--------|
| `app/src/main/java/com/eveningoutpost/dexdrip/utils/TimePreference.java` | Store minutes-since-midnight; add `migrateToMinutes()` |
| `app/src/main/java/com/eveningoutpost/dexdrip/utils/BgToSpeech.java` | Use `getScheduleMinutes()`; register debug tag in static block |
| `app/src/main/java/com/eveningoutpost/dexdrip/models/UserError.java` | Add `ExtraLogTags.ensureDebugTag()` |
| `app/src/main/java/com/eveningoutpost/dexdrip/utilitymodels/SpeechUtil.java` | Add audio focus request/release; `UtteranceProgressListener`; modern `speak()` API |

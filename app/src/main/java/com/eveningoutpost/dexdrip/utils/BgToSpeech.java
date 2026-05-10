package com.eveningoutpost.dexdrip.utils;

import android.os.PowerManager;

import com.eveningoutpost.dexdrip.BestGlucose;
import com.eveningoutpost.dexdrip.models.BgReading;
import com.eveningoutpost.dexdrip.models.JoH;
import com.eveningoutpost.dexdrip.models.UserError;
import com.eveningoutpost.dexdrip.models.UserError.Log;
import com.eveningoutpost.dexdrip.R;
import com.eveningoutpost.dexdrip.utilitymodels.Constants;
import com.eveningoutpost.dexdrip.utilitymodels.PersistentStore;
import com.eveningoutpost.dexdrip.utilitymodels.Pref;
import com.eveningoutpost.dexdrip.utilitymodels.SpeechUtil;
import com.eveningoutpost.dexdrip.utilitymodels.VehicleMode;
import com.eveningoutpost.dexdrip.utils.DexCollectionType;
import com.eveningoutpost.dexdrip.xdrip;

import java.text.DecimalFormat;

import static com.eveningoutpost.dexdrip.utilitymodels.SpeechUtil.TWICE_DELIMITER;

/**
 * Created by adrian on 07/09/15.
 * <p>
 * Updated 27/12/17 by jamorham to use SpeechUtil
 * <p>
 * Designed to speak glucose readings when enabled, call the "speak" method with the value, timestamp and optional trend name
 * <p>
 */
public class BgToSpeech implements NamedSliderProcessor {

    public static final String BG_TO_SPEECH_PREF = "bg_to_speech";
    private static final double MAX_THRESHOLD_MINUTES = (8 * 60) + 1;
    private static final double MAX_THRESHOLD_MGDL = 100;

    private static final String TAG = "BgToSpeech";

    static {
        UserError.ExtraLogTags.ensureDebugTag(TAG);
    }

    private static String displayBg(final double mgdl) {
        if (Pref.getString("units", "mgdl").equals("mgdl")) {
            return String.valueOf((int) mgdl);
        }
        return new DecimalFormat("#.#").format(mgdl * Constants.MGDL_TO_MMOLL);
    }

    private static int getMinutesSliderValue(int position) {
        return (int) LogSlider.calc(0, 300, 4, MAX_THRESHOLD_MINUTES, position);
    }

    private static int getThresholdSliderValue(int position) {
        if (position == 0) return 0; // 0 = threshold disabled (OFF)
        return (int) LogSlider.calc(0, 300, 4, MAX_THRESHOLD_MGDL, position);
    }

    // speak a bg reading if its timestamp is current, include the delta name if preferences dictate
    public static void speak(final double value, long timestamp, String delta_name) {
        UserError.Log.d(TAG, "speak() called: value=" + displayBg(value) + " age=" + JoH.msSince(timestamp) + "ms");

        // don't read out old values - use a longer window for follower/passive sources
        // where readings arrive with inherent network delay
        final long maxAge = DexCollectionType.getDexCollectionType().isPassive()
                ? 20 * Constants.MINUTE_IN_MS
                : 4 * Constants.MINUTE_IN_MS;
        if (JoH.msSince(timestamp) > maxAge) {
            UserError.Log.d(TAG, "Not speaking: reading too old (" + JoH.msSince(timestamp) + "ms > " + maxAge + "ms)");
            return;
        }

        // TODO As we check for this in new data observer should we only check for ongoing call here?
        // check if speech is enabled and extra check for ongoing call
        if (!(Pref.getBooleanDefaultFalse(BG_TO_SPEECH_PREF) || VehicleMode.shouldSpeak()) || JoH.isOngoingCall()) {
            UserError.Log.d(TAG, "Not speaking: speech disabled or ongoing call");
            return;
        }

        // If a schedule is enabled, ensure current time is within range
        if (!isWithinSchedule()) {
            final long msUntilStart = getMillisUntilScheduleStart();
            if (msUntilStart > 0 && msUntilStart <= 6 * Constants.MINUTE_IN_MS) {
                scheduleSpeakAtStart(msUntilStart, value, timestamp, delta_name);
            }
            UserError.Log.d(TAG, "Not speaking: outside schedule"
                    + (msUntilStart > 0 ? " (starts in " + JoH.niceTimeScalar(msUntilStart) + ")" : ""));
            return;
        }

        // check constraints
        final long change_time = getMinutesSliderValue(Pref.getInt("speak_readings_change_time", 0)) * Constants.MINUTE_IN_MS;

        boolean conditions_met = false;

        if (lastSpokenSince() < change_time) {
            final long remaining = change_time - lastSpokenSince();
            UserError.Log.d(TAG, "Not speaking due to change time threshold: " + JoH.niceTimeScalar(change_time) + " vs " + JoH.niceTimeScalar(lastSpokenSince()));
            scheduleSpeakAtInterval(remaining, value, timestamp, delta_name);

        } else {
            UserError.Log.d(TAG, "Speaking due to change time threshold: " + JoH.niceTimeScalar(change_time) + " vs " + JoH.niceTimeScalar(lastSpokenSince()));
            conditions_met = true;
        }

        if (!conditions_met) {
            if (!thresholdExceeded(value)) {
                UserError.Log.d(TAG, "Not speaking due to change delta threshold: " + displayBg(value));
                return;
            }
        }

        updateLastSpokenSince();
        PersistentStore.setDouble(LAST_SPOKEN_VALUE, value);
        realSpeakNow(value, timestamp, delta_name);

    }

    /**
     * Check if the current time falls within the configured speak readings schedule.
     * Returns true if no schedule is set (i.e. speak all day) or if the current time is within the scheduled range.
     * Also used by speak alerts to respect the same schedule.
     */
    public static boolean isWithinSchedule() {
        try {
            if (Pref.getBooleanDefaultFalse("speak_readings_schedule_enabled")) {
                final int nowMinutes = minutesSinceMidnight();
                final int startMinutes = getScheduleMinutes("speak_readings_schedule_start", 0);
                final int endMinutes = getScheduleMinutes("speak_readings_schedule_end", 24 * 60);

                UserError.Log.d(TAG, "Schedule check: now=" + nowMinutes
                        + " start=" + startMinutes + " end=" + endMinutes);

                if (startMinutes == endMinutes) {
                    UserError.Log.d(TAG, "Schedule: start==end, treating as all day");
                    return true;
                }

                final boolean inRange;
                if (startMinutes < endMinutes) {
                    inRange = nowMinutes >= startMinutes && nowMinutes < endMinutes;
                } else {
                    // range spans midnight
                    inRange = nowMinutes >= startMinutes || nowMinutes < endMinutes;
                }

                UserError.Log.d(TAG, "Schedule result: inRange=" + inRange);
                if (!inRange) return false;
            }
        } catch (Exception e) {
            UserError.Log.e(TAG, "Error reading schedule preferences, falling back to speaking: " + e);
        }
        return true;
    }

    /**
     * Reads a schedule time preference and returns minutes since midnight (0–1439),
     * or defaultIfUnset if the preference has never been saved.
     * Handles migration from the legacy epoch-ms storage format used in earlier builds.
     */
    private static int getScheduleMinutes(final String key, final int defaultIfUnset) {
        final long stored;
        try {
            stored = Pref.getLong(key, -1);
        } catch (ClassCastException e) {
            UserError.Log.e(TAG, "ClassCastException reading " + key + ", trying as string: " + e);
            try {
                final long parsed = Long.parseLong(Pref.getString(key, "-1"));
                return parsed < 0 ? defaultIfUnset : TimePreference.migrateToMinutes(parsed);
            } catch (Exception e2) {
                UserError.Log.e(TAG, "Failed to read " + key + " as string: " + e2);
                return defaultIfUnset;
            }
        }
        return stored < 0 ? defaultIfUnset : TimePreference.migrateToMinutes(stored);
    }

    private static int minutesSinceMidnight() {
        final java.util.Calendar now = java.util.Calendar.getInstance();
        return now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE);
    }

    private static volatile long pendingSpeakAt = 0;
    private static volatile long pendingIntervalSpeakAt = 0;

    /**
     * Defer a reading for speech until the schedule start time.
     * Holds a wake lock for the duration so the device stays awake.
     */
    private static void scheduleSpeakAtStart(final long delayMs, final double value, final long timestamp, final String delta_name) {
        final long targetTime = JoH.tsl() + delayMs;
        if (Math.abs(pendingSpeakAt - targetTime) < Constants.MINUTE_IN_MS) {
            UserError.Log.d(TAG, "Speak at schedule start already pending, skipping duplicate");
            return;
        }
        pendingSpeakAt = targetTime;
        UserError.Log.d(TAG, "Deferring reading for speech at schedule start in " + JoH.niceTimeScalar(delayMs));

        new Thread(() -> {
            final PowerManager.WakeLock wl = JoH.getWakeLock("BgToSpeech-schedule-start",
                    (int) (delayMs + 30 * Constants.SECOND_IN_MS));
            try {
                Thread.sleep(delayMs);
                if (isWithinSchedule()) {
                    UserError.Log.d(TAG, "Schedule started, speaking deferred reading");
                    updateLastSpokenSince();
                    realSpeakNow(value, timestamp, delta_name);
                } else {
                    UserError.Log.d(TAG, "Schedule still not active after delay, skipping");
                }
            } catch (InterruptedException e) {
                UserError.Log.d(TAG, "Deferred speak interrupted");
            } finally {
                JoH.releaseWakeLock(wl);
                pendingSpeakAt = 0;
            }
        }).start();
    }

    /**
     * Defer a reading for speech until the interval timer expires.
     * If a natural speak fires before the timer, the deferred speak self-cancels
     * because lastSpokenSince() will have reset to near zero (less than the age
     * recorded at schedule time).
     */
    private static void scheduleSpeakAtInterval(final long delayMs, final double value, final long timestamp, final String delta_name) {
        final long targetTime = JoH.tsl() + delayMs;
        if (Math.abs(pendingIntervalSpeakAt - targetTime) < 30 * Constants.SECOND_IN_MS) {
            UserError.Log.d(TAG, "Interval deferred speak already pending, skipping duplicate");
            return;
        }
        pendingIntervalSpeakAt = targetTime;
        final long spokenAgeAtSchedule = lastSpokenSince();
        UserError.Log.d(TAG, "Deferring reading for interval speak in " + JoH.niceTimeScalar(delayMs));

        new Thread(() -> {
            final PowerManager.WakeLock wl = JoH.getWakeLock("BgToSpeech-interval",
                    (int) (delayMs + 30 * Constants.SECOND_IN_MS));
            try {
                Thread.sleep(delayMs);
                if (lastSpokenSince() < spokenAgeAtSchedule) {
                    UserError.Log.d(TAG, "Interval deferred speak cancelled: natural speak fired");
                    return;
                }
                if (!isWithinSchedule()) {
                    UserError.Log.d(TAG, "Interval deferred speak cancelled: outside schedule");
                    return;
                }
                UserError.Log.d(TAG, "Interval elapsed, speaking deferred reading");
                updateLastSpokenSince();
                PersistentStore.setDouble(LAST_SPOKEN_VALUE, value);
                realSpeakNow(value, timestamp, delta_name);
            } catch (InterruptedException e) {
                UserError.Log.d(TAG, "Interval deferred speak interrupted");
            } finally {
                JoH.releaseWakeLock(wl);
                pendingIntervalSpeakAt = 0;
            }
        }).start();
    }

    /**
     * Calculate milliseconds until the next occurrence of the schedule start time.
     * Returns -1 if no schedule is configured or no start time is set.
     */
    private static long getMillisUntilScheduleStart() {
        try {
            if (!Pref.getBooleanDefaultFalse("speak_readings_schedule_enabled")) {
                return -1;
            }
            final int startMinutes = getScheduleMinutes("speak_readings_schedule_start", -1);
            if (startMinutes < 0) return -1;

            int diff = startMinutes - minutesSinceMidnight();
            if (diff <= 0) diff += 24 * 60;

            return diff * 60 * 1000L;
        } catch (Exception e) {
            return -1;
        }
    }

    private static final String LAST_SPOKEN_TIME = "last-spoken-reading-time";

    private static long lastSpokenSince() {
        return JoH.msSince(PersistentStore.getLong(LAST_SPOKEN_TIME));
    }

    private static void updateLastSpokenSince() {
        PersistentStore.setLong(LAST_SPOKEN_TIME, JoH.tsl());
    }

    private static final String LAST_SPOKEN_VALUE = "last-spoken-value";

    private static boolean thresholdExceeded(double value) {
        final long change_delta = getThresholdSliderValue(Pref.getInt("speak_readings_change_threshold", 0));
        if (change_delta == 0) {
            UserError.Log.d(TAG, "Threshold disabled (OFF)");
            return false;
        }
        final double abs_delta = Math.abs(value - PersistentStore.getDouble(LAST_SPOKEN_VALUE));
        if (abs_delta > change_delta) {
            UserError.Log.uel(TAG, "Threshold EXCEEDED: delta=" + displayBg(abs_delta) + " vs " + displayBg(change_delta) + " @ " + displayBg(value));
            return true;
        }
        UserError.Log.d(TAG, "Threshold not exceeded: delta=" + displayBg(abs_delta) + " vs " + displayBg(change_delta) + " @ " + displayBg(value));
        return false;
    }

    // always speak the value passed
    public static void realSpeakNow(final double value, long timestamp, String delta_name) {
        final String text_to_speak = calculateText(value, Pref.getBooleanDefaultFalse("bg_to_speech_trend") ? delta_name : null);
        UserError.Log.d(TAG, "Attempting to speak BG reading of: " + text_to_speak);

        SpeechUtil.say(text_to_speak);
    }

    private static String mungeDeltaName(String delta_name) {


        switch (delta_name) {
            case "DoubleDown":
                delta_name = xdrip.getAppContext().getString(R.string.DoubleDown);
                break;
            case "SingleDown":
                delta_name = xdrip.getAppContext().getString(R.string.SingleDown);
                break;
            case "FortyFiveDown":
                delta_name = xdrip.getAppContext().getString(R.string.FortyFiveDown);
                break;
            case "Flat":
                delta_name = xdrip.getAppContext().getString(R.string.Flat);
                break;
            case "FortyFiveUp":
                delta_name = xdrip.getAppContext().getString(R.string.FortyFiveUp);
                break;
            case "SingleUp":
                delta_name = xdrip.getAppContext().getString(R.string.SingleUp);
                break;
            case "DoubleUp":
                delta_name = xdrip.getAppContext().getString(R.string.DoubleUp);
                break;
            case "NOT COMPUTABLE":
                delta_name = "";
                break;

            // do we need a default or just pass thru?
        }
        return delta_name;
    }

    private static String calculateText(double value, String delta_name) {

        final boolean doMgdl = (Pref.getString("units", "mgdl").equals("mgdl"));
        final boolean bg_to_speech_repeat_twice = (Pref.getBooleanDefaultFalse("bg_to_speech_repeat_twice"));
        String text = "";

        // TODO does some of this need unifying from best glucose etc?
        final DecimalFormat df = new DecimalFormat("#");
        if (value >= 400) {
            text = xdrip.getAppContext().getString(R.string.high);
        } else if (value >= 40) {
            if (doMgdl) {
                df.setMaximumFractionDigits(0);
                text = df.format(value);
            } else {
                df.setMaximumFractionDigits(1);
                df.setMinimumFractionDigits(1);
                text = df.format(value * Constants.MGDL_TO_MMOLL);
                try {
                    // we check the locale but it may not actually be available if the instance isn't created yet
                    if (SpeechUtil.getLocale().getLanguage().startsWith("en")) {
                        // in case the text has a comma in current locale but TTS defaults to English
                        text = text.replace(",", ".");
                    }
                } catch (NullPointerException e) {
                    Log.e(TAG, "Null pointer for TTS in calculateText");
                }
            }
            if (delta_name != null) text += " " + mungeDeltaName(delta_name);
            if (bg_to_speech_repeat_twice) text = text + TWICE_DELIMITER + text;
        } else if (value > 12) {
            text = xdrip.getAppContext().getString(R.string.low);
        } else {
            text = xdrip.getAppContext().getString(R.string.error);
        }
        Log.d(TAG, "calculated text: " + text);
        return text;
    }

    // shutdown instance - used for changing language/settings
    public static void tearDownTTS() {
        SpeechUtil.shutdown();
    }


    // TODO grace period and 20 minute safety when testSpeech is called needs either a rework or rethink as it is ignored by SpeakNow now and the grace parameter is no longer needed
    // hopefully say a test reading
    public static void testSpeech() {
        speakNow(1200000);
    }

    // speak the most recent reading, with 0 grace only if in time
    public static void speakNow(long grace) {
        final BgReading bgReading = BgReading.last();
        if (bgReading != null) {
            final BestGlucose.DisplayGlucose dg = BestGlucose.getDisplayGlucose();
            if (dg != null) {
                BgToSpeech.realSpeakNow(dg.mgdl, dg.timestamp + grace, dg.delta_name);
            } else {
                BgToSpeech.realSpeakNow(bgReading.calculated_value, bgReading.timestamp + grace, bgReading.slopeName());
            }
        }
    }

    @Override
    public int interpolate(String name, int position) {
        switch (name) {
            case "time":
                return getMinutesSliderValue(position);
            case "threshold":
                return getThresholdSliderValue(position);
        }
        throw new RuntimeException("name not matched in interpolate");
    }
}

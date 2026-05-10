package com.eveningoutpost.dexdrip.utils;

import android.content.Context;
import android.content.res.TypedArray;
import android.preference.DialogPreference;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TimePicker;

import com.eveningoutpost.dexdrip.models.JoH;

import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;

// Times are stored as minutes-since-midnight (0–1439), a DST-safe integer.
// Values > 1439 in storage are legacy epoch-ms from an older build and are
// migrated transparently by migrateToMinutes().

public class TimePreference extends DialogPreference {
    private Calendar calendar;
    private TimePicker picker = null;

    public TimePreference(Context ctxt) {
        this(ctxt, null);
    }

    public TimePreference(Context ctxt, AttributeSet attrs) {
        this(ctxt, attrs, android.R.attr.dialogPreferenceStyle);
    }

    public TimePreference(Context ctxt, AttributeSet attrs, int defStyle) {
        super(ctxt, attrs, defStyle);

        setPositiveButtonText("Set");
        setNegativeButtonText("Close");
        calendar = new GregorianCalendar();
    }

    @Override
    protected View onCreateDialogView() {
        picker = new TimePicker(getContext());
        picker.setIs24HourView(JoH.is24HourFormat());
        return (picker);
    }

    @Override
    protected void onBindDialogView(View v) {
        super.onBindDialogView(v);
        picker.setCurrentHour(calendar.get(Calendar.HOUR_OF_DAY));
        picker.setCurrentMinute(calendar.get(Calendar.MINUTE));
    }

    @Override
    protected void onDialogClosed(boolean positiveResult) {
        super.onDialogClosed(positiveResult);

        if (positiveResult) {
            final int hour = picker.getCurrentHour();
            final int minute = picker.getCurrentMinute();
            calendar.set(Calendar.HOUR_OF_DAY, hour);
            calendar.set(Calendar.MINUTE, minute);

            setSummary(getSummary());
            final long minutesSinceMidnight = hour * 60 + minute;
            if (callChangeListener(minutesSinceMidnight)) {
                persistLong(minutesSinceMidnight);
                notifyChanged();
            }
        }
    }

    @Override
    protected Object onGetDefaultValue(TypedArray a, int index) {
        return (a.getString(index));
    }

    @Override
    protected void onSetInitialValue(boolean restoreValue, Object defaultValue) {
        final int minutes;
        if (restoreValue) {
            final long stored = getPersistedLong(-1);
            minutes = (stored < 0) ? currentMinutes() : migrateToMinutes(stored);
        } else {
            if (defaultValue == null) {
                minutes = currentMinutes();
            } else {
                minutes = migrateToMinutes(Long.parseLong((String) defaultValue));
            }
        }
        calendar.set(Calendar.HOUR_OF_DAY, minutes / 60);
        calendar.set(Calendar.MINUTE, minutes % 60);
        setSummary(getSummary());
    }

    @Override
    public CharSequence getSummary() {
        if (calendar == null) {
            return null;
        }
        return JoH.getTimeFormat().format(new Date(calendar.getTimeInMillis()));
    }

    private static int currentMinutes() {
        final Calendar c = Calendar.getInstance();
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }

    /**
     * Converts any stored time value to minutes-since-midnight (0–1439).
     * Values already in that range pass through unchanged.
     * Larger values are treated as legacy epoch-ms and the local hour:minute is extracted.
     */
    static int migrateToMinutes(final long stored) {
        if (stored <= 0) return 0;
        if (stored <= 1439) return (int) stored;
        final Calendar c = Calendar.getInstance();
        c.setTimeInMillis(stored);
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }
}
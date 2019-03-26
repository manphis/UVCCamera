package com.example.uvctestapp.util;

import java.text.SimpleDateFormat;
import java.util.GregorianCalendar;
import java.util.Locale;

public class TimeFormat {
    private static final SimpleDateFormat mDateTimeFormat = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);

    public static final String getDateTimeString() {
        final GregorianCalendar now = new GregorianCalendar();
        return mDateTimeFormat.format(now.getTime());
    }
}

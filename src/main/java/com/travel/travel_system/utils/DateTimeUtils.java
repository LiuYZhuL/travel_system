package com.travel.travel_system.utils;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

public final class DateTimeUtils {

    private static final String DATETIME_PATTERN = "yyyy-MM-dd HH:mm:ss";
    private static final String DATE_PATTERN = "yyyy-MM-dd";
    private static final String TIME_PATTERN = "HH:mm";
    private static final TimeZone DEFAULT_TIMEZONE = TimeZone.getTimeZone("Asia/Shanghai");

    private DateTimeUtils() {}

    public static String formatDateTime(Date date) {
        if (date == null) {
            return null;
        }
        SimpleDateFormat sdf = new SimpleDateFormat(DATETIME_PATTERN);
        sdf.setTimeZone(DEFAULT_TIMEZONE);
        return sdf.format(date);
    }

    public static String formatDate(Date date) {
        if (date == null) {
            return null;
        }
        SimpleDateFormat sdf = new SimpleDateFormat(DATE_PATTERN);
        sdf.setTimeZone(DEFAULT_TIMEZONE);
        return sdf.format(date);
    }

    public static String formatTime(Date date) {
        if (date == null) {
            return null;
        }
        SimpleDateFormat sdf = new SimpleDateFormat(TIME_PATTERN);
        sdf.setTimeZone(DEFAULT_TIMEZONE);
        return sdf.format(date);
    }

    public static String formatDuration(Long seconds) {
        if (seconds == null || seconds <= 0) {
            return "0 分钟";
        }
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        if (hours > 0) {
            return hours + " 小时 " + minutes + " 分钟";
        }
        return Math.max(1, minutes) + " 分钟";
    }

    public static String formatDurationShort(Long seconds) {
        if (seconds == null || seconds <= 0) {
            return "0分";
        }
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        if (hours > 0) {
            return hours + "小时" + minutes + "分";
        }
        return Math.max(1, minutes) + "分钟";
    }

    public static String formatDurationCompact(Long seconds) {
        if (seconds == null || seconds <= 0) {
            return "0m";
        }
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        if (hours > 0) {
            return hours + "h" + minutes + "m";
        }
        return Math.max(1, minutes) + "m";
    }

    public static String formatGeneratedAt(Object value) {
        if (value instanceof Date) {
            return formatDateTime((Date) value);
        }
        return value != null ? String.valueOf(value) : null;
    }

    public static String formatDistance(Double meters) {
        if (meters == null || meters <= 0) {
            return "0 km";
        }
        if (meters < 1000) {
            return String.format("%.0f m", meters);
        }
        return String.format("%.2f km", meters / 1000.0);
    }

    public static String formatDistanceCompact(Double meters) {
        if (meters == null || meters <= 0) {
            return "0km";
        }
        if (meters < 1000) {
            return String.format("%.0fm", meters);
        }
        return String.format("%.1fkm", meters / 1000.0);
    }

    public static Date parseDateTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            SimpleDateFormat sdf = new SimpleDateFormat(DATETIME_PATTERN);
            sdf.setTimeZone(DEFAULT_TIMEZONE);
            return sdf.parse(value);
        } catch (Exception e) {
            throw new RuntimeException("日期时间解析失败: " + value, e);
        }
    }
}

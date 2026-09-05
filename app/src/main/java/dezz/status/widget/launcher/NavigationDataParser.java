/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure-Java parser for route summaries exposed by Yandex navigation applications. */
public final class NavigationDataParser {
    // Android 9's java.util.regex implementation rejects UNICODE_CHARACTER_CLASS (flag 256)
    // with IllegalArgumentException. UNICODE_CASE keeps case-insensitive Cyrillic matching; clean()
    // normalizes Unicode whitespace before the expressions run, so the unsupported flag is not
    // needed for route text containing non-breaking or narrow spaces.
    private static final int REGEX_FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private static final Pattern CLOCK = Pattern.compile(
            "(?<!\\d)([01]?\\d|2[0-3]):([0-5]\\d)(?:\\s*([AP]M))?(?!\\d)",
            REGEX_FLAGS);
    private static final Pattern CLOCK_ONLY = Pattern.compile(
            "^[\\s:;,·•\u2013\u2014-]*([01]?\\d|2[0-3]):([0-5]\\d)(?:\\s*([AP]M))?"
                    + "[\\s:;,·•\u2013\u2014-]*$",
            REGEX_FLAGS);
    private static final Pattern ARRIVAL = Pattern.compile(
            "(?:время\\s+прибытия|прибыт(?:ие|ия|ь)|arrival(?:\\s+time)?|\\beta\\b)"
                    + "[^\\d]{0,48}([01]?\\d|2[0-3]):([0-5]\\d)(?:\\s*([AP]M))?",
            REGEX_FLAGS);
    private static final Pattern ROUTE_MARKER = Pattern.compile(
            "(?:время\\s+прибытия|прибыт|остал(?:ось|ось)|до\\s+финиша|"
                    + "arrival|remaining|\\beta\\b)",
            REGEX_FLAGS);

    private static final String DAY_UNIT = "(?:д(?:н(?:я|ей)?)?|days?)";
    private static final String HOUR_UNIT = "(?:ч(?:ас(?:а|ов)?)?|h(?:rs?|ours?)?)";
    private static final String MINUTE_UNIT = "(?:мин(?:ут(?:а|ы)?)?|minutes?|mins?)";
    private static final String SEPARATOR = "[\\s,.;·•]*";
    private static final Pattern DURATION = Pattern.compile(
            "(?:(\\d+)\\s*" + DAY_UNIT
                    + "(?:" + SEPARATOR + "(\\d+)\\s*" + HOUR_UNIT + ")?"
                    + "(?:" + SEPARATOR + "(\\d+)\\s*" + MINUTE_UNIT + ")?"
                    + "|(\\d+)\\s*" + HOUR_UNIT
                    + "(?:" + SEPARATOR + "(\\d+)\\s*" + MINUTE_UNIT + ")?"
                    + "|(\\d+)\\s*" + MINUTE_UNIT + ")",
            REGEX_FLAGS);

    private static final Pattern DISTANCE = Pattern.compile(
            "(?<![\\d.,])(\\d+(?:[.,]\\d+)?)\\s*"
                    + "(километр(?:а|ов)?|км|kilometers?|kilometres?|km|"
                    + "метр(?:а|ов)?|м|meters?|metres?|m)"
                    + "(?=$|[\\s,.;:·•)\\]])",
            REGEX_FLAGS);

    /** Parsed and normalized route values. Empty strings mean that a value was not found. */
    public static final class Parsed {
        public final String arrival;
        public final String duration;
        public final String distance;

        Parsed(String arrival, String duration, String distance) {
            this.arrival = arrival;
            this.duration = duration;
            this.distance = distance;
        }

        public boolean hasData() {
            return !arrival.isEmpty() || !duration.isEmpty() || !distance.isEmpty();
        }

        public int fieldCount() {
            int count = 0;
            if (!arrival.isEmpty()) count++;
            if (!duration.isEmpty()) count++;
            if (!distance.isEmpty()) count++;
            return count;
        }
    }

    private static final class DistanceCandidate {
        final BigDecimal metres;
        final String formatted;

        DistanceCandidate(BigDecimal metres, String formatted) {
            this.metres = metres;
            this.formatted = formatted;
        }
    }

    private NavigationDataParser() {}

    /**
     * Parses exact Yandex values first and fills missing fields from notification/accessibility
     * text. Exact values correspond to {@code ru.yandex.maps.arrival/time/distance}.
     */
    public static Parsed parse(Object preferredArrival, Object preferredDuration,
            Object preferredDistance, List<String> textValues) {
        List<String> values = sanitize(textValues);

        String duration = findDuration(asText(preferredDuration));
        String distance = findDistance(asText(preferredDistance));
        String arrival = normalizeClock(asText(preferredArrival));

        if (duration.isEmpty()) duration = findDuration(values);
        if (distance.isEmpty()) distance = findDistance(values);
        if (arrival.isEmpty()) arrival = findArrival(values,
                !duration.isEmpty() || !distance.isEmpty());

        return new Parsed(arrival, duration, distance);
    }

    public static Parsed parse(List<String> textValues) {
        return parse(null, null, null, textValues);
    }

    public static boolean hasRouteMarker(List<String> textValues) {
        for (String value : sanitize(textValues)) {
            if (ROUTE_MARKER.matcher(value).find()) return true;
        }
        return false;
    }

    private static List<String> sanitize(List<String> values) {
        if (values == null || values.isEmpty()) return Collections.emptyList();
        Set<String> result = new LinkedHashSet<>();
        for (String value : values) {
            String text = clean(value);
            if (!text.isEmpty()) result.add(text);
        }
        return new ArrayList<>(result);
    }

    private static String asText(Object value) {
        return value == null ? "" : clean(String.valueOf(value));
    }

    private static String clean(String value) {
        if (value == null) return "";
        StringBuilder result = new StringBuilder(value.length());
        boolean pendingSpace = false;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                pendingSpace = result.length() > 0;
                continue;
            }
            if (pendingSpace) result.append(' ');
            result.appendCodePoint(codePoint);
            pendingSpace = false;
        }
        return result.toString();
    }

    private static String findArrival(List<String> values, boolean hasRouteEvidence) {
        for (String value : values) {
            Matcher marked = ARRIVAL.matcher(value);
            if (marked.find()) return formatClock(marked.group(1), marked.group(2), marked.group(3));
        }

        Set<String> clocks = new LinkedHashSet<>();
        for (String value : values) {
            Matcher only = CLOCK_ONLY.matcher(value);
            if (only.matches()) {
                clocks.add(formatClock(only.group(1), only.group(2), only.group(3)));
                continue;
            }
            Matcher matcher = CLOCK.matcher(value);
            while (matcher.find()) {
                clocks.add(formatClock(matcher.group(1), matcher.group(2), matcher.group(3)));
            }
        }
        // A standalone clock is how several Navigator builds expose ETA in RemoteViews. Do not
        // mistake an arbitrary notification clock for ETA unless distance/duration is present.
        return hasRouteEvidence && clocks.size() == 1 ? clocks.iterator().next() : "";
    }

    private static String normalizeClock(String value) {
        Matcher matcher = CLOCK.matcher(value);
        if (!matcher.find()) return "";
        return formatClock(matcher.group(1), matcher.group(2), matcher.group(3));
    }

    private static String formatClock(String hourValue, String minuteValue, String amPm) {
        int hour;
        int minute;
        try {
            hour = Integer.parseInt(hourValue);
            minute = Integer.parseInt(minuteValue);
        } catch (NumberFormatException ignored) {
            return "";
        }
        if (amPm != null && !amPm.trim().isEmpty()) {
            String marker = amPm.trim().toUpperCase(Locale.ROOT);
            if (hour > 12 || hour == 0) return "";
            if ("PM".equals(marker) && hour != 12) hour += 12;
            if ("AM".equals(marker) && hour == 12) hour = 0;
        }
        return String.format(Locale.ROOT, "%02d:%02d", hour, minute);
    }

    private static String findDuration(List<String> values) {
        long bestMinutes = -1;
        for (String value : values) {
            long candidate = durationMinutes(value);
            if (candidate > bestMinutes) bestMinutes = candidate;
        }
        return formatDuration(bestMinutes);
    }

    private static String findDuration(String value) {
        return formatDuration(durationMinutes(value));
    }

    private static long durationMinutes(String value) {
        if (value.isEmpty()) return -1;
        long best = -1;
        Matcher matcher = DURATION.matcher(value);
        while (matcher.find()) {
            long days = number(matcher.group(1));
            long hours = number(matcher.group(2));
            long minutes = number(matcher.group(3));
            if (matcher.group(4) != null) {
                hours = number(matcher.group(4));
                minutes = number(matcher.group(5));
            } else if (matcher.group(6) != null) {
                minutes = number(matcher.group(6));
            }
            long total = days * 24L * 60L + hours * 60L + minutes;
            if (total > best) best = total;
        }
        return best;
    }

    private static long number(String value) {
        if (value == null || value.isEmpty()) return 0;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String formatDuration(long totalMinutes) {
        if (totalMinutes < 0) return "";
        long days = totalMinutes / (24L * 60L);
        long hours = (totalMinutes % (24L * 60L)) / 60L;
        long minutes = totalMinutes % 60L;
        StringBuilder result = new StringBuilder();
        if (days > 0) result.append(days).append(" д");
        if (hours > 0) {
            if (result.length() > 0) result.append(' ');
            result.append(hours).append(" ч");
        }
        if (minutes > 0 || result.length() == 0) {
            if (result.length() > 0) result.append(' ');
            result.append(minutes).append(" мин");
        }
        return result.toString();
    }

    private static String findDistance(List<String> values) {
        DistanceCandidate best = null;
        for (String value : values) {
            DistanceCandidate candidate = distanceCandidate(value);
            if (candidate != null && (best == null
                    || candidate.metres.compareTo(best.metres) > 0)) {
                best = candidate;
            }
        }
        return best == null ? "" : best.formatted;
    }

    private static String findDistance(String value) {
        DistanceCandidate candidate = distanceCandidate(value);
        return candidate == null ? "" : candidate.formatted;
    }

    private static DistanceCandidate distanceCandidate(String value) {
        if (value.isEmpty()) return null;
        DistanceCandidate best = null;
        Matcher matcher = DISTANCE.matcher(value);
        while (matcher.find()) {
            BigDecimal amount;
            try {
                amount = new BigDecimal(matcher.group(1).replace(',', '.'));
            } catch (NumberFormatException ignored) {
                continue;
            }
            if (amount.signum() < 0) continue;
            String unit = matcher.group(2).toLowerCase(Locale.ROOT);
            boolean kilometres = unit.equals("км") || unit.equals("km")
                    || unit.startsWith("километр") || unit.startsWith("kilomet");
            BigDecimal metres = kilometres ? amount.multiply(BigDecimal.valueOf(1000L)) : amount;
            String formatted = decimal(amount) + (kilometres ? " км" : " м");
            DistanceCandidate candidate = new DistanceCandidate(metres, formatted);
            if (best == null || candidate.metres.compareTo(best.metres) > 0) best = candidate;
        }
        return best;
    }

    private static String decimal(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString().replace('.', ',');
    }
}

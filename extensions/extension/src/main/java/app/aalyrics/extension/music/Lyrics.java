package app.aalyrics.extension.music;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The lyrics of one song, with or without a timestamp for each line. */
final class Lyrics {

    static final class Line {
        /** Start of the line in milliseconds, or -1 if the lyrics have no timestamps. */
        final long timeMs;
        final String text;

        Line(long timeMs, String text) {
            this.timeMs = timeMs;
            this.text = text;
        }
    }

    /** Shown for a timestamp without text, which marks an instrumental part. */
    static final String INSTRUMENTAL_LINE = "♪";

    // [01:23.45], [1:23], [01:23:45] and [01:23.456].
    private static final Pattern TIMESTAMP =
            Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");

    final List<Line> lines;
    final boolean synced;

    private Lyrics(List<Line> lines, boolean synced) {
        this.lines = Collections.unmodifiableList(lines);
        this.synced = synced;
    }

    /** @return The lyrics, or null if the text has no line with a timestamp. */
    static Lyrics fromSynced(String lrc) {
        if (lrc == null) return null;

        List<Line> lines = new ArrayList<>();
        for (String raw : lrc.split("\n")) {
            Matcher matcher = TIMESTAMP.matcher(raw);
            List<Long> times = new ArrayList<>();
            int textStart = 0;
            // One line can have several timestamps if it is sung more than once.
            while (matcher.find() && raw.substring(textStart, matcher.start()).trim().isEmpty()) {
                times.add(toMillis(matcher.group(1), matcher.group(2), matcher.group(3)));
                textStart = matcher.end();
            }
            if (times.isEmpty()) continue;

            String text = raw.substring(textStart).trim();
            if (text.isEmpty()) text = INSTRUMENTAL_LINE;
            for (long time : times) {
                lines.add(new Line(time, text));
            }
        }
        if (lines.isEmpty()) return null;

        // The sort is stable, so lines with the same timestamp keep their order.
        Collections.sort(lines, (a, b) -> Long.compare(a.timeMs, b.timeMs));

        // Several empty timestamps in a row are one instrumental part.
        List<Line> merged = new ArrayList<>(lines.size());
        for (Line line : lines) {
            boolean repeatsPause = !merged.isEmpty()
                    && INSTRUMENTAL_LINE.equals(line.text)
                    && INSTRUMENTAL_LINE.equals(merged.get(merged.size() - 1).text);
            if (!repeatsPause) merged.add(line);
        }
        return new Lyrics(merged, true);
    }

    /** @return The lyrics, or null if the text has no line. */
    static Lyrics fromPlain(String text) {
        if (text == null) return null;

        List<Line> lines = new ArrayList<>();
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (!line.isEmpty()) lines.add(new Line(-1, line));
        }
        return lines.isEmpty() ? null : new Lyrics(lines, false);
    }

    /** @return The index of the line sung at the position, or -1 before the first line. */
    int indexAt(long positionMs) {
        if (!synced) return -1;

        int low = 0;
        int high = lines.size() - 1;
        int index = -1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (lines.get(middle).timeMs <= positionMs) {
                index = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return index;
    }

    private static long toMillis(String minutes, String seconds, String fraction) {
        long millis = Long.parseLong(minutes) * 60_000L + Long.parseLong(seconds) * 1_000L;
        if (fraction != null) {
            long value = Long.parseLong(fraction);
            // "5" is 500 ms, "45" is 450 ms and "456" is 456 ms.
            if (fraction.length() == 1) value *= 100;
            else if (fraction.length() == 2) value *= 10;
            millis += value;
        }
        return millis;
    }
}

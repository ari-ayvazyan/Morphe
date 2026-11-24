package app.aalyrics.extension.music;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;
import java.util.regex.Pattern;

/** Downloads lyrics from <a href="https://lrclib.net">LRCLIB</a>, a free lyrics database. */
final class LyricsProvider {

    /** The song to find lyrics for. */
    static final class Track {
        final String title;
        final String artist;
        /** Length in milliseconds, or 0 if unknown. */
        final long durationMs;

        Track(String title, String artist, long durationMs) {
            this.title = title;
            this.artist = artist;
            this.durationMs = durationMs;
        }
    }

    private static final String SEARCH_URL = "https://lrclib.net/api/search";
    private static final String USER_AGENT = "AndroidAutoLyricsPatch/1.0";
    private static final int TIMEOUT_MS = 10_000;

    // Lyrics whose song length differs by more than this are for another version of the song,
    // and their timestamps would not fit.
    private static final long MAX_SYNCED_DURATION_DIFFERENCE_MS = 5_000;

    // "(Official Video)", "[Lyrics]", "(2011 Remaster)", "(feat. Someone)" and similar.
    private static final Pattern TITLE_NOISE = Pattern.compile(
            "\\s*[(\\[][^)\\]]*\\b(official|video|audio|lyrics?|visuali[sz]er|remaster(ed)?|explicit|"
                    + "clean|hd|hq|4k|mv|feat|ft|featuring|with)\\b[^)\\]]*[)\\]]",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ARTIST_SEPARATOR = Pattern.compile(
            "\\s*(,|;|&|\\bx\\b|\\bfeat\\.?|\\bft\\.?|\\bfeaturing\\b)\\s*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NOT_LETTER_OR_DIGIT = Pattern.compile("[^\\p{L}\\p{N}]+");

    private LyricsProvider() {
    }

    /**
     * @return The lyrics, or null if LRCLIB has none for the song.
     * @throws IOException If LRCLIB could not be reached. The request can be repeated later.
     */
    static Lyrics fetch(Track track) throws IOException {
        String cleanTitle = cleanTitle(track.title);
        String firstArtist = firstArtist(track.artist);

        Lyrics lyrics = search(track, cleanTitle, track.artist);
        if (lyrics == null && !firstArtist.equals(track.artist)) {
            lyrics = search(track, cleanTitle, firstArtist);
        }
        return lyrics;
    }

    static String cleanTitle(String title) {
        String cleaned = TITLE_NOISE.matcher(title).replaceAll("").trim();
        return cleaned.isEmpty() ? title.trim() : cleaned;
    }

    static String firstArtist(String artist) {
        String withoutTopic = artist.replaceFirst("(?i)\\s+-\\s+Topic$", "").trim();
        String[] parts = ARTIST_SEPARATOR.split(withoutTopic);
        return parts.length == 0 || parts[0].trim().isEmpty() ? withoutTopic : parts[0].trim();
    }

    private static Lyrics search(Track track, String title, String artist) throws IOException {
        StringBuilder url = new StringBuilder(SEARCH_URL)
                .append("?track_name=").append(URLEncoder.encode(title, "UTF-8"));
        if (!artist.isEmpty()) {
            url.append("&artist_name=").append(URLEncoder.encode(artist, "UTF-8"));
        }

        try {
            return pickBest(new JSONArray(httpGet(url.toString())), track);
        } catch (JSONException ex) {
            throw new IOException("Unexpected response from LRCLIB", ex);
        }
    }

    /** Chooses the search result that fits the song best, preferring lyrics with timestamps. */
    static Lyrics pickBest(JSONArray results, Track track) throws JSONException {
        String wantedTitle = normalize(cleanTitle(track.title));
        String wantedArtist = normalize(firstArtist(track.artist));

        Lyrics best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < results.length(); i++) {
            JSONObject result = results.getJSONObject(i);

            long durationMs = (long) (result.optDouble("duration", 0) * 1000);
            long difference = track.durationMs > 0 && durationMs > 0
                    ? Math.abs(track.durationMs - durationMs)
                    : -1;

            Lyrics synced = difference <= MAX_SYNCED_DURATION_DIFFERENCE_MS
                    ? Lyrics.fromSynced(optString(result, "syncedLyrics"))
                    : null;
            Lyrics lyrics = synced != null ? synced : Lyrics.fromPlain(optString(result, "plainLyrics"));
            if (lyrics == null) continue;

            int score = synced != null ? 10 : 0;
            if (difference >= 0) {
                if (difference <= 2_000) score += 6;
                else if (difference <= MAX_SYNCED_DURATION_DIFFERENCE_MS) score += 3;
                else score -= 6;
            }
            String foundTitle = normalize(cleanTitle(optString(result, "trackName")));
            if (foundTitle.equals(wantedTitle)) score += 8;
            else if (!containsEither(foundTitle, wantedTitle)) score -= 20;

            String foundArtist = normalize(optString(result, "artistName"));
            if (!wantedArtist.isEmpty()) {
                if (containsEither(foundArtist, wantedArtist)) score += 6;
                else score -= 20;
            }

            // A result with the wrong title and artist is another song.
            if (score > bestScore && score > -10) {
                best = lyrics;
                bestScore = score;
            }
        }
        return best;
    }

    private static boolean containsEither(String a, String b) {
        return !a.isEmpty() && !b.isEmpty() && (a.contains(b) || b.contains(a));
    }

    private static String normalize(String text) {
        return NOT_LETTER_OR_DIGIT.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
    }

    /** JSONObject.optString returns "null" for a JSON null. */
    private static String optString(JSONObject object, String name) {
        return object.isNull(name) ? "" : object.optString(name, "");
    }

    private static String httpGet(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", "application/json");

            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("LRCLIB returned HTTP " + status);
            }

            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                return output.toString("UTF-8");
            }
        } finally {
            connection.disconnect();
        }
    }
}

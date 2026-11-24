package app.aalyrics.extension.music;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shows the lyrics of the playing song as a tab of YouTube Music in Android Auto.
 *
 * Android Auto shows the folders of the app's root folder as tabs, and the items of a folder as a list.
 * The Lyrics tab is a folder with one item for each line. With timestamps, the list starts at the
 * line that is sung and is reloaded whenever the next line starts.
 */
@SuppressWarnings("unused")
public final class AndroidAutoLyricsPatch {

    /** Added by the patch to MediaBrowserServiceCompat, which has an obfuscated name. */
    public interface BrowserService {
        /** Calls the app's onLoadChildren(String, Result). */
        void patch_lyricsLoadChildren(String parentId, Object result);

        /** Calls the app's onLoadChildren(String, Result, Bundle). */
        void patch_lyricsLoadChildrenWithOptions(String parentId, Object result, Bundle options);

        /** Loads a folder again and sends its contents to the connection. */
        void patch_lyricsReload(String parentId, Object connection, Bundle options, Bundle notifyOptions);
    }

    /** Added by the patch to MediaBrowserServiceCompat.Result, which has an obfuscated name. */
    public interface BrowseResult {
        /** @return The ID of the folder this result belongs to. */
        Object patch_lyricsGetParentId();

        void patch_lyricsSendResult(Object value);
    }

    private enum Status { NOTHING_PLAYING, LOADING, READY, NOT_FOUND, FAILED }

    /** What the Lyrics tab shows. Replaced as a whole, so any thread can read it. */
    private static final class State {
        static final State EMPTY = new State(null, null, Status.NOTHING_PLAYING, null);

        final String trackKey;
        final LyricsProvider.Track track;
        final Status status;
        final Lyrics lyrics;

        State(String trackKey, LyricsProvider.Track track, Status status, Lyrics lyrics) {
            this.trackKey = trackKey;
            this.track = track;
            this.status = status;
            this.lyrics = lyrics;
        }
    }

    /** The arguments of Android Auto's request for the Lyrics folder, kept to load it again. */
    private static final class Subscription {
        final WeakReference<Object> service;
        final Bundle options;
        final Bundle notifyOptions;

        Subscription(Object service, Bundle options, Bundle notifyOptions) {
            this.service = new WeakReference<>(service);
            this.options = options;
            this.notifyOptions = notifyOptions;
        }
    }

    private static final String TAG = "AALyrics";

    /** YouTube Music uses Android Auto's package name as the ID of the root folder it gives Android Auto. */
    private static final String ROOT_ID = "com.google.android.projection.gearhead";
    private static final String MEDIA_ID_PREFIX = "aalyrics:";
    private static final String LYRICS_FOLDER_ID = MEDIA_ID_PREFIX + "folder";
    private static final String LINE_ID_PREFIX = MEDIA_ID_PREFIX + "line:";
    private static final String INFO_ID = MEDIA_ID_PREFIX + "info";

    /** Android Auto shows at most four tabs. */
    private static final int MAX_TABS = 4;

    // MediaBrowserCompat.MediaItem flags.
    private static final int FLAG_BROWSABLE = 1;
    private static final int FLAG_PLAYABLE = 2;

    // Show folders and items as a list and not as a grid of covers.
    private static final String CONTENT_STYLE_BROWSABLE_HINT = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT";
    private static final String CONTENT_STYLE_PLAYABLE_HINT = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT";
    private static final int CONTENT_STYLE_LIST = 1;

    private static final String CURRENT_LINE_PREFIX = "▶ ";
    /** A longer line continues in the second row of its list item. */
    private static final int MAX_TITLE_LENGTH = 40;

    /** The song's length often arrives shortly after its title, and it is needed to choose the lyrics. */
    private static final long FETCH_DELAY_MS = 700;
    private static final long MIN_RELOAD_INTERVAL_MS = 250;
    private static final int CACHE_SIZE = 30;

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    private static final Runnable FETCH = AndroidAutoLyricsPatch::fetchLyrics;
    private static final Runnable TICK = AndroidAutoLyricsPatch::tick;

    private static volatile State state = State.EMPTY;
    private static volatile MediaSession session;
    private static volatile MediaController controller;

    // The fields below are only used on the main thread.

    /** Connections that requested the Lyrics folder. Android Auto's entry goes away when it disconnects. */
    private static final WeakHashMap<Object, Subscription> subscriptions = new WeakHashMap<>();
    /** A null value means that LRCLIB has no lyrics for the song. */
    private static final Map<String, Lyrics> cache = new LinkedHashMap<String, Lyrics>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Lyrics> eldest) {
            return size() > CACHE_SIZE;
        }
    };
    /** The current line of the list that was last sent, or -1. */
    private static int lastSentIndex = -1;

    private static Object lyricsTab;
    private static Constructor<?> descriptionConstructor;
    private static Constructor<?> itemConstructor;

    private AndroidAutoLyricsPatch() {
    }

    /**
     * Overridden by the patch option "Follow playback".
     */
    public static boolean followPlayback() {
        return true;
    }

    // region Injection points

    /**
     * Injection point, at the start of MediaBrowserServiceCompat.performLoadChildren
     * of app versions where it takes one Bundle.
     */
    public static void onSubscription(Object service, String parentId, Object connection, Bundle options) {
        onSubscription(service, parentId, connection, options, null);
    }

    /**
     * Injection point, at the start of MediaBrowserServiceCompat.performLoadChildren.
     */
    public static void onSubscription(Object service, String parentId, Object connection,
                                      Bundle options, Bundle notifyOptions) {
        try {
            if (!LYRICS_FOLDER_ID.equals(parentId) || connection == null) return;

            Subscription subscription = new Subscription(service, options, notifyOptions);
            runOnMainThread(() -> {
                subscriptions.put(connection, subscription);
                scheduleTick();
            });
        } catch (Throwable ex) {
            Log.e(TAG, "onSubscription failed", ex);
        }
    }

    /**
     * Injection point, replaces the call to the app's onLoadChildren(String, Result).
     */
    public static void loadChildren(Object service, String parentId, Object result) {
        if (LYRICS_FOLDER_ID.equals(parentId)) {
            ((BrowseResult) result).patch_lyricsSendResult(buildLyricsItems());
        } else {
            ((BrowserService) service).patch_lyricsLoadChildren(parentId, result);
        }
    }

    /**
     * Injection point, replaces the call to the app's onLoadChildren(String, Result, Bundle).
     */
    public static void loadChildren(Object service, String parentId, Object result, Bundle options) {
        if (LYRICS_FOLDER_ID.equals(parentId)) {
            ((BrowseResult) result).patch_lyricsSendResult(buildLyricsItems());
        } else {
            ((BrowserService) service).patch_lyricsLoadChildrenWithOptions(parentId, result, options);
        }
    }

    /**
     * Injection point, at the start of MediaBrowserServiceCompat.Result.sendResult.
     *
     * @param value The folder's contents, which for the root folder are Android Auto's tabs.
     * @return The contents to send.
     */
    public static Object onSendResult(Object result, Object value) {
        try {
            if (!(value instanceof List) || !(result instanceof BrowseResult)) return value;
            if (!ROOT_ID.equals(((BrowseResult) result).patch_lyricsGetParentId())) return value;

            List<Object> tabs = new ArrayList<>((List<?>) value);
            // The app has no tabs to show, such as before the user has signed in.
            if (tabs.isEmpty()) return value;

            // With four or more tabs, the Lyrics tab replaces the last visible one.
            tabs.add(Math.min(tabs.size(), MAX_TABS - 1), lyricsTab());
            return tabs;
        } catch (Throwable ex) {
            Log.e(TAG, "Could not add the Lyrics tab", ex);
            return value;
        }
    }

    /**
     * Injection point, at the start of every MediaSession.Callback.onPlayFromMediaId.
     *
     * @return The media ID, or null if it is an item of the Lyrics tab and the app must not see it.
     */
    public static String onPlayFromMediaId(String mediaId) {
        if (mediaId == null || !mediaId.startsWith(MEDIA_ID_PREFIX)) return mediaId;

        try {
            if (mediaId.startsWith(LINE_ID_PREFIX)) {
                // aalyrics:line:<index>:<milliseconds>
                long timeMs = Long.parseLong(mediaId.substring(mediaId.lastIndexOf(':') + 1));
                MediaController currentController = controller;
                if (timeMs >= 0 && currentController != null) {
                    currentController.getTransportControls().seekTo(timeMs);
                }
            } else if (state.status == Status.FAILED) {
                // Tapping "Could not load lyrics" tries again.
                runOnMainThread(() -> {
                    State current = state;
                    if (current.status != Status.FAILED) return;
                    setState(new State(current.trackKey, current.track, Status.LOADING, null));
                    mainHandler.removeCallbacks(FETCH);
                    mainHandler.post(FETCH);
                });
            }
        } catch (Throwable ex) {
            Log.e(TAG, "onPlayFromMediaId failed", ex);
        }
        return null;
    }

    /**
     * Injection point, before every call to MediaSession.setMetadata.
     */
    public static void onMediaSession(MediaSession mediaSession) {
        if (mediaSession == null || mediaSession == session) return;

        runOnMainThread(() -> {
            try {
                if (mediaSession == session) return;

                MediaController previous = controller;
                if (previous != null) previous.unregisterCallback(CONTROLLER_CALLBACK);

                MediaController newController = mediaSession.getController();
                newController.registerCallback(CONTROLLER_CALLBACK, mainHandler);
                session = mediaSession;
                controller = newController;
                onMetadataChanged(newController.getMetadata());
            } catch (Throwable ex) {
                Log.e(TAG, "Could not observe the media session", ex);
            }
        });
    }

    // endregion

    // region Playing song

    private static final MediaController.Callback CONTROLLER_CALLBACK = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            try {
                AndroidAutoLyricsPatch.onMetadataChanged(metadata);
            } catch (Throwable ex) {
                Log.e(TAG, "onMetadataChanged failed", ex);
            }
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState playbackState) {
            try {
                // Seeking, pausing and resuming all change which line is next and when.
                reloadIfLineChanged();
                scheduleTick();
            } catch (Throwable ex) {
                Log.e(TAG, "onPlaybackStateChanged failed", ex);
            }
        }

        @Override
        public void onSessionDestroyed() {
            session = null;
            controller = null;
        }
    };

    private static void onMetadataChanged(MediaMetadata metadata) {
        LyricsProvider.Track track = toTrack(metadata);
        String trackKey = track == null ? null : track.title + '\n' + track.artist;
        State current = state;

        if (Objects.equals(trackKey, current.trackKey)) {
            // Keep the length if it arrived after the title.
            if (track != null && current.track.durationMs <= 0 && track.durationMs > 0) {
                state = new State(trackKey, track, current.status, current.lyrics);
            }
            return;
        }

        mainHandler.removeCallbacks(FETCH);
        if (track == null) {
            setState(State.EMPTY);
            return;
        }

        if (cache.containsKey(trackKey)) {
            Lyrics cached = cache.get(trackKey);
            setState(new State(trackKey, track, cached == null ? Status.NOT_FOUND : Status.READY, cached));
        } else {
            setState(new State(trackKey, track, Status.LOADING, null));
            mainHandler.postDelayed(FETCH, FETCH_DELAY_MS);
        }
    }

    private static LyricsProvider.Track toTrack(MediaMetadata metadata) {
        if (metadata == null) return null;

        String title = firstText(metadata,
                MediaMetadata.METADATA_KEY_TITLE,
                MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        if (title == null) return null;

        String artist = firstText(metadata,
                MediaMetadata.METADATA_KEY_ARTIST,
                MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
                MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        long durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        return new LyricsProvider.Track(title, artist == null ? "" : artist, Math.max(durationMs, 0));
    }

    private static String firstText(MediaMetadata metadata, String... keys) {
        for (String key : keys) {
            CharSequence text = metadata.getText(key);
            if (text != null && text.toString().trim().length() > 0) return text.toString().trim();
        }
        return null;
    }

    private static void fetchLyrics() {
        State requested = state;
        if (requested.status != Status.LOADING) return;

        executor.execute(() -> {
            Lyrics lyrics = null;
            boolean failed = false;
            try {
                lyrics = LyricsProvider.fetch(requested.track);
            } catch (Throwable ex) {
                Log.w(TAG, "Could not download lyrics", ex);
                failed = true;
            }

            Lyrics result = lyrics;
            boolean hasFailed = failed;
            mainHandler.post(() -> {
                if (!hasFailed) cache.put(requested.trackKey, result);

                State current = state;
                // Another song started while the lyrics were downloading.
                if (!Objects.equals(current.trackKey, requested.trackKey)) return;

                Status status = hasFailed ? Status.FAILED
                        : result == null ? Status.NOT_FOUND
                        : Status.READY;
                setState(new State(current.trackKey, current.track, status, result));
            });
        });
    }

    /** @return The playback position in milliseconds, or -1 if unknown. */
    private static long positionMs() {
        PlaybackState playbackState = playbackState();
        if (playbackState == null) return -1;

        long position = playbackState.getPosition();
        if (playbackState.getState() == PlaybackState.STATE_PLAYING) {
            long elapsed = SystemClock.elapsedRealtime() - playbackState.getLastPositionUpdateTime();
            if (elapsed > 0) position += (long) (elapsed * playbackSpeed(playbackState));
        }
        return position;
    }

    private static PlaybackState playbackState() {
        MediaController currentController = controller;
        return currentController == null ? null : currentController.getPlaybackState();
    }

    private static float playbackSpeed(PlaybackState playbackState) {
        float speed = playbackState.getPlaybackSpeed();
        return speed > 0 ? speed : 1;
    }

    // endregion

    // region Lyrics tab

    private static void setState(State newState) {
        state = newState;
        lastSentIndex = -1;
        reloadFolder();
        scheduleTick();
    }

    /** Sends the Lyrics folder again to every connection that requested it. */
    private static void reloadFolder() {
        if (subscriptions.isEmpty()) return;

        // Reloading calls onSubscription, which changes the map.
        for (Map.Entry<Object, Subscription> entry : new ArrayList<>(subscriptions.entrySet())) {
            Object connection = entry.getKey();
            Subscription subscription = entry.getValue();
            Object service = subscription.service.get();
            if (connection == null) continue;
            if (!(service instanceof BrowserService)) {
                subscriptions.remove(connection);
                continue;
            }

            try {
                ((BrowserService) service).patch_lyricsReload(
                        LYRICS_FOLDER_ID, connection, subscription.options, subscription.notifyOptions);
            } catch (Throwable ex) {
                Log.e(TAG, "Could not reload the Lyrics tab", ex);
                subscriptions.remove(connection);
            }
        }
    }

    private static void reloadIfLineChanged() {
        State current = state;
        if (!isFollowing(current)) return;
        if (current.lyrics.indexAt(positionMs()) != lastSentIndex) reloadFolder();
    }

    private static boolean isFollowing(State current) {
        return followPlayback() && current.status == Status.READY && current.lyrics.synced;
    }

    /** Reloads the tab when the next line starts. */
    private static void scheduleTick() {
        mainHandler.removeCallbacks(TICK);

        State current = state;
        if (!isFollowing(current) || subscriptions.isEmpty()) return;

        PlaybackState playbackState = playbackState();
        if (playbackState == null || playbackState.getState() != PlaybackState.STATE_PLAYING) return;

        long position = positionMs();
        int next = current.lyrics.indexAt(position) + 1;
        if (next >= current.lyrics.lines.size()) return;

        long untilNextLine = current.lyrics.lines.get(next).timeMs - position;
        long delay = (long) (untilNextLine / playbackSpeed(playbackState));
        mainHandler.postDelayed(TICK, Math.max(delay, MIN_RELOAD_INTERVAL_MS));
    }

    private static void tick() {
        try {
            reloadIfLineChanged();
            scheduleTick();
        } catch (Throwable ex) {
            Log.e(TAG, "Could not follow the playback", ex);
        }
    }

    private static List<Object> buildLyricsItems() {
        List<Object> items = new ArrayList<>();
        try {
            State current = state;
            String song = current.track == null ? null
                    : current.track.artist.isEmpty() ? current.track.title
                    : current.track.title + " • " + current.track.artist;

            switch (current.status) {
                case NOTHING_PLAYING:
                    items.add(newItem(INFO_ID, "Nothing is playing", "Play a song to see its lyrics",
                            null, null, FLAG_PLAYABLE));
                    break;
                case LOADING:
                    items.add(newItem(INFO_ID, "Loading lyrics…", song, null, null, FLAG_PLAYABLE));
                    break;
                case NOT_FOUND:
                    items.add(newItem(INFO_ID, "No lyrics found", song, null, null, FLAG_PLAYABLE));
                    break;
                case FAILED:
                    items.add(newItem(INFO_ID, "Could not load lyrics", "Tap to try again",
                            null, null, FLAG_PLAYABLE));
                    break;
                case READY:
                    addLines(items, current.lyrics);
                    break;
            }
        } catch (Throwable ex) {
            Log.e(TAG, "Could not create the lyrics list", ex);
        }
        return items;
    }

    private static void addLines(List<Object> items, Lyrics lyrics) throws ReflectiveOperationException {
        int currentIndex = lyrics.indexAt(positionMs());
        // Android Auto cannot scroll a list for the app, so the list starts at the current line.
        int first = isFollowing(state) ? Math.max(currentIndex, 0) : 0;
        lastSentIndex = currentIndex;

        for (int i = first; i < lyrics.lines.size(); i++) {
            Lyrics.Line line = lyrics.lines.get(i);
            String text = i == currentIndex ? CURRENT_LINE_PREFIX + line.text : line.text;

            String title = text;
            String subtitle = null;
            if (text.length() > MAX_TITLE_LENGTH) {
                int split = text.lastIndexOf(' ', MAX_TITLE_LENGTH);
                if (split < MAX_TITLE_LENGTH / 2) split = MAX_TITLE_LENGTH;
                title = text.substring(0, split).trim();
                subtitle = text.substring(split).trim();
            }

            // Tapping a line with a timestamp seeks to it, see onPlayFromMediaId.
            items.add(newItem(LINE_ID_PREFIX + i + ':' + line.timeMs, title, subtitle,
                    null, null, FLAG_PLAYABLE));
        }
    }

    private static synchronized Object lyricsTab() throws ReflectiveOperationException {
        if (lyricsTab == null) {
            Bundle extras = new Bundle();
            extras.putInt(CONTENT_STYLE_BROWSABLE_HINT, CONTENT_STYLE_LIST);
            extras.putInt(CONTENT_STYLE_PLAYABLE_HINT, CONTENT_STYLE_LIST);
            lyricsTab = newItem(LYRICS_FOLDER_ID, "Lyrics", null, createTabIcon(), extras, FLAG_BROWSABLE);
        }
        return lyricsTab;
    }

    /** Draws lines of text, as the app has no lyrics icon that could be used for the tab. */
    private static Bitmap createTabIcon() {
        final int size = 96;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);

        float[] lineWidths = {64, 48, 64, 32};
        float barHeight = 9;
        float top = 16;
        for (float lineWidth : lineWidths) {
            canvas.drawRoundRect(new RectF(16, top, 16 + lineWidth, top + barHeight),
                    barHeight / 2, barHeight / 2, paint);
            top += 18.5f;
        }
        return bitmap;
    }

    /**
     * Creates a MediaBrowserCompat.MediaItem. The classes are part of the app and not of Android,
     * so they are used with reflection. The patch checks that both constructors exist.
     */
    private static synchronized Object newItem(String mediaId, CharSequence title, CharSequence subtitle,
                                               Bitmap icon, Bundle extras, int flags)
            throws ReflectiveOperationException {
        if (itemConstructor == null) {
            Class<?> descriptionClass = Class.forName("android.support.v4.media.MediaDescriptionCompat");
            Constructor<?> description = descriptionClass.getDeclaredConstructor(
                    String.class, CharSequence.class, CharSequence.class, CharSequence.class,
                    Bitmap.class, Uri.class, Bundle.class, Uri.class);
            description.setAccessible(true);
            Constructor<?> item = Class.forName("android.support.v4.media.MediaBrowserCompat$MediaItem")
                    .getDeclaredConstructor(descriptionClass, int.class);
            item.setAccessible(true);

            descriptionConstructor = description;
            itemConstructor = item;
        }

        Object description = descriptionConstructor.newInstance(
                mediaId, title, subtitle, null, icon, null, extras, null);
        return itemConstructor.newInstance(description, flags);
    }

    // endregion

    private static void runOnMainThread(Runnable runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run();
        } else {
            mainHandler.post(runnable);
        }
    }
}

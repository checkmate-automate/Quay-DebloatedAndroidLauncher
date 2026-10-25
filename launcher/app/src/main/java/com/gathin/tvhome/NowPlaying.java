package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.List;

/**
 * The now-playing strip under the date: what is playing, and the three controls for it.
 *
 * <p>It reads whatever media session is active rather than talking to any particular app, so it follows
 * Spotify, the browser or anything else that publishes one. Access needs an enabled notification listener
 * ({@link MediaListener}); without it {@code getActiveSessions} throws SecurityException, which is caught
 * and simply leaves the strip hidden.
 *
 * <p>The strip hides itself whenever nothing is playing, so a quiet home screen looks exactly as it did
 * before this existed.
 */
final class NowPlaying {

    private static final String TAG = "TvHome.NowPlaying";

    private final Context ctx;
    private final View root;
    private final ImageView art;
    private final TextView title, artist;
    private final TextView prev, play, next;
    private final MediaSessionManager sessions;
    private final ComponentName listener;

    /** The session being shown, and the callback registered on it; both null while the strip is hidden. */
    private MediaController controller;
    private MediaController.Callback callback;
    /** The owner's choice from the top bar, kept apart from whether anything happens to be playing. */
    private boolean shown = true;

    NowPlaying(Context ctx, View strip) {
        this.ctx = ctx;
        this.root = strip;
        this.art = strip.findViewById(R.id.npArt);
        this.title = strip.findViewById(R.id.npTitle);
        this.artist = strip.findViewById(R.id.npArtist);
        this.prev = strip.findViewById(R.id.npPrev);
        this.play = strip.findViewById(R.id.npPlay);
        this.next = strip.findViewById(R.id.npNext);
        this.sessions = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
        this.listener = new ComponentName(ctx, MediaListener.class);

        prev.setOnClickListener(v -> transport(Transport.PREV));
        play.setOnClickListener(v -> transport(Transport.PLAY_PAUSE));
        next.setOnClickListener(v -> transport(Transport.NEXT));
    }

    private enum Transport { PREV, PLAY_PAUSE, NEXT }

    /**
     * Shows or hides the strip by choice. Turning it off also stops whatever is playing: the control is
     * read as "music off", and leaving a track playing with nothing on screen to stop it would strand it.
     */
    void setShown(boolean show) {
        shown = show;
        if (show) {
            draw();
            return;
        }
        MediaController c = controller;
        if (c != null) {
            PlaybackState s = c.getPlaybackState();
            if (s != null && s.getState() == PlaybackState.STATE_PLAYING) c.getTransportControls().pause();
        }
        hide();
    }

    boolean shown() {
        return shown;
    }

    /** Starts following the active session, and keeps following as apps come and go. */
    void start() {
        if (sessions == null) return;
        try {
            sessions.addOnActiveSessionsChangedListener(this::bind, listener);
            bind(sessions.getActiveSessions(listener));
        } catch (SecurityException e) {
            // The notification listener is not enabled: nothing to show, and nothing to report loudly.
            Log.i(TAG, "no media access; the strip stays hidden");
            hide();
        }
    }

    void stop() {
        if (sessions != null) {
            try {
                sessions.removeOnActiveSessionsChangedListener(this::bind);
            } catch (RuntimeException ignored) {
                // a lambda is not the same object twice; the controller callback below is what matters
            }
        }
        release();
    }

    /** Picks a session worth showing: one that is playing, else one that is merely loaded. */
    private void bind(List<MediaController> active) {
        MediaController best = null;
        if (active != null) {
            for (MediaController c : active) {
                PlaybackState s = c.getPlaybackState();
                if (s == null) continue;
                if (s.getState() == PlaybackState.STATE_PLAYING) {
                    best = c;
                    break;
                }
                if (best == null && s.getState() == PlaybackState.STATE_PAUSED) best = c;
            }
        }
        if (best == null) {
            release();
            hide();
            return;
        }
        if (controller != null && controller.getSessionToken().equals(best.getSessionToken())) {
            draw();
            return;
        }
        release();
        controller = best;
        callback = new MediaController.Callback() {
            @Override
            public void onMetadataChanged(MediaMetadata metadata) {
                draw();
            }

            @Override
            public void onPlaybackStateChanged(PlaybackState state) {
                draw();
            }

            @Override
            public void onSessionDestroyed() {
                release();
                hide();
            }
        };
        controller.registerCallback(callback);
        draw();
    }

    private void release() {
        if (controller != null && callback != null) controller.unregisterCallback(callback);
        controller = null;
        callback = null;
    }

    /**
     * Takes the strip off screen while the owner still wants it, keeping its space.
     *
     * <p>Always INVISIBLE, never GONE. GONE reclaims the space and reflows everything below it, moving the
     * dock, its name pill and the rest of the screen — every time a track ends, a session appears, or the
     * strip is switched off. Holding the space costs nothing visually, since what shows through is the
     * wallpaper, and in exchange the layout never moves.
     */
    private void hide() {
        root.setVisibility(View.INVISIBLE);
    }

    /** Puts the current track on screen, or hides the strip when there is nothing to say about it. */
    private void draw() {
        MediaController c = controller;
        if (!shown) {
            hide();
            return;
        }
        if (c == null) {
            hide();
            return;
        }
        MediaMetadata m = c.getMetadata();
        PlaybackState s = c.getPlaybackState();
        CharSequence name = m == null ? null : m.getText(MediaMetadata.METADATA_KEY_TITLE);
        if (TextUtils.isEmpty(name)) {
            hide();
            return;
        }
        title.setText(name);

        CharSequence by = m.getText(MediaMetadata.METADATA_KEY_ARTIST);
        if (TextUtils.isEmpty(by)) by = m.getText(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        artist.setText(by == null ? "" : by);
        // INVISIBLE rather than GONE throughout: a missing artist, a missing cover or an unsupported
        // transport button must not resize the strip and nudge everything around it.
        artist.setVisibility(TextUtils.isEmpty(by) ? View.INVISIBLE : View.VISIBLE);

        Bitmap cover = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (cover == null) cover = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (cover != null) {
            art.setImageBitmap(cover);
            art.setVisibility(View.VISIBLE);
        } else {
            art.setImageDrawable(null);
            art.setVisibility(View.INVISIBLE);
        }

        boolean playing = s != null && s.getState() == PlaybackState.STATE_PLAYING;
        play.setCompoundDrawablesRelativeWithIntrinsicBounds(
                playing ? R.drawable.ic_np_pause : R.drawable.ic_np_play, 0, 0, 0);
        // Only offer a control the session says it supports, so a button never does nothing.
        long actions = s == null ? 0 : s.getActions();
        prev.setVisibility((actions & PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0 ? View.VISIBLE : View.INVISIBLE);
        next.setVisibility((actions & PlaybackState.ACTION_SKIP_TO_NEXT) != 0 ? View.VISIBLE : View.INVISIBLE);
        root.setVisibility(View.VISIBLE);
    }

    private void transport(Transport what) {
        MediaController c = controller;
        if (c == null) return;
        PlaybackState s = c.getPlaybackState();
        boolean playing = s != null && s.getState() == PlaybackState.STATE_PLAYING;
        switch (what) {
            case PREV:
                c.getTransportControls().skipToPrevious();
                break;
            case NEXT:
                c.getTransportControls().skipToNext();
                break;
            default:
                if (playing) c.getTransportControls().pause();
                else c.getTransportControls().play();
                break;
        }
    }
}

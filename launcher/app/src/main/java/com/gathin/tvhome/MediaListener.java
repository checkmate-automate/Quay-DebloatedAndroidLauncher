package com.gathin.tvhome;

import android.service.notification.NotificationListenerService;

/**
 * Does nothing, and exists only to be named.
 *
 * <p>Reading what is playing goes through {@code MediaSessionManager.getActiveSessions}, which Android
 * gates behind either a system-only permission or an <em>enabled notification listener</em> belonging to
 * the caller. So the launcher declares one, listens to nothing, and passes this component as its proof of
 * access. Nothing here ever handles a notification.
 *
 * <p>It still has to be switched on once, which the owner cannot do from inside this app:
 *
 * <pre>
 * adb shell settings put secure enabled_notification_listeners \
 *     "$(adb shell settings get secure enabled_notification_listeners):com.gathin.tvhome/com.gathin.tvhome.MediaListener"
 * </pre>
 *
 * Until then {@link NowPlaying} simply finds no sessions and the header stays as it was.
 */
public class MediaListener extends NotificationListenerService {
}

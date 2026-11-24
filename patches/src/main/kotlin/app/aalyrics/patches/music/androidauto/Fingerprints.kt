package app.aalyrics.patches.music.androidauto

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall

/**
 * MediaBrowserServiceCompat.performLoadChildren, which asks the app for the contents of a folder
 * and delivers them to the connected browser (Android Auto).
 *
 * The class name is obfuscated, so this is matched against the superclasses of MusicBrowserService.
 */
internal object PerformLoadChildrenFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf(
        "onLoadChildren must call detach() or sendResult() before returning for package="
    )
)

/** MediaBrowserServiceCompat.Result.sendResult, which every folder's contents pass through. */
internal fun sendResultFingerprint(resultType: String) = Fingerprint(
    definingClass = resultType,
    returnType = "V",
    parameters = listOf("Ljava/lang/Object;"),
    strings = listOf(
        "sendResult() called when either sendResult() or sendError() had already been called for: "
    )
)

/**
 * The callbacks that receive media button and browse commands from the system.
 * The method name belongs to the Android framework, so it is never obfuscated.
 */
internal object PlayFromMediaIdFingerprint : Fingerprint(
    superclass = "Landroid/media/session/MediaSession\$Callback;",
    name = "onPlayFromMediaId",
    returnType = "V",
    parameters = listOf("Ljava/lang/String;", "Landroid/os/Bundle;")
)

/** Every method that gives the app's media session new song metadata. */
internal object SetMetadataFingerprint : Fingerprint(
    filters = listOf(
        methodCall(
            definingClass = "Landroid/media/session/MediaSession;",
            name = "setMetadata",
            parameters = listOf("Landroid/media/MediaMetadata;")
        )
    )
)

package app.aalyrics.patches.music.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

internal object Constants {
    /**
     * The same app targets as the official Morphe patches for YouTube Music,
     * so one APK can be patched with both.
     *
     * Every target is experimental because the lyrics tab has not been verified on a car yet.
     */
    val COMPATIBILITY_YOUTUBE_MUSIC = Compatibility(
        name = "YouTube Music",
        packageName = "com.google.android.apps.youtube.music",
        apkFileType = ApkFileType.APK_REQUIRED,
        appIconColor = 0xFF0000,
        signatures = setOf(
            // Android 13+
            "6a2f65ec694a6a632acdcb5080912a565f903d4b8d83f0eb8e44fbdf2660d8e1",
            // Android 7+
            "a2a1ad7ba7f41dfca4514e2afeb90691719af6d0fdbed4b09bbf0ed897701ceb"
        ),
        targets = listOf(
            AppTarget(version = "9.46.51", minSdk = 28, isExperimental = true),
            AppTarget(version = "9.45.51", minSdk = 28, isExperimental = true),
            AppTarget(version = "9.44.52", minSdk = 26, isExperimental = true),
            AppTarget(version = "9.30.53", minSdk = 26, isExperimental = true),
            AppTarget(version = "9.20.53", minSdk = 26, isExperimental = true),
            AppTarget(version = "9.15.51", minSdk = 26, isExperimental = true)
        )
    )
}

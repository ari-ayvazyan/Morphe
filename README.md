# 🎤 AA Lyrics Patches

Patches for use with [Morphe](https://morphe.software) that add a **Lyrics tab to YouTube Music in Android Auto**.

## ❓ About

The **Android Auto lyrics** patch adds a fourth tab to YouTube Music on the car screen. The tab lists
the lyrics of the song that is playing:

- Lyrics are downloaded from [LRCLIB](https://lrclib.net), a free lyrics database. The song's title
  and artist are sent to lrclib.net to find them. No account or key is needed.
- If the lyrics have timestamps, the list starts at the line that is sung and moves on with the song.
  Tapping a line jumps to that part of the song.
- Lyrics without timestamps are shown as one list for the whole song.
- The patch option **Follow playback** turns the moving list off. The whole song is then listed and
  only reloaded when the song changes.

The patch works together with the official [Morphe patches](https://github.com/MorpheApp/morphe-patches)
for YouTube Music, including their *Android Auto* patch. Select the patches of both sources when you
patch the app. With the official *Android Auto* patch the tabs are Home, Podcasts, Library and Lyrics.

> [!WARNING]
> The patch has not been tested in a car yet, so every app version is marked as experimental.
> Do not read lyrics while you are driving.

### How to use these patches

Click the button below, or manually add this repo url to Morphe Manager: https://github.com/ari-ayvazyan/Morphe

<a href="https://morphe.software/add-source?github=ari-ayvazyan/Morphe">
  <img src=".github/assets/add-to-morphe.svg" alt="Add to Morphe" height="48">
</a>

Morphe Manager installs a source from its GitHub releases. A release is created automatically when a
`feat:` or `fix:` commit reaches the `main` branch (stable) or the `dev` branch (pre-release).

## 🩹 Patches list

<!-- PATCHES_START -->

<!-- Do not modify this section by hand. The patch list is generated when release.yml creates a new release. -->

| App | Patch | Description |
|-----|-------|-------------|
| YouTube Music | Android Auto lyrics | Adds a Lyrics tab to Android Auto that shows the lyrics of the song that is playing. |

<!-- PATCHES_END -->

## ⚙️ How it works

Android Auto shows the folders that a media app returns for its root folder as tabs, and the items of
a folder as a list. The patch:

1. Adds a Lyrics folder to the tabs YouTube Music returns for Android Auto.
2. Answers requests for that folder with one list item for each line of the lyrics.
3. Reads the song and the playback position from the app's media session, and sends the folder
   again when the song changes or the next line starts.

All hooks are in the AndroidX media classes inside the app (found by their log messages) and on
Android framework methods. None are in YouTube Music's own obfuscated code, so the patch does not
depend on one app version and does not collide with the hooks of the official patches.


### 🛠️ Building locally

- Run `./gradlew buildAndroid`
- The built patches .mpp file is found in `patches/build/libs/patches-*.mpp`
- Patch the mpp file using [Morphe-Desktop](https://github.com/MorpheApp/morphe-desktop)
  like any other patch bundle.

See the [Morphe documentation](https://github.com/MorpheApp/morphe-documentation) for more information.

## 📜 License

AA Lyrics Patches are licensed under the [GNU General Public License v3.0](LICENSE)

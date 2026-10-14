<p align="center">
  <img src="app/src/main/res/drawable-nodpi/wammy_logo.png" alt="Wammy Logo" width="112" />
</p>

<h1 align="center">Wammy</h1>

<p align="center">
  A modern, open-source manga, manhwa, and light novel reader for Android with a clean Material 3 design. Supports Mihon and LNReader extensions.
</p>

<p align="center">
  <a href="https://github.com/kainotch/Wammy/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/kainotch/Wammy?style=for-the-badge&logo=github&label=latest"></a>
  <a href="https://discord.com/invite/qybFSATs7Q"><img alt="Discord" src="https://img.shields.io/badge/Discord-Join-5865F2?style=for-the-badge&logo=discord&logoColor=white"></a>
  <a href="https://github.com/kainotch/Wammy/releases"><img alt="Total downloads" src="https://img.shields.io/github/downloads/kainotch/Wammy/total?style=for-the-badge&logo=github&label=downloads"></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/github/license/kainotch/Wammy?style=for-the-badge"></a>
</p>

<p align="center">
  <a href="https://developer.android.com/"><img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white"></a>
  <a href="https://kotlinlang.org/"><img alt="Kotlin" src="https://img.shields.io/github/languages/top/kainotch/Wammy?style=for-the-badge&logo=kotlin&logoColor=white"></a>
  <a href="https://developer.android.com/compose"><img alt="Jetpack Compose" src="https://img.shields.io/badge/Jetpack%20Compose-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white"></a>
  <a href="https://m3.material.io/"><img alt="Material 3" src="https://img.shields.io/badge/Material%203-Design-6750A4?style=for-the-badge&logo=materialdesign&logoColor=white"></a>
</p>

<p align="center">
  <a href="https://github.com/kainotch/Wammy/releases/latest"><strong>Download APK</strong></a>
  |
  <a href="https://kainotch.github.io/WammyWeb/"><strong>Website</strong></a>
  |
  <a href="https://discord.com/invite/qybFSATs7Q"><strong>Discord</strong></a>
  |
  <a href="https://github.com/kainotch/Wammy/issues"><strong>Issues</strong></a>
</p>

---

## About

Wammy is a free and open-source manga, manhwa, and light novel reader for Android that brings comics and novels together into a single, fast, and customizable interface.

Please give the repo a star if you like the project. It helps more people find it.

## Screenshots

<p align="center">
  <img src=".github/images/home.jpg" alt="Wammy home screen" width="23%" />
  <img src=".github/images/library.jpg" alt="Wammy library screen" width="23%" />
  <img src=".github/images/details.jpg" alt="Wammy manga details screen" width="23%" />
  <img src=".github/images/profile.jpg" alt="Wammy profile screen" width="23%" />
</p>

<p align="center">
  <sub>Home & Recommendations | Library Grid | Manga Details & Suggestions | User Profile & Favorites</sub>
</p>

## Highlights

- Unified library supporting both manga and light novels side-by-side with unread badges, custom categories, and filtering.
- Multi-source extension ecosystem supporting APK extensions, sandboxed in-app extensions, and LNReader JS plugins.
- Google Drive cloud sync and local backup/restore to keep your library, history, categories, and settings synced across devices.
- Custom user profiles with cloud-synced Top 5 favorite manga and light novels.
- Built-in Text-to-Speech (TTS) support and customizable typography for light novel reading.
- Automatic reading progress tracking with AniList, MyAnimeList, Kitsu, and more.
- Free and open-source under the Apache License 2.0.

<details>
<summary><strong>Features</strong></summary>

- Unified manga, webtoon, and light novel reading experience with configurable viewer modes (Left-to-Right, Right-to-Left, Vertical, and Webtoon Strip).
- Advanced novel reader with custom fonts, text sizing, margins, background themes, and Text-to-Speech (TTS) playback.
- Sandboxed extension mode allowing extensions to be installed and updated silently inside the app without system install prompts.
- Dedicated Discover feed aggregating trending and latest updates across your installed sources.
- Google Drive cloud backup and automatic periodic synchronization.
- Local backup and restore support for safeguarding or migrating your library.
- Offline reading with background download queue management for both manga chapters and novel volumes.
- Two-way tracker integration with AniList, MyAnimeList, Kitsu, Shikimori, Bangumi, and MangaUpdates.
- Dynamic Material 3 theming with pure dark/OLED mode support.
- App lock with biometric authentication and incognito reading mode.

</details>

## Star History

<a href="https://www.star-history.com/#kainotch/Wammy&Date">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/svg?repos=kainotch/Wammy&type=Date&theme=dark" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/svg?repos=kainotch/Wammy&type=Date" />
   <img alt="Star History Chart" src="https://api.star-history.com/svg?repos=kainotch/Wammy&type=Date" />
 </picture>
</a>

## Install

1. Open the [Official Download Page](https://kainotch.github.io/WammyWeb/download/) or the [latest GitHub release](https://github.com/kainotch/Wammy/releases/latest).
2. Download the newest `Wammy` APK.
3. Install it on a compatible Android device (enable **Install from Unknown Sources** if prompted).
4. Add your preferred extension repository or sources, then start reading.

## Building from Source

### Prerequisites

- Android Studio (Latest Stable)
- JDK 17+
- Android SDK API level 35

### Steps

```bash
# Clone the repository
git clone https://github.com/kainotch/Wammy.git
cd Wammy

# Build the debug APK
./gradlew :app:assembleDebug
```

The APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.

## FAQ

### Does Wammy include built-in manga or novels?
> No. Wammy does not include built-in content. Sources are provided through external extensions, JS plugins, or repositories added by users.

### Is Wammy free?
> Yes. Wammy is completely free and open-source under the Apache License 2.0.

### How does Cloud Sync work?
> You can sign in with your Google account to back up and synchronize your library, reading progress, categories, and tracker bindings to your private Google Drive AppData folder.

### Can I contribute?
> Yes. Pull requests for bug fixes, improvements, and new features are welcome.

## Contribute

1. Fork the repository.
2. Create a focused branch for your change.
3. Build locally with `./gradlew :app:assembleDebug`.
4. Open a Pull Request with a clear explanation of what changed.

For full documentation, extension guides, and FAQs, visit the [Official Website](https://kainotch.github.io/WammyWeb/).

## License

```text
Copyright 2026 Wammy Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

## Disclaimer

The developer(s) of this application do not have any affiliation with the content providers available. Content is provided by external libraries or extensions added or imported by users; the application itself does not host or include any built-in content.

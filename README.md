# Forte

Forte is a music app for Android. It plays songs, albums and playlists from YouTube Music in a clean, dark player — no ads in the way, and your library stays on your phone.

**[Download the latest release](../../releases/latest)** — Android 8.0 or newer.

## What it does

- Home with Listen again, quick picks, charts and new releases — shelves keep loading as you scroll
- Explore: moods, moments and genres as picture cards
- Search for songs, albums, playlists and artists
- Optional sign-in: your likes, playlists, subscriptions and history come with you, with your name and profile photo on the account card
- Your own library on the phone: favourites, playlists, history and downloads, kept apart from the account
- Downloads for offline play, inside the Library
- Background play that keeps going when you leave the app, with notification and lock-screen controls
- A player with lyrics, song credits, radio, a queue, sleep timer, playback speed and an audio output picker
- Region and language you pick yourself, used across Home, Explore and charts
- In-app updates: Forte checks this repo for new versions and installs them in place

## Architecture

One Android app module — Kotlin and Jetpack Compose, Media3/ExoPlayer for playback.

- `data/ForteEngine.kt` and `data/YtAccount.kt` — all talking to YouTube Music happens on the device. There is no Forte server in the play path; streams are resolved on the phone at tap time.
- `playback/` — a foreground service and the player controller own the queue, shuffle, repeat and re-resolving expired stream links.
- `data/db/` — a Room database holds the on-phone library: favourites, playlists, history, follows and downloads.
- `data/Downloads.kt` — downloads are plain audio files in app storage, playable offline.
- `ui/` — one screen per file. The look is fixed: black surfaces, one red accent, a frosted-glass capsule dock floating over the content.
- `data/UpdateChecker.kt` — reads `stable.json` from this repo to offer updates.

## Build

```
./gradlew :app:assembleRelease
```

Needs JDK 17 and Android SDK 35.

---

Made by Kotlinsyd

# Overtime for Android

Overtime is an IPTV player for Android phones and tablets: live TV with a TV guide, movies and series from your provider, and Continue watching. It is the Android companion of the Overtime Windows app.

It's built on [Next Player](https://github.com/anilbeesetti/nextplayer), so it also plays your own videos, network shares and streams.

## Features

### Live TV

- M3U playlist links and Xtream Codes logins
- Several sources at once, switchable from chips and reorderable by dragging
- Channel groups as a full list, with favourite groups pinned to the top in their original order
- Favourite channels
- Channel search
- Now and next programme on every channel, with progress
- TV guide grid with a 12-hour timeline, a now line and programme details
- Guides read from the playlist or set by hand (several addresses allowed), cached for 12 hours
- Automatic refresh when the app opens, plus manual refresh
- Next and previous channel in the player

### Movies & series

- Movie and series library from Xtream Codes sources
- Categories, search, posters, ratings and years
- Details page with plot, genre, cast and backdrop
- Seasons and episodes
- An episode plays with the rest of its season queued, so next episode and autoplay work
- Playback resumes where it was left

### Continue watching

- Own tab in the navigation bar
- One card per movie or series, newest first, with artwork, progress and time left
- Resume a movie or episode, or start the next episode once one is finished
- Series stay until dismissed; finished movies leave by themselves
- Dismiss button on every card

### Player and library (from Next Player)

- Local videos with tree, folder and file views, search and history
- Network storage (SMB, FTP, SFTP, WebDAV) and network stream URLs, under More
- Playlists, under More, with home-screen shortcuts
- Audio and subtitle track selection, external subtitles, subtitle delay
- Hardware and software decoders (FFmpeg audio extension)
- Swipe for brightness, volume and seeking; zoom gesture
- Playback speed, picture-in-picture and background playback
- Material 3 design with dynamic colours

## Supported formats

- **Video**: H.263, H.264 AVC, H.265 HEVC, MPEG-4 SP, VP8, VP9, AV1 (support depends on the device)
- **Audio**: Vorbis, Opus, FLAC, ALAC, PCM/WAVE, MP1, MP2, MP3, AMR, AAC, AC-3, E-AC-3, DTS, DTS-HD, TrueHD
- **Subtitles**: SRT, SSA, ASS, TTML, VTT, DVB

## Building

Open the project in Android Studio, or build from the command line with the JDK bundled with Android Studio:

```
./gradlew :app:assembleDebug
```

The IPTV features live in the `feature/iptv` module.

## Credits

Overtime for Android is based on [Next Player](https://github.com/anilbeesetti/nextplayer) by Anil Kumar Beesetti and its contributors.

## License

Licensed under the GNU General Public License v3.0, like Next Player. See the [LICENSE](LICENSE) file.

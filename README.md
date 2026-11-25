# Doomscroller

An Android app that counts the Instagram Reels you scroll through, shows you what that costs, and helps you scroll less. Everything runs and stays on the phone.

## What it does

- **Counts reels.** An accessibility service watches Instagram and logs each reel you see: how long you watched it, its author, and whether you liked, commented on or shared it. It also tracks time on the Home feed and Explore.
- **⭐️ Limits** (each can be turned off in Settings):
- **8-minute limit:** after 8 straight minutes of scrolling, with a warning at 7, it sends you to the home screen.
- **Bedtime kick:** between midnight and 5 AM, a scroll session that passes a random 5–7 minutes ends. You go to the home screen, see two seconds of black, then the Clock app opens. Each later kick that night comes twice as fast, down to a 2-minute minimum.

- **Floating bubble.** A draggable counter sits over Instagram. A ring around it fills as you use up today's budget, and it gets louder every 10 reels. Tap it for details.
- **Dashboard.** Today's reels and time, trends over a selectable range, time of day, watch time, engagement rates, sessions, top creators, and a "damage" summary.
- **The "do less" game** (Progress tab):
  - A 7-day calibration week measures your baseline.
  - An adaptive daily budget then starts about 10% under that baseline and moves one notch each Monday. It eases off after a bad week instead of getting stricter. You can set a fixed budget instead.
  - Three daily wins: stay under budget, a clean night (no reels after bedtime), and no binges.
  - "Minutes reclaimed" against your baseline only ever goes up.
  - Streaks follow "never miss twice": one miss pauses a streak and two in a row reset it.
  - Badges and personal records.



A new reel is detected in one of two ways. The service prefers item positions from the Reels pager's scroll events. If those aren't available, it falls back to a signature built from the reel's author and caption, which has to change and stay changed. Leaving Reels for less than 30 seconds, for example to check a profile or DMs, resumes the same session and reel.

Detection depends on Instagram's UI, so an Instagram update can break it. Settings → Debug → **Copy debug info** dumps what the scanner currently sees.

## Building

Requirements: JDK 17 and the Android SDK (compileSdk 37, minSdk 28).

```sh
./gradlew assembleDebug      # build
./gradlew installDebug       # install on a connected device
./gradlew test               # unit tests (ProgressEngine)
```

You can also open the project in Android Studio and run the `app` configuration.

## Setup on the phone

1. Install and open Doomscroller.
2. Tap **Open accessibility settings** and turn on **Doomscroller reel counter**.
3. Open Instagram and scroll Reels. The bubble appears and counting starts.

To try the limits without waiting, use the **Test it** buttons in Settings.

## Privacy

The app has no internet permission. All data lives in a local SQLite database and SharedPreferences on the device.

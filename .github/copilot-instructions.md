# Copilot instructions for audioIF-extended

An Android app for playing interactive fiction (Z-machine games such as Zork) by voice. It's a fork of drossimarinho/audioIF, built on the ZMPP interpreter. `README.md` describes every feature added in this fork. Read it before making changes, and update it with each change.

## Repository layout
- `audioif/`: the Android app module, package `org.redrossistudios.audioif`.
  - `controllers/`: activities.
    - `MenuActivity`: main menu and version label.
    - `MainActivity`: audio mode, with inline `SpeechRecognizer` and TTS.
    - `TextModeActivity`, `DownloadGamesActivity`, `VoiceSettingsActivity`.
  - `helpers/`, command handling:
    - `CommandSession` is the shared input path for audio and text mode: room memory, "go to X", "go back", help and hints.
    - `CommandNormalizer` maps natural language to game commands.
    - `CommandSuggester` offers "Did you mean…" choices.
    - `SpeechMatcher` picks the best recognizer alternative and fixes misheard game words (`CommandSession.handleSpeech`).
  - `helpers/`, game:
    - `ZGame` wraps ZMPP.
    - `AutoSave` resumes the game when you return to the app.
    - `GameLibrary`, `InfocomCatalog` and `GameDownloader` manage bundled and downloaded games.
  - `helpers/`, walkthrough hints:
    - `WalkthroughParser` reads the walkthrough files.
    - `WalkthroughIndex` replays the walkthrough, explores the map and caches the result.
    - `GameStateReader` reads game state, and `HeadlessGame` runs a hidden copy of the game.
    - `HintCatalog`, `HintAdvisor` and `HintLoader` choose and speak the hints.
  - `src/main/assets/games/`: bundled Zork I–III.
  - `src/main/assets/walkthroughs/`: git-ignored. Fill it with `tools/fetch-walkthroughs.ps1`. Walkthroughs are personal-use only, so never commit them.
- `zmpp/`: upstream ZMPP 1.5. Keep app changes in `zmpp/src/main/java/org/zmpp/vm/AudioIfExtensions.java`, and record any upstream edit in `zmpp/UPSTREAM.md`.
- `audioif/src/test/`: JVM unit tests plus `harness/PlayHarness.java`, a headless play-test harness.

## Environment (Windows dev machine)
- JDK 17+ (21 in use). Android SDK in `%LOCALAPPDATA%\Android\Sdk`. Set `$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"` in each PowerShell call.
- Build: `.\gradlew.bat :audioif:assembleDebug --console=plain -q`. APK: `audioif\build\outputs\apk\debug\audioif-debug.apk`.
- Install: `& "$env:ANDROID_HOME\platform-tools\adb.exe" install -r <apk>`. Run `adb devices` first; the phone is often unplugged.

## Tests
- Fast tests, run after most changes:
  `.\gradlew.bat :audioif:testDebugUnitTest --console=plain -q --tests "*WalkthroughParserTest" --tests "*InfocomCatalogTest" --tests "*HintAdvisorTest" --tests "*CommandSuggesterTest" --tests "*CommandSessionTest" --tests "*CommandNormalizerTest" --tests "*AutoSaveTest" --tests "*SpeechMatcherTest" --tests "*PlayLogTest"`
- Run `--tests "*WalkthroughReplayTest"` (about 30 s; needs the fetched walkthroughs) after changing hints, replay or exploration code. Every seed must cover every step (Zork I 386, II 261, III 184). The report is in `audioif/build/play/walkthrough-*.txt`.
- Play harness: `--tests "*PlayHarness*" -Pplay=<commands.txt> -PplaySeed=1`. The output is `audioif/build/play/transcript.md`. A line starting with `~` simulates speech alternatives: `~ reed leaf lit | read leaflet`.
- Don't run the whole unit-test task without `--tests` filters. It's slow.
- When the hint cache format changes, bump `WalkthroughIndex.FORMAT`, or old caches (the app's `files/hints` folder and `audioif/build/play/hints`) will be reused.

## Workflow
- The default branch is `main`. Work on the session branch, then open a PR and merge it (`gh pr merge N --merge --repo dogbowl2/audioIF-extended`).
- Add the commit trailer `Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>`.
- After merging, build and install on the phone if it's connected.
- Update `README.md` (and `versionName` in `audioif/build.gradle` for releases) with every change. Debug builds show "2.0-debug (<commit>)" at the bottom of the menu.

## Design notes and gotchas
- Rooms are identified by name plus `#` and the location object (global 0, read from the state vector), because Zork reuses room names such as "Clearing" and "Forest". Speak only the name (`HintAdvisor.roomName`).
- Zork's map isn't symmetric. Routes use only exits that the replay actually tried. Guessed reverse exits (`CommandSession.guessedExits`) are left out of the hint routes, and `CommandSession.route` uses them only when no route of confirmed exits exists.
- Zork's Loud Room echoes "word word ..." for every non-movement command, which players hear as a stuck loop. `CommandSession.runCommand` adds `ECHO_NOTE` (say "echo" or leave).
- Hint skips are saved next to the auto-save (`AutoSave.saveHintSkips`, `CommandSession.hintSkips`/`restoreHintSkips`); the session holds them until the `HintAdvisor` finishes loading.
- `SpeechMatcher.rank` orders alternatives by fewest unknown words, then most words for visible things, then fewest changes, then fewest app-only words (such as "road", a landmark word Zork doesn't know). `choose` returns the first. When the game rejects the chosen one as not understood, `CommandSession.handleSpeech` retries up to 2 more with no unknown words (parser errors don't use a turn).
- Zork's dictionary compares only 6 letters, so `game.contains("livingroom")` is true. `CommandSession.isGluedWords` treats such run-together words as unknown unless the game printed them; `unglue` maps them back to room names for "go to".
- `WalkthroughIndex.fromRun` drops global fields that only rise (the score), so goals don't depend on the player's score.
- `HintAdvisor.exitNeeds` records walkthrough steps done in a room before its exit was first used (tie rope before Dome Room "d"); routes avoid those exits until the step's goal is met, or add "first try: …".
- "You'll have to X first" is remembered (`CommandSession.neededFirst`); a following rejected command naming the same thing runs X.
- Audio mode speaks `Narration.forSpeech(text)`, not the raw text: no "> command" echoes, no room title the description repeats. It also enables `CommandSession.setListThings`, which adds "Things here: …" after moves and looks (the play harness enables it too).
- ZMPP's random generator isn't saved in snapshots, so exploring or retrying changes later random events. The replay retries failed steps from earlier snapshots.
- "help" describes the current situation (`CommandSession.situation`: room, visible things, exits, things to try); "hint" gives walkthrough hints; "how do I play" reads the guide. Fetch walkthroughs before building an APK for the phone, or hints are missing.
- Some walkthrough goals become impossible (Zork I's broken egg is a different object). `HintAdvisor.skipHintedIfStuck` skips a goal when the hinted command gets an "already…" reply but the state doesn't match.
- The app's own words (help, hint, undo) must not be `SpeechMatcher` candidates, or game verbs such as "hang" get corrected to them.
- Zork's "swing the sword" only says "Whoosh!"; `CommandSession` turns it into an attack only while the object tree (`thingsHere`) holds a creature, so a dead troll isn't attacked again.
- Speech: on ERROR_CLIENT, busy or disconnected errors the recognizer is recreated. Errors before the player starts speaking restart listening quietly (no "sorry"); after 60 s of those, listening pauses. Otherwise listening pauses after 3 misses in a row, or while the app is in the background.
- `CommandSession.handle` answers "repeat" and "say the description" itself (from `lastText` and `roomDescription`) before `handleCommand` runs. Add new app-only phrases as patterns over canonical text (`CommandNormalizer.canonicalize` expands contractions and turns "that" into "the").
- `HintAdvisor.goalRoom` points "get X" hints at the room X is in now, read from the object-parent field in the state vector.

## Current state and open ideas (October 2026)
- Version 2.0 is merged to `main`. The build with the version label hasn't been installed on the phone yet.
- Audio mode writes play logs (`PlayLog`: what was said, recognizer alternatives with confidence, what was used and sent, speech errors). Get them with `adb pull /sdcard/Android/data/org.redrossistudios.audioif/files/logs`, or the user can tap "Share last play log" on the menu. Ask for one when debugging speech problems.
- The original cause of the repeated speech ERROR_CLIENT on the phone is unknown (the logs had rotated). Watch logcat (`adb logcat -s SpeechRecognizer TTS`) if it recurs.
- Possible next steps:
  - Playtest hints through Zork II and III on the phone.
  - Grow `CommandNormalizer` from new play-harness transcripts.
  - ZMPP 1.5 (2011) is the latest Java version. `zmpp/UPSTREAM.md` explains why the Scala rewrite zmpp2 isn't a drop-in update.

# audioIF-extended
An extended fork of [drossimarinho/audioIF](https://github.com/drossimarinho/audioIF).

audio IF - Audio Interactive Fiction - Play story games in a dynamic listen-talk gameplay - Android

Google Play link: https://play.google.com/store/apps/details?id=org.redrossistudios.audioif

Interactive fiction, Text adveture games, Story games, Interactive fiction interpreter, Z-Machine Interpreter, Z-Code games.

Play your favorite interactive fiction games in a listen-talk way.
At each step the text adventure is read for you, and then waits for your voice command to play the game.

2 modes:

-Text mode: regular interactive fiction gameplay with reading and writing<br />
-Audio mode: dynamic listen-talk gameplay

I tried to make the app easy to use for blind people. 
The "start audio mode" button is really big in the middle of the screen, so after you open the app, if you press somewhere in the middle of your screen the audio mode should start.
On the audio mode, the app scans for the supported game files on your device, and speaks out loud the name of each game file with a number identifier. After the enumeration of the games, there will be a beep, then you should say the game number, it will load that game.
When it's time for the player to speak, the app makes a beep sound. You can interrupt the story speech by clicking anywhere in the middle of screen, click anywhere on the screen also to start the speech recognition in case it is stopped.
Speech recognition runs inline, without a popup: the app starts listening as soon as it finishes reading the story text. If your speech isn't understood, the app says "Sorry, I didn't catch that. Please try again." and listens again. After three misses in a row it stops listening until you tap the story, and it stops listening while the app is in the background. Spoken directions and common verbs are converted to short commands: "north west" or "go north west" becomes NW, "up" becomes U, "examine lamp" or "check lamp" becomes "x lamp", and so on. A conversion is only used if the game recognizes the short form; games without "x" (such as Zork I) get "examine" instead. If the game doesn't understand a command (for example "You can't see any such thing." or "That's not a verb I recognise."), the app offers up to three similar commands from the game's vocabulary, for example "Did you mean one, take the lamp, two, take the lake, three, something else?" Say the number to run that command, say "something else" to be asked again, or just say a new command. Long-press the screen to show the keyboard and type a command instead; this pauses voice input.

The app asks for microphone permission the first time audio mode starts. Voice commands don't work without it.

If there is any problem or recommendation please let me know.

Text to speech configuration: Tap "Reading voice" on the menu to choose any installed voice for your language (tap a voice to hear it) and set the speed and pitch. Audio mode uses these settings. "Reset to default" goes back to your phone's text-to-speech defaults (Android Settings → Accessibility → Text-to-speech output).

Included and downloadable games:
Zork I, Zork II and Zork III come with the app, so you can start playing right away. They appear in the game list in both audio and text mode.
Tap "Download Infocom games" on the menu to download more games: the other text-only Infocom classics (Planetfall, Enchanter, Trinity, A Mind Forever Voyaging and more) and Colossal Cave Adventure. Tap a game to download it; it then appears in the game list.

How to easily find games:
If you click on the top button of the app "Browse supported games", it will bring you to the website https://ifarchive.org/indexes/if-archiveXgamesXzcode.html where the game files are directly available for download. Once the game file is downloaded, you just need to go back to the app and start audio or text mode, and the file scanner is going to find your downloaded games.

Game lists:
https://ifarchive.org/indexes/if-archiveXgamesXzcode.html

https://ifdb.tads.org/search?sortby=rcu&newSortBy.x=0&newSortBy.y=0&searchfor=-format%3AZ-Machine


How to play quick guide:
http://www.microheaven.com/ifguide/step3.html


Supported formats: Z-Machine games<br />
-.z[1-8] <br />
-.zblorb<br />
-.zlb


The core of this application uses the ZMPP interpreter project: https://sourceforge.net/projects/zmpp/

## Changes in this fork
- Audio mode uses an inline `SpeechRecognizer` instead of the Google speech popup. Listening starts when text-to-speech finishes (via `UtteranceProgressListener`), and recognition errors trigger a spoken retry prompt.
- Spoken commands are normalized by `CommandNormalizer` (`audioif/src/main/java/org/redrossistudios/audioif/helpers/CommandNormalizer.java`). It lowercases the input, strips punctuation, expands contractions ("what's" → "what is"), and drops polite or filler words at either end ("please", "can you", "I would like to", "now", "again"). It then simplifies words: "my/this/that" → "the", "everything" → "all", "upstairs" → "up". Directions are mapped, including motion phrases such as "walk to the north" or "head back east" → `E`. Verbs are mapped as well: examine / look at / check / inspect → `x` (or `examine` if the game has no `x`), what's in / look inside → `look in`, pick up → `take`, put down / get rid of → `drop`, switch → `turn`, plus inventory, look around, score, save and restore. Newer mappings come from Zork II playtests: "try to…", "I think I'll…" and gerunds ("going north") are accepted; "take a look at", "peek into", "glance under" and "what is on/under/behind" become `x`/`look in`/`look under`/`look behind`; "explore", "is there…" and "examine the room" become `look`; "walk north across the field" and "take the southwest path" become the direction; "stick/slide/insert the X in Y" become `put`; "pull the mat out from under the door" becomes `pull the mat`. Whole-input phrases such as "get out of here" (→ `exit`/`out`), "go inside" (→ `in`) and "climb up" (→ `U`, because bare `climb up` makes Zork II print a garbled object name) are added with `putExact`. Phrases match whole words at the start of the input, and the longest match wins. "Terminal" phrases such as "what am I carrying" ignore the rest of the sentence. To add a mapping, call `put("spoken phrase", "command")` (or `putTerminal`) in its static block. Separate fallbacks with `|` (for example `"x|examine"`); the first one whose verb the game knows is used.
- Natural-language session (`CommandSession`, `audioif/src/main/java/org/redrossistudios/audioif/helpers/CommandSession.java`). Audio mode, text mode and the play harness all send input through it. `ZGame` connects it to ZMPP. It adds features the old games lack:
  - Compound commands: "open the mailbox and take the leaflet", "go north, then east". Processing stops at the first rejected step.
  - Repeats: "go south twice", "wait 3 times" (up to 5).
  - Room memory: rooms come from the status line (or the room heading). Any command that changes rooms ("go inside", "climb the stairs", "exit") is recorded as a way between them. "Go back" retraces the last move, "keep going" repeats it, and "go to the kitchen" walks the shortest known route, stopping if something unexpected happens. "Leave" or "get out of here" uses the game's exit or out.
  - Landmarks: if the game rejects "follow the trail" or "go to the stairs", the room description is searched for a sentence that mentions the thing and gives a direction ("A narrow trail leads north" → north; "above" → up). If the current room doesn't mention it, the app walks to the nearest known room whose description did. In brief mode the app fetches the full description first.
  - Questions about exits ("which way can I go?") look around and list the ways you've already used from here.
  - Chatter after a command ("go north, that looks interesting") is ignored.
  - Pronouns: if the game doesn't understand "it"/"them", the command is retried with the last noun you used, e.g. "(I took it to mean the leaflet.)".
  - Situational help. "Help", "what can I do" or "what are my options" describe where you are: the room, the things you can see, the ways out, and two or three commands to try, preferring ones you haven't tried ("You're at Behind House. You can see the window. You can go north, east, south, southwest, west or northwest. You could try: open the window or go north."). After a resume the last reply isn't a room description, so help first does a silent "look". Visible things come from the game's object tree (`ZGame.thingsHere`, global 0 is the location), skipping the player and what they carry, and only once the game has mentioned them; scenery such as Zork's window and rug is taken from the room description. Exits come from the walkthrough map when there is one, otherwise from ways you've used and directions the description mentions. "How do I play", "commands" or "tutorial" read a short guide for players new to text adventures. A new game starts with a spoken tip about how to talk to it, and after two rejected commands in a row the app suggests saying "help".
  - Hint and undo for games that don't have them. "Stop", "pause", "be quiet" or "goodbye" pause listening until you tap the story. "Run away", "flee" or "escape" go back the way you came (Zork wants a direction, and a wasted turn in a fight can kill you). "Examine the room" (or the room's name, when the game doesn't know the word) does a look. "Swing the sword" (or thrust, stab, wave the sword) with a creature present becomes "attack the troll with the sword", and the app tells you so, because Zork only answers "Whoosh!". "Go to" a room you haven't visited works if the last hint named it, using the walkthrough's route. Undo uses up to 10 snapshots captured by `AudioIfExtensions.captureInputState` / `restoreInputState` (see below), and says what was taken back ("Took back: open the door. You are in Kitchen."). Look, inventory and score don't use up an undo step.
  - Walkthrough hints for Zork I, II and III (see "Walkthrough hints" below). Saying "hint", "help me" or "I'm stuck" tells you what to do next, more precisely each time you ask: first where the next step is, then how to get there or which things to use, then the exact command ("At Up a Tree, try: get egg. From here, go north, north, then up."). The hint starts vague again once you've done that step. If you say the hinted command and the game answers that it's already done ("You already have that!") but your game state still doesn't match the walkthrough's (Zork's egg, once broken, is a different object), that step is skipped and the next one is hinted, so hints never loop on an impossible step.
  - Explanations: questions ("why is…") get a note that the game can't answer questions. "Use X on Y" offers real verbs, chosen by the objects: keys → unlock, open, put; holes and slots → put; lamps → light. "Use X" alone offers what X is usually for: turn on the lamp, tie the rope to something you can see, attack a creature with the sword, unlock a door with the key. When the game asks a question such as "What do you want to tie the rope to?", the note follows it: "Just say what, like railing, or say the whole command, like tie the rope to the railing", with an example taken from the things you can see. Zork accepts the bare answer. Other rejections get numbered suggestions. Pick one by saying or typing its number. Suggestions use words from recent replies, and implausible ones (a non-verb replacing the verb, a direction swapped for a different direction) are dropped.
  - Text mode shows what was actually sent in brackets, e.g. `> check the mailbox  [examine the mailbox]`, and shows app notes in italics, so it is a quick way to test voice-command handling.
  - Movement fixes from commute play logs:
    - Recognizer padding before a direction is dropped ("the south", "no southwest", "just go north").
    - "Go back to the kitchen" works ("back" is removed before the game sees it). "Go kitchen", which Zork rejects with "You should supply a direction!", walks to a known room.
    - "The other side of the house" or "the back of the house" means Behind House.
    - "Go to the living room" works for a room you haven't visited if the walkthrough map puts it at most 4 moves away.
    - Routes prefer exits you've actually used over guessed reverse exits, which may not exist in Zork's forest.
    - Landmark guessing ignores "the north side of a white house", since that says where you are, not which way to go.
    - "Climb in" or "get in" alone sends "in". "Climb in the window" becomes "enter the window", because Zork answers "climb in" with "You have a theory on how to board…". "Go back into the house" from outside first tries Zork's "in".
    - "Go forward" or "keep going" after a move up, down, in or out asks you to say the direction again, so you don't drop down a chasm by accident.
  - Long sentences: when the game rejects a rambling sentence that doesn't start with a command, the app runs the command at its end and says so: "how about ghosts, I said go south" → "(I took that as: go south.)". It stops at a negation, so "go back into the house, don't leave the house" runs "go back into the house". This is skipped when you chain commands with "and" or "then", so an error still stops the rest.
  - The Loud Room: Zork's Loud Room only echoes your last word ("lamp lamp ..."), which sounds like the game is stuck in a loop. The app now explains it: "This room is so loud that the game only echoes your last word. Say echo to quiet it, or leave by saying a direction, like go west."
  - Darkness: in a pitch-black room the app tells you how to light your lamp, naming the light you carry ("You're carrying the lantern, so say turn on the lantern."), or warns you to go back. Carried things come from `ZGame.carried`, which works in the dark.
  - Skipped hints are saved with the auto-save (`AutoSave` `.skips` file), so a hint that was skipped because it couldn't be done doesn't come back after you restart the app.
  - Fewer repeated commands (from a play log where the same command had to be said several times):
    - Speech retries: if the game rejects the chosen speech alternative as not understood, the app quietly tries up to two more alternatives with no unknown words (`SpeechMatcher.rank`), undoing nothing because parser errors don't use a turn. So "text the rope to the railing" falls through to "attach the rope to the railing".
    - Quiet recognizer restarts: a recognizer error before you started speaking (client, no match, timeout, busy, disconnected) restarts listening silently instead of saying "sorry". Listening pauses after 60 seconds of such errors.
    - "Hint hint" (and "hint hit", "hint in") counts as hint.
    - "Crawl down"/"crawl up" are directions; "crawl" alone means climb. Speech ranking now prefers fewer changed words over fewer app-only words, so "crawl down" beats "kill down".
    - Words run together: Zork only compares the first six letters, so it seemed to know "livingroom". A long word made of two known words is now treated as unknown unless the game has printed it, and "go to the livingroom" splits it back into a room name.
    - "You're not at the house" now triggers the app's walk to the place, like "You can't go that way".
    - The Loud Room note lists the room's exits ("like go east, west or up.").
    - Walkthrough goals no longer count the score: global fields that only ever rise (3 or more times) are dropped, so a goal like "get the egg" is done even when your score differs from the replay's (`WalkthroughIndex.FORMAT` 8).
    - Hint routes avoid exits that need an earlier step first (the Dome Room's "down" needs the rope tied). If there's no other way, the hint says "At Dome Room, first try: tie rope to railing."
    - When Zork says "You'll have to open the bottle first" and your next command is rejected but names the same thing, the app does what Zork asked: "(I took that as: open the bottle.)"
- Speech matching for game words (`SpeechMatcher`, `CommandSession.handleSpeech`). Phone speech recognizers are tuned for everyday English, so game words get misheard ("leaf lit", "mail box", "go nor", "open the greeting"). The recognizer now returns up to 5 alternatives, and on Android 13+ it is biased toward directions, verbs and things seen in the game (`EXTRA_BIASING_STRINGS`, from `CommandSession.speechHints`). The app picks the alternative with the fewest words the game doesn't know, after fixing words: split words are joined ("mail box" → mailbox, "leaf lit" → leaflet), common mishearings are mapped ("nor" → north, "yeast" → east, "reed" → read), and other unknown words are replaced by the single game word that sounds most alike (a Metaphone-style key plus edit distance), chosen only from words the game has shown you, so nothing is spoiled. A lone "hint" often comes back as "tent", "tint", "Kent" or "can't" with no "hint" among the alternatives; a one-word utterance the game doesn't know is taken as "hint" if any alternative is one of these. Small function words are never changed, and the app's own words (help, hint, undo) are never used as replacements, so "hang the rope" isn't turned into "hint the rope". When alternatives tie, the app prefers words for things you can see here, then words the game itself knows over words only the app knows, so with a rope in view "take the road" | "take the rope" becomes "take the rope". Conversational words ("said", "told", "why", "okay") are never changed. Audio mode shows what it heard ("> read the leaflet") above the reply, and a rejected command adds "I heard: …" so a mishearing is easy to spot. In the play harness, a line starting with `~` simulates speech, with alternatives separated by `|` (`~ reed leaf lit | read leaflet`).
- Play log for debugging speech (`PlayLog`). Each audio-mode session writes a timestamped text file: everything the app says (`SAY`), every recognizer alternative with its confidence score (`HEARD 1. open the mail box (0.82) | 2. …`), the text the app used after speech matching (`USED`), the commands sent to the game (`SENT`, marked `[rejected]` if the game didn't understand), typed commands (`TYPED`), and speech errors, pauses and backgrounding (`EVENT`). Logs are kept in `Android/data/org.redrossistudios.audioif/files/logs/` (the newest 20), readable over USB or with `adb pull /sdcard/Android/data/org.redrossistudios.audioif/files/logs`. "Share last play log" on the menu sends the newest log as text to email, chat or notes.
- Rejected commands get spoken suggestions from `CommandSuggester` (`audioif/src/main/java/org/redrossistudios/audioif/helpers/CommandSuggester.java`). After each command, the game's response is checked against a list of rejection phrases (add more with `CommandSuggester.addRejectionPhrase`). Sentence-level errors such as "There was no verb in that sentence!" get no word suggestions. If it matches, the word the game names (`the word "X"`) or the unknown words in the command (or, if every word is known, the object words) are replaced with dictionary words within a small edit distance (typos and swapped letters count as 1). Results are ranked by distance, then shared prefix, and the top three are read out as numbered options. The dictionary is read through `AudioIfExtensions.getDictionaryWords`. Z-Machine dictionaries store only the first 6 (v1-3) or 9 (v4+) letters of each word, so shortened entries are expanded back to full words seen in the game's output; entries that can't be expanded are skipped. Object words are only suggested if the game has mentioned them, so suggestions name real things. Typos of words up to 4 letters allow edit distance 1; longer words allow 2.
- Infocom games. Versions come from the [Obsessively Complete Infocom Catalog](https://eblong.com/infocom/), using the "Text Adventure Masterpieces of Infocom" (1996) release of each game.
  - Zork I, II and III are bundled in `audioif/src/main/assets/games/`. Microsoft released them under the MIT License in November 2025; the license is in `LICENSE-zork.txt` next to them. On start, `GameLibrary` copies them into the app's private `games` folder, which `FileScanner` lists before the device-wide scan.
  - The other 27 Masterpieces games are still copyrighted by Microsoft, so they're not stored in this repository. The "Download Infocom games" screen (`DownloadGamesActivity`) downloads them from eblong.com on request, after a one-time notice. `GameDownloader` checks each file against the catalog MD5 before saving it.
  - Colossal Cave Adventure is downloadable too: Graham Nelson's Inform port (`Advent.z5`, Release 9 / 060321) from the [IF Archive](https://ifarchive.org/if-archive/games/zcode/Advent.z5). It's the standard 350-point version, and its parser accepts full sentences, which suits voice input.
  - Not included: the graphical `.z6` games (Arthur, Journey, Zork Zero), and Hitchhiker's Guide and Shogun, which aren't on Masterpieces. Where Masterpieces had separate Mac and PC files, the PC file is used; Sherlock is the PC no-sound build.
  - The game list is in `InfocomCatalog`; add a `Game` entry (title, file name, MD5) to offer another download.
  - Saved games now go next to the game file, with its extension replaced by `.sav`. The old code cut the path at the first dot, which broke for paths containing dots such as the app's private folder.
- Automatic resume. After every command, and whenever you leave the game screen, `AutoSave` stores the game state (Quetzal format, via `ZGame.saveState`) and the last text shown in the app's private `files/autosave` folder, one save per story file. Reopening the same game restores it, says "Welcome back…", and reads the recap. To start over, say or type `restart`. A save from a different release of a story file is ignored, and the save is deleted when a game ends. This is separate from the in-game `save`/`restore` commands, which still write a `.sav` file next to the game.
- Reading voice screen (`VoiceSettingsActivity`): lists the TTS engine's installed voices for the device language, with speed and pitch sliders (0.5x–2.0x). `VoiceSettings` saves the choice and applies it in audio mode after `setLanguage`. A saved voice that's later uninstalled is ignored.
- The game list scan (`FileScanner`) runs on a background thread with a "Searching for games..." spinner. It used to walk all of shared storage on the UI thread, which froze audio mode (an ANR) on phones with many files. It now skips hidden folders and `Android/`, and stops 8 folders deep.
- The menu no longer opens the "All files access" settings page on every launch. Bundled and downloaded games don't need that permission, so the app asks once with an explanation and an "Open settings" button.
- "Classic adventure" visual theme. A dark ink/parchment palette with amber accents (`res/values/colors.xml`, `styles.xml`), serif story text, rounded buttons with Material icons, and styled dialogs. The menu was redesigned with a title header and a large primary audio-mode button. Audio mode shows a status pill ("Reading… tap to skip", "Listening… say a command", "Paused — tap the story to listen"). In text mode, command echoes are shown in amber. The game picker is a compact titled dialog that hides file extensions.
- The app version is shown at the bottom of the menu. It's set by `versionName` in `audioif/build.gradle` (now 2.0). Debug builds add the git commit they were built from, for example "2.0-debug (956c743)", so you can tell which build is installed.
- ZMPP changes are isolated for easy upstream merges. The `zmpp` module is upstream ZMPP 1.5 (the latest SourceForge commit, from 2011). The app's additions to it live in one new file, `zmpp/src/main/java/org/zmpp/vm/AudioIfExtensions.java`, and the only edit to an upstream file is one marked visibility change in `MachineImpl`. `zmpp/UPSTREAM.md` records the base commit, the local changes, and how to pull in upstream fixes.
- Build toolchain upgraded to Android Gradle Plugin (AGP) 9.4.1 (see below).
- Headless play harness for testing command handling (`audioif/src/test/java/.../harness/PlayHarness.java`). It plays a game on the desktop JVM through the same input path as text mode (`CommandSession` → `CommandNormalizer`/`CommandSuggester` → `ZGame` → ZMPP).
  - Write one command per line to a text file, then run `gradlew :audioif:testDebugUnitTest --tests "*PlayHarness*" -Pplay=C:\path\commands.txt`. Optional flags: `-PplayGame=<story file>` (default: bundled Zork I) and `-PplaySeed=<n>`.
  - Each run replays the file from the start with a seeded random generator, so results are repeatable.
  - Output goes to `audioif/build/play/`: `transcript.md` (with `[sent: …]` and **REJECTED** markers), `transcript.json`, `latest.txt` and `summary.txt` (number of inputs and rejected inputs). App notes appear as "(app)" lines.
  - A replay of a 148-command Zork I session played by an AI agent went from 78 rejected inputs to 15 after these changes. A 180-command Zork II session went from 35 rejected inputs to 13.
  - Without `-Pplay` the test is skipped.
  - If the walkthrough for the game has been fetched (see "Walkthrough hints"), the harness loads hints too, so "hint" lines show the hints a player would hear. The replayed index is cached in `audioif/build/play/hints`.
- `WalkthroughReplayTest` replays each fetched walkthrough with several random seeds and writes `audioif/build/play/walkthrough-<game>.txt` (steps covered, retries and goals per seed). It checks that every step is replayed and that each game has enough goals. It's skipped for games whose walkthrough hasn't been fetched. The other unit tests use made-up walkthroughs only.

### Android Gradle Plugin upgrade
The original project used AGP 3.2.0, which can't parse the manifest `<queries>` element. Android 11+ needs that element to let the app find the speech recognition service (`android.speech.RecognitionService`), so the build was upgraded to the latest stable AGP:

| | Before | After |
|---|---|---|
| Android Gradle Plugin | 3.2.0 | 9.4.1 |
| Gradle wrapper | 4.6 | 9.6.0 |
| JDK | 8 | 17+ |
| compileSdk | 33 | 36 |
| targetSdk | 33 | 36 (see below) |
| minSdk | 24 | 24 |
| Build tools | 28.0.2 (pinned) | 36.0.0 (AGP default) |
| Repositories | `jcenter()`, `google()` | `google()`, `mavenCentral()` |

Changes required by the upgrade:
- **Support Library → AndroidX** (AGP 9 no longer supports the old Support Library or Jetifier):
  - `com.android.support:appcompat-v7:27.1.1` → `androidx.appcompat:appcompat:1.8.0`
  - `com.android.support.constraint:constraint-layout:1.0.2` → `androidx.constraintlayout:constraintlayout:2.2.2`
  - `espresso-core:2.2.2` → `androidx.test.espresso:espresso-core:3.7.0`; `junit:4.12` → `4.13.2`
  - Java imports (`android.support.v4`/`v7` → `androidx.core`/`androidx.appcompat`) and layout XML tags (`android.support.constraint.ConstraintLayout` → `androidx.constraintlayout.widget.ConstraintLayout`) were updated to match.
  - `android.useAndroidX=true` is set in the new `gradle.properties`.
- **Dependency configurations:** `compile`/`testCompile`/`androidTestCompile` → `implementation`/`testImplementation`/`androidTestImplementation`.
- **Namespaces:** the `package` attribute was removed from both `AndroidManifest.xml` files and replaced by `namespace` in each module's `build.gradle` (`org.redrossistudios.audioif`, `org.zmpp`).
- **DSL updates:** `compileSdkVersion`/`minSdkVersion`/`targetSdkVersion` → `compileSdk`/`minSdk`/`targetSdk`; `buildToolsVersion` and the empty `productFlavors` block were removed; `targetSdk`, `versionCode` and `versionName` were removed from the `zmpp` library module because AGP no longer accepts them for libraries.
- **ProGuard:** `proguard-android.txt` → `proguard-android-optimize.txt` (AGP 9 dropped the old default file). Minification is still disabled.
- **targetSdk 36:** Google Play Protect on current Android versions blocks sideloaded apps that target an old API level ("built for an older version of Android"), so `targetSdk` was raised from 33 to 36. Apps targeting 35+ are drawn edge-to-edge, and the keyboard no longer resizes the window automatically. `helpers/EdgeToEdge.applySystemBarPadding()` is called after `setContentView` in every activity. It pads the content by the status/navigation bar, display cutout and keyboard insets so that nothing ends up under the system bars or the keyboard.

## Building
Requirements: JDK 17 or newer, and an Android SDK with platform 36 and build-tools 36.0.0. Set `ANDROID_HOME` or create a `local.properties` file containing `sdk.dir`.

```
./gradlew assembleDebug
```

The APK is written to `audioif/build/outputs/apk/debug/`.

### Testing
- Unit tests run on the desktop JVM: `./gradlew :audioif:testDebugUnitTest --tests "*CommandNormalizerTest"` (or any other `*Test` class in `audioif/src/test`). Pass `--tests` filters: running the whole task also starts the play harness and walkthrough replay setup, which is slow.
- `WalkthroughReplayTest` and the play harness are described under "Changes in this fork" above.
- `.github/copilot-instructions.md` lists the exact commands, workflow and project notes for contributors and coding agents.

### Walkthrough hints

The hints come from the IF Archive's step-by-step Zork walkthroughs (`solutions/Sols2.zip`). They aren't stored in this repository. For a personal build, fetch them before building:

```
powershell -File tools/fetch-walkthroughs.ps1
```

This copies `Zork I.txt`, `Zork II.txt` and `Zork III.txt` into `audioif/src/main/assets/walkthroughs/`, which is git-ignored. Without them the app builds and runs normally, but "hint" only gives general advice. Fetch them before building an APK for the phone.

How it works (`helpers/WalkthroughParser`, `WalkthroughIndex`, `HintCatalog`, `HintAdvisor`, `HintLoader`):
- When a game starts, `HintLoader` replays its walkthrough in a hidden copy of the game on a background thread. `GameStateReader` records, for each step, which object parents, attributes and globals it changed. Steps that fail (for example a random fight or a timed event) are retried from earlier snapshots, with extra waits if needed.
- During the replay, the first time the walkthrough reaches a room, the hidden game explores every room reachable from there (up to 400), trying each direction and then undoing it. This gives a map of real exits, including rooms the walkthrough never visits, such as the forests. Zork's map isn't symmetric (east from North of House leads behind it, but west from there enters the kitchen), so routes only use moves that the replay checked.
- Rooms are identified by name plus the game's location object (global 0), because names repeat: Zork I has two different rooms called "Clearing" and several called "Forest". Hints only speak the name.
- A goal is a step that changes lasting state without moving the player. The next hint is the first goal whose changes aren't already true in your game, so hints follow your progress even if you play in a different order.
- The first load takes a while (up to tens of seconds on a phone). Until then "hint" says hints are loading. The result is cached in the app's `files/hints` folder by game checksum, so later loads are instant.
- Only Z-machine story files (`.z1`–`.z8`) with a matching walkthrough get hints.

To pull in changes from the original project:

```
git remote add upstream https://github.com/drossimarinho/audioIF.git
git fetch upstream
git merge upstream/master
```

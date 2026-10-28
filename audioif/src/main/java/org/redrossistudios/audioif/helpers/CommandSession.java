package org.redrossistudios.audioif.helpers;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns what the player said or typed into game commands and app replies.
 * Shared by audio mode, text mode and the play harness so all behave the same.
 *
 * On top of {@link CommandNormalizer} and {@link CommandSuggester} it adds
 * things old parsers lack: several commands in one sentence ("open the mailbox
 * and take the leaflet"), "twice", "go back", "keep going", "go to the
 * kitchen" (walks a remembered route), "undo", "help", "hint", guessing what
 * "it" means, and friendlier replies when a command is rejected.
 */
public final class CommandSession {

    /** The running game. */
    public interface Game extends CommandNormalizer.Dictionary {
        /** Sends a command and returns the game's reply. */
        String send(String command);

        List<String> dictionaryWords();

        /** The current room from the status line, or null if unknown. */
        String currentRoom();

        /** Names of the objects in the current room (scenery included, hidden ones too), or empty if unknown. */
        default List<String> thingsHere() {
            return Collections.emptyList();
        }

        /** Names of what the player carries, or empty if unknown. */
        default List<String> carried() {
            return Collections.emptyList();
        }

        /** Puzzle-relevant game state (see {@link GameStateReader}), or null if unsupported. */
        default int[] stateVector() {
            return null;
        }

        /** Captures the state for undo, or returns null if unsupported. */
        Object snapshot();

        /** Restores a {@link #snapshot()}; returns false on failure. */
        boolean restore(Object snapshot);
    }

    /** One step of the reply: a command sent with the game's response, and/or a note from the app. */
    public static final class Exchange {
        /** The command sent to the game, or null for app-only notes. */
        public final String command;
        /** The game's reply, or null. */
        public final String response;
        /** A note from the app, or null. */
        public final String note;

        Exchange(String command, String response, String note) {
            this.command = command;
            this.response = response;
            this.note = note;
        }
    }

    public static final class Result {
        public final List<Exchange> exchanges = new ArrayList<>();
        /** True if the game rejected a command. */
        public boolean rejected;
        /** Suggestions offered for a numbered choice, empty if none. */
        public List<String> suggestions = Collections.emptyList();
        /** For speech, the words the app went with after matching them to the game; else null. */
        public String heard;
        /** True if the player asked the app to stop listening ("stop", "pause"). */
        public boolean pauseListening;

        public List<String> sentCommands() {
            List<String> sent = new ArrayList<>();
            for (Exchange exchange : exchanges) {
                if (exchange.command != null) {
                    sent.add(exchange.command);
                }
            }
            return sent;
        }

        /** Game responses and notes, in order, separated by blank lines. */
        public String text() {
            StringBuilder sb = new StringBuilder();
            for (Exchange exchange : exchanges) {
                for (String part : new String[]{exchange.response, exchange.note}) {
                    if (part != null && !part.trim().isEmpty()) {
                        if (sb.length() > 0) {
                            sb.append("\n\n");
                        }
                        sb.append(part.trim());
                    }
                }
            }
            return sb.toString();
        }

        void note(String note) {
            exchanges.add(new Exchange(null, null, note));
        }
    }

    public static final String ASK_AGAIN = "OK, what would you like to do?";

    /** Spoken once when a new game starts, for players who haven't met a text adventure before. */
    public static final String WELCOME_TIP = "Tip: tell the game what to do in a few words, "
            + "like open the mailbox, take the lamp, or go north. "
            + "Say help at any time to hear what you could do here.";

    static final String HELP_TEXT = "This is a text adventure. You play by telling the game what to do, "
            + "usually a verb and a thing, like open the mailbox. "
            + "To move, say a direction: north, south, east, west, up, down, in or out. "
            + "Say look to hear where you are, and examine something to look at it closely. "
            + "Other useful verbs are take, drop, open, close, read, turn on, put something in something, "
            + "and attack something with a weapon. Say inventory to hear what you're carrying. "
            + "You can chain commands: open the mailbox and take the leaflet. "
            + "Say go back to retrace a step, or go to a place you've visited, like go to the kitchen. "
            + "Say undo to take back a turn, and save or restore to keep your progress. "
            + "Say help at any time to hear what you could do here.";

    static final String HINT_TEXT = "This game has no built-in hints. Try examining everything "
            + "you find, reading anything with writing on it, opening containers, "
            + "and trying every exit. Say help to hear what you could do here.";

    static final String STUCK_NOTE = "Not sure what to say? Say help to hear what you could do here.";

    static final String QUESTION_NOTE = "The game can't answer questions. Try look, "
            + "examine something, or inventory.";

    static final String REPHRASE_NOTE = "Try a short command that starts with a verb, "
            + "like take the lamp or open the door.";

    private static final int MAX_UNDO = 10;
    private static final int MAX_REPEAT = 5;
    private static final int MAX_WALK = 25;

    static final String HINTS_LOADING = "Hints for this game are still loading. Ask again in a moment, "
            + "or say help to hear what you could do here.";

    // "help" describes the situation; "how do I play" explains commands; "hint" uses the walkthrough.
    private static final Set<String> SITUATION_PHRASES = canonicalSet("help", "what can i do",
            "what can i do here", "what are my options", "options", "ideas", "give me ideas",
            "what can i try", "what should i try", "suggestions", "suggest something");
    private static final Set<String> HELP_PHRASES = canonicalSet("commands", "more help", "help with commands",
            "what can i say", "how do i play", "how to play", "how does this work", "instructions",
            "list the commands", "what are the commands", "list commands", "command list", "verbs",
            "what verbs", "tutorial");
    private static final Set<String> HINT_PHRASES = canonicalSet("hint", "hints", "give me a hint",
            "i need a hint", "i am stuck", "clue", "give me a clue", "any hints", "what should i do",
            "what do i do", "what now", "now what", "what next", "what do i do next", "what should i do next",
            "i do not know what to do", "help me", "i need help");
    private static final Set<String> UNDO_PHRASES = canonicalSet("undo", "undo that", "undo the last move",
            "undo last move", "undo the last turn", "take that back", "take back", "oops undo");
    private static final Set<String> BACK_PHRASES = canonicalSet("go back", "back", "return", "go back the way i came",
            "retrace the steps", "go back where i came from", "go back to where i was",
            "head back", "walk back", "turn back", "turn around", "go back the way i came in");
    // "run away from the troll": Zork wants a direction, and a wasted turn can be fatal, so go back.
    private static final Pattern FLEE = Pattern.compile("^(?:run away|run off|flee|escape|retreat|get away|"
            + "run back|back away|run for it|get out of here|run)(?: from (?:the )?.+)?$");
    private static final Set<String> STOP_PHRASES = canonicalSet("stop", "stop listening", "pause",
            "pause listening", "be quiet", "quiet", "shut up", "stop talking", "hold on", "stop the game",
            "i am done", "that is enough", "goodbye", "good bye", "bye");
    public static final String PAUSED_NOTE = "Okay, I've stopped listening. Your game is saved. "
            + "Tap the story when you want to carry on.";
    // What phone recognizers make of a lone "hint" when it isn't among the alternatives.
    private static final Set<String> HINT_ALIKES = set("tent", "tint", "kent", "hent", "mint", "meant",
            "can't", "cant", "hence", "hints", "hind", "hinted", "lint", "hinge");
    private static final Pattern EXAMINE_PLACE = Pattern.compile(
            "^(?:examine|look at|inspect|search|check out|explore|describe|x) (?:the )?(.+)$");
    private static final Pattern WEAPON_SWING = Pattern.compile("^(?:swing|thrust|stab|slash|brandish|wave|"
            + "wield|lunge|jab|swipe|strike|use) (?:with )?(?:the |my )?((?:elvish |nasty )?(?:sword|knife|axe|"
            + "dagger|stiletto|blade))(?: (?:at|on|into|toward|towards) (?:the )?(.+))?$");
    private static final Set<String> CREATURES = set("troll", "thief", "robber", "cyclops", "bat", "ghost",
            "ghosts", "spirits", "grue", "dragon", "demon", "guardian", "wizard", "gnome", "monster", "giant",
            "vampire", "snake", "serpent", "wolf", "bear", "goblin", "orc", "skeleton", "zombie");
    private static final Set<String> CONTINUE_PHRASES = canonicalSet("continue", "keep going", "go on",
            "forward", "go forward", "keep walking", "carry on", "walk on", "keep moving", "move on",
            "onward", "go straight", "straight ahead", "go straight ahead", "go further", "go farther",
            "continue on", "keep on", "keep on going", "go ahead", "move forward", "walk forward",
            "further", "farther", "go on further", "keep heading", "keep heading that way",
            "keep going that way", "continue that way", "same way", "same direction",
            "keep going the same way", "go the same way");
    private static final Set<String> EXIT_QUESTIONS = canonicalSet("which way can i go", "what are the exits",
            "which exits are there", "what exits are there", "where can i go", "where can i go from here",
            "exits", "list the exits", "show the exits", "what directions can i go", "which directions",
            "which directions can i go", "which way", "which way now", "where to", "where does it go",
            "are there any exits", "what exits", "what are the ways out", "how do i get out",
            "how do i leave", "where are the exits", "what way can i go");
    private static final Set<String> CHATTER_STARTERS = set("i", "it", "that", "this", "because", "since",
            "cause", "which", "maybe", "hopefully", "probably", "you");
    // Zork's Loud Room answers every command with "bar bar ...".
    private static final Pattern ECHO = Pattern.compile("(?s)^\\s*([a-z]+) \\1 \\.\\.\\.\\s*$",
            Pattern.CASE_INSENSITIVE);
    static final String ECHO_NOTE = "This room is so loud that the game only echoes your last word. "
            + "Say echo to quiet it, or leave by saying a direction";
    private static final Pattern DARK = Pattern.compile("(?i)pitch black|too dark to see|it is dark|it's dark");
    static final String DARK_NOTE = "It's dark here, and moving around in the dark is dangerous. "
            + "If you have a lamp, say turn on the lamp. Otherwise, say go back.";
    // "the south", "no southwest", "just go north": recognizer padding before a direction.
    private static final Pattern PADDED_DIRECTION = Pattern.compile("^(?:the|no|just|so|okay|ok|hey|they|well|"
            + "now|and|to|um|uh|let us|lets|i will|i|we|you|joe|oh)(?: (?:go|head|walk))? (.+)$");
    // Words after which a clause stops: "go back into the house, don't leave the house".
    private static final Set<String> NEGATIONS = set("not", "never", "dont", "didnt", "do", "did");
    // Words in a long sentence that don't start the command the player meant.
    private static final Set<String> WEAK_STARTERS = set("i", "then", "also", "try", "let", "keep", "wait", "z",
            "help", "undo", "return", "continue", "say", "answer", "count", "describe", "check", "score",
            "save", "restore", "use", "touch", "listen", "smell", "explore");
    private static final Set<String> LEVEL_DIRECTIONS = set("N", "S", "E", "W", "NE", "NW", "SE", "SW");
    // "climb in", "climb in the window", "get into the boat": Zork wants "enter".
    private static final Pattern CLIMB_IN = Pattern.compile("^(?:climb|get|crawl|step|hop|squeeze|jump|slip|"
            + "sneak|go)(?: back)? (?:in|into|inside|through)(?: (?:the )?(.+))?$");
    private static final Pattern BEHIND_PLACE = Pattern.compile(
            "^(?:the )?(?:back|rear|other side|far side|backside|back side|behind)(?: side)? of (?:the )?(.+)$");
    private static final Set<String> HOUSE_WORDS = set("house", "building", "home", "cabin", "hut", "cottage");
    // How far "go to the living room" may walk, by the walkthrough's map, to a room not yet visited.
    private static final int MAX_UNSEEN_WALK = 4;
    private static final Set<String> NO_STATE_COMMANDS = set("look", "l", "i", "inventory", "score",
            "diagnose", "time", "verbose", "brief", "superbrief", "help", "hint");
    private static final Set<String> NOT_MOVES = set("restore", "restart", "load", "undo", "look", "l");
    private static final Set<String> LANDMARK_VERBS = set("go", "walk", "run", "head", "cross", "climb",
            "enter", "follow", "take", "wade", "swim", "jump", "crawl", "step", "move", "use", "travel",
            "ford", "approach", "proceed", "descend", "ascend", "squeeze");
    private static final Pattern LEAVE = Pattern.compile(
            "^(?:leave|exit|get out of|go out of|walk out of|step out of|head out of) (?:the )?(.+)$");
    private static final Set<String> QUESTION_WORDS = set("what", "where", "who", "why", "how",
            "when", "which", "is", "are", "can", "could", "do", "does", "should");
    private static final Set<String> PRONOUNS = set("it", "them", "him", "her");
    private static final Set<String> NOUN_STOPS = set("here", "there", "is", "are", "was", "and",
            "with", "in", "on", "of", "to", "from", "at", "by", "into", "it", "that", "which",
            "you", "a", "an", "the", "all", "but", "as", "for", "lies", "sits", "stands");
    private static final Set<String> VERB_STARTERS = set("take", "get", "grab", "pick", "drop",
            "open", "close", "shut", "go", "walk", "run", "head", "climb", "enter", "exit", "leave",
            "read", "examine", "x", "look", "check", "inspect", "put", "place", "turn", "switch", "light",
            "move", "push", "pull", "press", "kill", "attack", "hit", "fight", "give", "throw", "eat",
            "drink", "wait", "z", "inventory", "i", "unlock", "lock", "search", "wear", "remove",
            "tie", "untie", "dig", "fill", "pour", "wave", "ring", "inflate", "deflate", "listen",
            "smell", "touch", "say", "answer", "jump", "swim", "follow", "board", "launch", "lower",
            "raise", "lift", "break", "cut", "burn", "blow", "count", "pray", "knock", "save", "score",
            "restore", "north", "south", "east", "west", "northeast", "northwest", "southeast",
            "southwest", "up", "down", "n", "s", "e", "w", "ne", "nw", "se", "sw", "u", "d",
            "then", "also", "use", "return", "continue", "undo", "help", "cross", "peek", "peer", "stick",
            "slide", "insert", "explore", "try", "describe", "keep", "let", "unlock", "knock");
    private static final Map<String, String> OPPOSITE = new HashMap<>();
    private static final Map<String, String> DIRECTION_ABBREVIATION = new HashMap<>();
    private static final List<String> CANT_GO_PHRASES = Arrays.asList(
            "you can't go that way", "you must specify", "you hit your head", "i don't know the word",
            "there was no verb", "that sentence isn't one i recogni", "you can't see any such thing",
            "i only understood you as far as", "you used the word", "where do you want to go",
            "you'll have to say which", "you can't go there", "you can't see any", "you should supply",
            "you're not at", "you are not at");
    private static final Pattern GO_TO = Pattern.compile(
            "^(?:go|walk|head|run|travel|return|get|navigate|move|find the way|take me)"
                    + "(?: back)? (?:to|into|toward|towards) (?:the )?(.+)$");
    private static final Pattern GO_BARE = Pattern.compile("^(?:go|walk|head|run|travel) (?:the )?(.+)$");
    private static final Pattern USE_ON = Pattern.compile("^use (?:the )?(.+?) (?:on|with|in|to open) (?:the )?(.+)$");
    private static final Pattern REPEAT = Pattern.compile("^(.+?) (twice|two times|three times|four times|five times|(\\d) times)$");
    private static final Pattern CLAUSE_SPLIT = Pattern.compile(
            "\\s*(?:[.;!?]+\\s+|,?\\s+and then\\s+|,\\s*then\\s+|\\s+then\\s+|,\\s*after that\\s+|\\s+after that\\s+)\\s*");
    private static final Pattern AND_SPLIT = Pattern.compile("\\s*(?:,\\s*and\\s+|\\s+and\\s+|,\\s*)");

    static {
        String[][] pairs = {{"N", "S"}, {"E", "W"}, {"NE", "SW"}, {"NW", "SE"}, {"U", "D"}, {"IN", "OUT"}};
        for (String[] pair : pairs) {
            OPPOSITE.put(pair[0], pair[1]);
            OPPOSITE.put(pair[1], pair[0]);
        }
        for (Map.Entry<String, String> entry : CommandNormalizer.DIRECTIONS.entrySet()) {
            DIRECTION_ABBREVIATION.put(entry.getKey().replace(" ", ""), entry.getValue());
            DIRECTION_ABBREVIATION.put(entry.getValue().toLowerCase(Locale.ROOT), entry.getValue());
        }
        DIRECTION_ABBREVIATION.put("in", "IN");
        DIRECTION_ABBREVIATION.put("out", "OUT");
    }

    private static final class Move {
        final String direction;
        final String from;
        final String to;

        Move(String direction, String from, String to) {
            this.direction = direction;
            this.from = from;
            this.to = to;
        }
    }

    private static final class Snapshot {
        final Object state;
        final String room;
        final Move lastMove;
        final String lastNoun;
        final String input;

        Snapshot(Object state, String room, Move lastMove, String lastNoun, String input) {
            this.state = state;
            this.room = room;
            this.lastMove = lastMove;
            this.lastNoun = lastNoun;
            this.input = input;
        }
    }

    private final Game game;
    private List<String> pending;
    private final Deque<Snapshot> undo = new ArrayDeque<>();
    private final Map<String, Map<String, String>> map = new LinkedHashMap<>();
    // Exits in the map that are only guessed as the opposite of a move ("room\tdirection").
    private final Set<String> guessedExits = new HashSet<>();
    private final Set<String> vocabulary = new LinkedHashSet<>();
    // Words the game has printed that its parser also knows: what a misheard word may have meant.
    private final Set<String> seenGameWords = new LinkedHashSet<>();
    // Every command sent, so help suggests things the player hasn't tried yet.
    private final Set<String> triedCommands = new HashSet<>();
    private int rejectedInARow;
    // What the game last said must be done first ("open the glass bottle"), or null.
    private String neededFirst;
    private static final Pattern MUST_FIRST = Pattern.compile(
            "(?i)you'll have to ((?:open|unlock|take|get|remove|close) (?:the )?[a-z ]{1,30}?) first");
    private String room;
    private Move lastMove;
    private String lastNoun;
    private String roomDescription;
    // The fullest description seen for each room, for "go to the stairs" seen in an earlier room.
    private final Map<String, String> roomTexts = new HashMap<>();
    private final Deque<String> recentResponses = new ArrayDeque<>();

    private volatile HintAdvisor hints;
    private volatile boolean hintsLoading;

    public CommandSession(Game game) {
        this.game = game;
    }

    /** Call with true while a walkthrough index loads in the background. */
    public void setHintsLoading(boolean loading) {
        hintsLoading = loading;
    }

    /** Answers help and hint requests from a walkthrough; null for the generic answers. */
    public void setHintAdvisor(HintAdvisor advisor) {
        if (advisor != null) {
            synchronized (savedSkips) {
                advisor.restoreSkipped(savedSkips);
            }
        }
        hints = advisor;
        hintsLoading = false;
    }

    private final Set<Integer> savedSkips = new HashSet<>();

    /** Hint goals the player has done another way, as text to keep with the saved game. */
    public String hintSkips() {
        HintAdvisor advisor = hints;
        Set<Integer> skips;
        synchronized (savedSkips) {
            skips = new java.util.TreeSet<>(savedSkips);
        }
        if (advisor != null) {
            skips.addAll(advisor.skippedGoals());
        }
        StringBuilder text = new StringBuilder();
        for (Integer goal : skips) {
            text.append(text.length() == 0 ? "" : ",").append(goal);
        }
        return text.toString();
    }

    /** Restores {@link #hintSkips()} on resume, so a skipped hint doesn't come back. */
    public void restoreHintSkips(String text) {
        Set<Integer> skips = new HashSet<>();
        for (String part : (text == null ? "" : text).split(",")) {
            try {
                skips.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException ignored) {
                // Not a goal number.
            }
        }
        synchronized (savedSkips) {
            savedSkips.addAll(skips);
        }
        HintAdvisor advisor = hints;
        if (advisor != null) {
            advisor.restoreSkipped(skips);
        }
    }

    /** Call with the game's opening text so the starting room is known. */
    public void start(String introText) {
        learnVocabulary(introText);
        roomDescription = introText;
        String status = game.currentRoom();
        room = status != null ? status : lastRoomName(introText);
        if (room != null) {
            map.computeIfAbsent(room, k -> new LinkedHashMap<>());
            rememberRoomText(room, introText);
        }
    }

    private void rememberRoomText(String name, String text) {
        if (text == null) {
            return;
        }
        String old = roomTexts.get(name);
        if (old == null || text.length() > old.length()) {
            roomTexts.put(name, text);
        }
    }

    /** True while numbered suggestions are waiting for a choice. */
    public boolean hasPendingSuggestions() {
        return pending != null;
    }

    public Result handle(String input) {
        Result result = new Result();
        String text = input == null ? "" : input.trim();
        List<String> offered = pending;
        pending = null;

        if (offered != null) {
            int choice = CommandSuggester.parseChoice(text);
            if (choice >= 1 && choice <= offered.size()) {
                takeSnapshot(offered.get(choice - 1));
                runCommand(offered.get(choice - 1), result);
                return result;
            }
            if (choice == offered.size() + 1 || CommandNormalizer.canonicalize(text).equals("something else")) {
                result.note(ASK_AGAIN);
                return result;
            }
        }
        if (text.isEmpty()) {
            send(" ", result);
            return result;
        }

        String canonical = CommandNormalizer.canonicalize(text);
        if (SITUATION_PHRASES.contains(canonical) && !(canonical.equals("help") && game.contains("help"))) {
            result.note(situation());
            return result;
        }
        if (HELP_PHRASES.contains(canonical) && !game.contains("help")) {
            result.note(HELP_TEXT);
            return result;
        }
        if (HINT_PHRASES.contains(canonical) && !game.contains("hint")) {
            result.note(hint());
            return result;
        }
        if (UNDO_PHRASES.contains(canonical) && !game.contains("undo")) {
            undo(result);
            return result;
        }
        if (isStop(canonical)) {
            result.pauseListening = true;
            result.note(PAUSED_NOTE);
            return result;
        }

        if (EXIT_QUESTIONS.contains(canonical)) {
            describeExits(result);
            return result;
        }

        Matcher padded = PADDED_DIRECTION.matcher(canonical);
        if (padded.matches() && CommandNormalizer.direction(padded.group(1)) != null) {
            text = padded.group(1);
        }

        String roomBefore = room;
        boolean snapshot = takeSnapshot(text);
        String needed = neededFirst;
        runClauses(text, result);
        // "how about ghosts, I said go south": a long sentence the game rejected, with a command
        // at the end. A parser rejection doesn't use a turn, so run that command instead. Not for
        // "frobnicate the box and go north": the player chose to chain those, so stop at the error.
        String meant = result.rejected && result.sentCommands().size() <= 1
                && !canonical.matches(".*\\b(?:and|then)\\b.*") ? commandInSentence(canonical) : null;
        // "You'll have to open the glass bottle first." then a garbled "in the bottle".
        if (meant == null && needed != null && result.rejected && onlyParserRejections(result)
                && mentionsNoun(canonical, needed)) {
            meant = needed;
        }
        if (meant != null) {
            pending = null;
            result = new Result();
            result.note("(I took that as: " + meant + ".)");
            runClauses(meant, result);
        }
        // Looking around doesn't change anything, so it shouldn't use up an undo step.
        boolean changed = result.sentCommands().isEmpty();
        for (String command : result.sentCommands()) {
            changed |= !NO_STATE_COMMANDS.contains(command.toLowerCase(Locale.ROOT).trim());
        }
        if (!changed && snapshot) {
            undo.pop();
        }
        if (listThings && !result.rejected && room != null
                && (!room.equals(roomBefore) || result.sentCommands().contains("look")
                        || result.sentCommands().contains("l"))) {
            String things = thingsNote();
            if (things != null) {
                result.note(things);
            }
        }
        rejectedInARow = result.rejected ? rejectedInARow + 1 : 0;
        if (rejectedInARow == 2) {
            result.note(STUCK_NOTE);
        }
        return result;
    }

    private void runClauses(String text, Result result) {
        List<String> clauses = splitClauses(text);
        for (int i = 0; i < clauses.size(); i++) {
            if (i > 0 && isChatter(clauses.get(i))) {
                continue;
            }
            if (!runClause(clauses.get(i), result)) {
                break;
            }
        }
    }

    /**
     * The last command in a long sentence that doesn't start with one: "how about ghosts this
     * is what I said go south" -> "go south". Stops at a negation, so "go back into the house,
     * don't leave the house" gives "go back into the house". Null if there's no such command.
     */
    static String commandInSentence(String canonical) {
        String[] words = canonical.split(" ");
        if (words.length < 5 || VERB_STARTERS.contains(words[0]) && !WEAK_STARTERS.contains(words[0])) {
            return null;
        }
        int start = -1;
        for (int i = words.length - 1; i >= 1 && start < 0; i--) {
            if (VERB_STARTERS.contains(words[i]) && !WEAK_STARTERS.contains(words[i])
                    && !DIRECTION_ABBREVIATION.containsKey(words[i]) && !NEGATIONS.contains(words[i - 1])) {
                start = i;
            }
        }
        for (int i = words.length - 1; i >= 1 && start < 0; i--) {
            if (isDirectionWord(words[i]) && !NEGATIONS.contains(words[i - 1])) {
                start = i;
            }
        }
        if (start < 0) {
            return null;
        }
        int end = start + 1;
        while (end < words.length && !NEGATIONS.contains(words[end])) {
            end++;
        }
        if (end - start > 8) {
            return null;
        }
        return String.join(" ", Arrays.asList(words).subList(start, end));
    }

    private static boolean isDirectionWord(String word) {
        return word.length() > 1 && DIRECTION_ABBREVIATION.containsKey(word) && !word.equals("in")
                && !word.equals("out");
    }

    // "drop the newspaper, I don't need it": a remark after a command, not a command.
    private boolean isChatter(String clause) {
        String[] words = CommandNormalizer.canonicalize(clause).split(" ");
        return words.length >= 2 && CHATTER_STARTERS.contains(words[0]) && !VERB_STARTERS.contains(words[1]);
    }

    // Looks around and lists the exits the app has seen used from this room.
    private void describeExits(Result result) {
        runCommand("look", result);
        Map<String, String> exits = room == null ? null : map.get(room);
        if (exits == null || exits.isEmpty()) {
            result.note("The game describes the exits in the room description. Try a direction, like north or go up.");
            return;
        }
        List<String> known = new ArrayList<>();
        for (Map.Entry<String, String> exit : exits.entrySet()) {
            known.add(spokenDirection(exit.getKey()) + " to " + exit.getValue());
        }
        result.note("Ways you have used from here: " + String.join(", ", known) + ".");
    }

    private static String spokenDirection(String key) {
        for (Map.Entry<String, String> entry : CommandNormalizer.DIRECTIONS.entrySet()) {
            if (entry.getValue().equals(key) && !entry.getKey().contains(" ")) {
                return entry.getKey();
            }
        }
        switch (key) {
            case "IN": return "in";
            case "OUT": return "out";
            default: return key.toLowerCase(Locale.ROOT);
        }
    }

    // Returns false if the clause was rejected and the rest of the input should be skipped.
    private boolean runClause(String clause, Result result) {
        String canonical = CommandNormalizer.canonicalize(clause);
        if (canonical.isEmpty()) {
            return true;
        }
        int times = 1;
        Matcher repeat = REPEAT.matcher(canonical);
        if (repeat.matches()) {
            times = repeatCount(repeat.group(2));
            canonical = repeat.group(1);
            clause = canonical;
        }

        boolean flee = FLEE.matcher(canonical).matches();
        if (BACK_PHRASES.contains(canonical) || flee) {
            if (lastMove == null) {
                result.note(flee ? "To get away, say a direction, like north or south."
                        : "I don't know which way you came from yet.");
                return false;
            }
            String direction = backDirection();
            if (direction == null) {
                result.note("I don't know the way back to " + lastMove.from + " from here. Try look to see the exits.");
                return false;
            }
            return runCommand(direction, result);
        }
        if (CONTINUE_PHRASES.contains(canonical) && lastMove != null && OPPOSITE.containsKey(lastMove.direction)) {
            // Carrying on down a chasm or up a cliff is rarely what "go forward" means.
            if (!LEVEL_DIRECTIONS.contains(lastMove.direction)) {
                String way = spokenDirection(lastMove.direction);
                result.note("Your last move was " + way + ". Say go " + way
                        + " to go that way again, or name another direction.");
                return false;
            }
            boolean ok = true;
            for (int i = 0; i < times && ok; i++) {
                ok = runCommand(lastMove.direction, result);
            }
            return ok;
        }
        Matcher examine = EXAMINE_PLACE.matcher(canonical);
        if (examine.matches() && isRoomItself(examine.group(1))) {
            return runCommand("look", result);
        }
        Matcher swing = WEAPON_SWING.matcher(canonical);
        if (swing.matches()) {
            String target = swing.group(2) != null ? swing.group(2) : creatureHere();
            if (target != null) {
                // "Swing the sword" only whooshes in Zork; fighting takes "attack the troll with the sword".
                String attack = (game.contains("attack") ? "attack" : "kill") + " the " + target + " with the "
                        + swing.group(1);
                result.note("To fight, say " + attack + ".");
                return runCommand(attack, result);
            }
        }
        Matcher goTo = GO_TO.matcher(canonical);
        if (goTo.matches() && CommandNormalizer.direction(goTo.group(1)) == null) {
            if (HOUSE_WORDS.contains(goTo.group(1)) && canonical.matches(".* (?:into|inside) .*")
                    && game.contains("in")) {
                // "go back into the house" from outside it: Zork's own way in.
                String response = send("in", result);
                if (!cantGo(response) && !isRejected(response)) {
                    return true;
                }
                result.exchanges.remove(result.exchanges.size() - 1);
            }
            return goTo(clause, goTo.group(1), result);
        }
        Matcher climbIn = CLIMB_IN.matcher(canonical);
        if (climbIn.matches() && !(climbIn.group(1) != null && canonical.startsWith("go "))) {
            String thing = climbIn.group(1);
            if (thing == null && game.contains("in")) {
                return runCommand("in", result);
            }
            if (thing != null && game.contains("enter") && !HOUSE_WORDS.contains(thing)) {
                return runCommand("enter the " + thing, result);
            }
        }
        // "go kitchen": Zork wants a direction, but the player named a room they know.
        Matcher goBare = GO_BARE.matcher(canonical);
        if (goBare.matches() && CommandNormalizer.direction(goBare.group(1)) == null
                && findRoom(goBare.group(1)) != null && !findRoom(goBare.group(1)).equals(room)) {
            return walkTo(findRoom(goBare.group(1)), result);
        }
        Matcher leave = LEAVE.matcher(canonical);
        if (leave.matches() && isCurrentPlace(leave.group(1))) {
            return runCommand(exitCommand(), result);
        }

        String command = CommandNormalizer.normalize(clause, game);
        int mark = result.exchanges.size();
        boolean ok = true;
        for (int i = 0; i < times && ok; i++) {
            ok = runCommand(command, result);
        }
        if (!ok && times == 1 && tryLandmark(canonical, result, mark)) {
            return true;
        }
        return ok;
    }

    private String exitCommand() {
        return game.contains("exit") ? "exit" : "out";
    }

    // "leave the gazebo" when in the Gazebo, or "leave the room".
    // "examine the room", or "examine the living room" in a game that doesn't know "living".
    private boolean isRoomItself(String place) {
        if (set("room", "area", "place", "surroundings", "around", "here", "this place", "this room").contains(place)) {
            return true;
        }
        if (room == null || place.equals("house") || place.equals("building") || !isCurrentPlace(place)) {
            return false;
        }
        for (String word : place.split(" ")) {
            if (!word.equals("the") && !game.contains(word)) {
                return true;
            }
        }
        return false;
    }

    // A creature the player can see ("troll"), or null.
    private String creatureHere() {
        List<String> present = game.thingsHere();
        for (String thing : present) {
            for (String word : thing.toLowerCase(Locale.ROOT).split("[^a-z]+")) {
                if (CREATURES.contains(word)) {
                    return word;
                }
            }
        }
        if (!present.isEmpty()) {
            // The game's object tree is trusted: a troll that has died is gone from it.
            return null;
        }
        for (String thing : visibleThings()) {
            for (String word : thing.toLowerCase(Locale.ROOT).split(" ")) {
                if (CREATURES.contains(word)) {
                    return word;
                }
            }
        }
        String last = recentResponses.isEmpty() ? "" : recentResponses.getLast();
        String text = ((roomDescription == null ? "" : roomDescription) + " " + last).toLowerCase(Locale.ROOT);
        for (String word : text.split("[^a-z]+")) {
            if (CREATURES.contains(word) && game.contains(word)) {
                return word;
            }
        }
        return null;
    }

    private boolean isCurrentPlace(String place) {
        Set<String> generic = set("room", "place", "area", "building", "house", "here", "this place");
        if (generic.contains(place)) {
            return true;
        }
        if (room == null) {
            return false;
        }
        List<String> roomWords = Arrays.asList(room.toLowerCase(Locale.ROOT).split("[^a-z]+"));
        for (String word : place.split(" ")) {
            if (!word.equals("the") && !roomWords.contains(word)) {
                return false;
            }
        }
        return true;
    }

    // "cross the ford" or "climb onto the ledge" when the room description says
    // "a ford to the south" or "a ledge above you": send that direction instead.
    private boolean tryLandmark(String canonical, Result result, int mark) {
        String[] words = canonical.split(" ");
        if (words.length < 2 || !LANDMARK_VERBS.contains(words[0]) || roomDescription == null) {
            return false;
        }
        // In brief mode a revisited room shows only its name, so fetch the full description.
        if (roomDescription.trim().split("\\s+").length < 8) {
            int before = result.exchanges.size();
            send("look", result);
            while (result.exchanges.size() > before) {
                result.exchanges.remove(result.exchanges.size() - 1);
            }
        }
        Set<String> nouns = new LinkedHashSet<>();
        for (int i = 1; i < words.length; i++) {
            if (words[i].length() >= 3 && !NOUN_STOPS.contains(words[i]) && !set("onto", "into", "through",
                    "across", "over", "toward", "towards", "up", "down", "the", "inside", "along").contains(words[i])) {
                nouns.add(words[i]);
            }
        }
        String direction = null;
        for (String sentence : roomDescription.toLowerCase(Locale.ROOT).split("[.!?;]|\\r?\\n")) {
            List<String> sentenceWords = Arrays.asList(sentence.split("[^a-z]+"));
            boolean mentions = false;
            for (String noun : nouns) {
                // "stairway" also matches "stairs".
                String stem = noun.length() > 5 ? noun.substring(0, 5) : noun;
                for (String word : sentenceWords) {
                    mentions |= word.equals(noun) || (stem.length() == 5 && word.startsWith(stem));
                }
            }
            if (!mentions) {
                continue;
            }
            String found = directionIn(sentenceWords);
            if (found != null) {
                if (direction != null && !direction.equals(found)) {
                    return false; // ambiguous
                }
                direction = found;
            }
        }
        if (direction == null && words[0].equals("climb") && (words[1].equals("up") || words[1].equals("onto")
                || words[1].equals("on") || words[1].equals("to"))) {
            direction = "U";
        }
        if (direction == null || !game.contains(direction.toLowerCase(Locale.ROOT))) {
            return false;
        }
        String before = room;
        String response = send(direction, result);
        if (cantGo(response) || isRejected(response) || (before != null && before.equals(room))) {
            result.exchanges.remove(result.exchanges.size() - 1);
            return false;
        }
        // Replace the parser error and any suggestions with the move.
        Exchange move = result.exchanges.remove(result.exchanges.size() - 1);
        while (result.exchanges.size() > mark) {
            result.exchanges.remove(result.exchanges.size() - 1);
        }
        result.exchanges.add(move);
        pending = null;
        result.suggestions = Collections.emptyList();
        result.rejected = false;
        result.note("(I took that to mean go " + spokenDirection(direction) + ".)");
        return true;
    }

    private static String directionIn(List<String> words) {
        String found = null;
        for (int i = 0; i < words.size(); i++) {
            String word = words.get(i);
            String direction = null;
            if (i + 1 < words.size()) {
                direction = CommandNormalizer.DIRECTIONS.get(word + " " + words.get(i + 1));
            }
            if (direction == null && !word.equals("up") && !word.equals("down")) {
                direction = CommandNormalizer.DIRECTIONS.get(word);
            }
            if (direction == null && (word.equals("above") || word.equals("overhead") || word.equals("upward")
                    || word.equals("upwards"))) {
                direction = "U";
            }
            if (direction == null && (word.equals("below") || word.equals("downward") || word.equals("beneath"))) {
                direction = "D";
            }
            // "the north side of a white house" says where the player is, not which way to go.
            int after = i + (i + 1 < words.size() && CommandNormalizer.DIRECTIONS.containsKey(word + " " + words.get(i + 1))
                    ? 2 : 1);
            if (direction != null && after < words.size() && set("side", "wall", "walls", "end", "edge", "bank",
                    "shore", "part", "half", "corner", "face").contains(words.get(after))) {
                direction = null;
            }
            if (direction != null) {
                if (found != null && !found.equals(direction)) {
                    return null;
                }
                found = direction;
            }
        }
        return found;
    }

    // Sends one command and handles a rejection. Returns false if rejected.
    private boolean runCommand(String command, Result result) {
        String response = send(command, result);
        if (ECHO.matcher(response).matches()) {
            List<String> exits = new ArrayList<>();
            for (String exit : exitsHere()) {
                exits.add(spokenDirection(exit));
            }
            String back = backDirection();
            if (exits.isEmpty() && back != null) {
                exits.add(spokenDirection(back));
            }
            result.note(ECHO_NOTE + (exits.isEmpty() ? "." : ", like go " + spokenList(exits, "or") + "."));
            return true;
        }
        if (DARK.matcher(response).find()) {
            result.note(darkNote());
        }
        if (retryWithNoun(command, response, result)) {
            return true;
        }
        if (!isRejected(response)) {
            return true;
        }
        result.rejected = true;
        explainRejection(command, response, result);
        return false;
    }

    // In the dark: name the light the player carries, if any.
    private String darkNote() {
        for (String thing : game.carried()) {
            String lower = thing.toLowerCase(Locale.ROOT);
            for (String word : lower.split("[^a-z]+")) {
                if (LIGHT_THINGS.contains(word) || word.equals("candles") || word.equals("candle")) {
                    String verb = word.startsWith("candle") ? "light" : "turn on";
                    return "It's dark here. You're carrying the " + word + ", so say " + verb + " the " + word + ".";
                }
            }
        }
        return DARK_NOTE;
    }

    private String send(String command, Result result) {
        String before = room;
        String response = game.send(command);
        result.exchanges.add(new Exchange(command, response, null));
        learnVocabulary(response);
        String lowerCommand = command.toLowerCase(Locale.ROOT).trim();
        triedCommands.add(bareCommand(lowerCommand));
        String direction = moveKey(lowerCommand);
        String status = game.currentRoom();
        String after = status != null ? status : roomFromResponse(response, direction != null);
        boolean isMove = !NOT_MOVES.contains(lowerCommand) && !isRejected(response);
        if (after != null) {
            map.computeIfAbsent(after, k -> new LinkedHashMap<>());
            if (isMove && before != null && !after.equals(before) && (direction != null || isTravel(lowerCommand))) {
                // Any command that changes the room is a way between them ("go inside", "climb the stairs").
                String key = direction != null ? direction : lowerCommand;
                map.computeIfAbsent(before, k -> new LinkedHashMap<>()).put(key, after);
                guessedExits.remove(before + "\t" + key);
                // Assume the way back is the opposite direction until a move proves otherwise.
                String opposite = OPPOSITE.get(key);
                if (opposite != null && map.get(after).putIfAbsent(opposite, before) == null) {
                    guessedExits.add(after + "\t" + opposite);
                }
                lastMove = new Move(key, before, after);
            }
            if ((!after.equals(before) || lowerCommand.equals("look") || lowerCommand.equals("l")) && !isRejected(response)) {
                roomDescription = response;
                rememberRoomText(after, response);
            }
            room = after;
        }
        if (direction != null && before != null && before.equals(room) && map.containsKey(before)) {
            map.get(before).remove(direction);
            guessedExits.remove(before + "\t" + direction);
        }
        recentResponses.addLast(response == null ? "" : response);
        Matcher first = response == null ? null : MUST_FIRST.matcher(response.replace('\u2019', '\''));
        neededFirst = first != null && first.find() ? first.group(1).toLowerCase(Locale.ROOT) : null;
        while (recentResponses.size() > 6) {
            recentResponses.removeFirst();
        }
        if (!isRejected(response)) {
            rememberNoun(command, response);
        }
        HintAdvisor advisor = hints;
        if (advisor != null && response != null && advisor.isHintedCommand(command)
                && response.toLowerCase(Locale.ROOT).matches("(?s).*\\b(already|you have that)\\b.*")) {
            advisor.skipHintedIfStuck(game.stateVector());
        }
        return response;
    }

    // Commands worth replaying to walk a route: "climb the stairs", not "pray" or "attack troll".
    private static boolean isTravel(String command) {
        String first = command.split(" ")[0];
        return OPPOSITE.containsKey(command.toUpperCase(Locale.ROOT)) || LANDMARK_VERBS.contains(first)
                || set("exit", "leave", "board", "disembark", "slide", "descend", "ascend").contains(first);
    }

    // "N", "north", "go north", "go inside", "enter" -> the map key for a plain direction, else null.
    private static String moveKey(String command) {
        String text = command.replaceFirst("^(?:go|walk|run|head) ", "");
        String direction = DIRECTION_ABBREVIATION.get(text.replace(" ", ""));
        if (direction != null) {
            return direction;
        }
        switch (text) {
            case "inside": case "enter": return "IN";
            case "outside": case "exit": case "leave": return "OUT";
            default: return null;
        }
    }

    private boolean retryWithNoun(String command, String response, Result result) {
        String lower = response.toLowerCase(Locale.ROOT).replace('\u2019', '\'');
        boolean unclear = lower.contains("i don't see what you are referring to")
                || lower.contains("i'm not sure what") || lower.contains("what do you want to")
                || lower.contains("you can't see any such thing");
        if (!unclear || lastNoun == null) {
            return false;
        }
        String[] words = command.toLowerCase(Locale.ROOT).split(" ");
        boolean replaced = false;
        for (int i = 0; i < words.length; i++) {
            if (PRONOUNS.contains(words[i])) {
                words[i] = "the " + lastNoun;
                replaced = true;
            }
        }
        if (!replaced) {
            return false;
        }
        String retried = String.join(" ", words);
        String retryResponse = send(retried, result);
        if (isRejected(retryResponse) || retryResponse.toLowerCase(Locale.ROOT).contains("you can't see any")) {
            result.exchanges.remove(result.exchanges.size() - 1);
            return false;
        }
        // Hide the parser error; it didn't use a turn.
        result.exchanges.remove(result.exchanges.size() - 2);
        result.note("(I took it to mean the " + lastNoun + ".)");
        return true;
    }

    private void explainRejection(String command, String response, Result result) {
        String canonical = CommandNormalizer.canonicalize(command);
        String firstWord = canonical.split(" ")[0];
        if (QUESTION_WORDS.contains(firstWord)) {
            result.note(QUESTION_NOTE);
            return;
        }
        String lowerResponse = response.toLowerCase(Locale.ROOT).replace('\u2019', '\'');
        if (lowerResponse.contains("what do you want to") || lowerResponse.contains("noun missing")) {
            String asked = askedCommand(lowerResponse, canonical);
            result.note(asked != null ? asked
                    : "Say the whole command, including what to use, like " + exampleFor(canonical) + ".");
            return;
        }
        Matcher use = USE_ON.matcher(canonical);
        if (firstWord.equals("use") && !game.contains("use")) {
            if (use.matches()) {
                offer(useOptions(use.group(1), use.group(2)), result);
            } else if (canonical.startsWith("use ") && !useAlone(canonical.replaceFirst("^use (?:the |my )?", ""))
                    .isEmpty()) {
                offer(useAlone(canonical.replaceFirst("^use (?:the |my )?", "")), result);
            } else {
                result.note("This game doesn't know use. Say what to do instead, like turn, "
                        + "open, light, wave or read.");
            }
            return;
        }
        List<String> suggestions = CommandSuggester.suggest(command, game.dictionaryWords(), 3,
                response, recentVocabulary());
        if (suggestions.isEmpty()) {
            if (!CommandSuggester.isRejection(response) || response.toLowerCase(Locale.ROOT).contains("can't see")) {
                return;
            }
            result.note(REPHRASE_NOTE);
            return;
        }
        offer(suggestions, result);
    }

    // "use the key on the door": unlock first for keys, put in first for holes and slots.
    private List<String> useOptions(String tool, String target) {
        String[] verbs;
        String both = tool + " " + target;
        if (both.matches(".*\\b(key|keys)\\b.*")) {
            verbs = new String[]{"unlock", "open", "put"};
        } else if (target.matches(".*\\b(keyhole|hole|slot|lock|socket|crack|opening|receptacle)\\b.*")) {
            verbs = new String[]{"put", "unlock", "open"};
        } else if (tool.matches(".*\\b(lamp|lantern|torch|match|matches|candle|candles)\\b.*")) {
            verbs = new String[]{"light", "burn", "turn"};
        } else {
            verbs = new String[]{"open", "turn", "hit", "put", "unlock"};
        }
        List<String> options = new ArrayList<>();
        for (String verb : verbs) {
            if (!game.contains(verb)) {
                continue;
            }
            options.add(verb.equals("put")
                    ? "put the " + tool + " in the " + target
                    : verb + " the " + target + " with the " + tool);
            if (options.size() == 3) {
                break;
            }
        }
        return options;
    }

    private static final Pattern ASKED = Pattern.compile(
            "what do you want to ([a-z][a-z ]*?)(?: (to|with|in|into|on|onto|at|from|under|behind|through|off))?\\?");

    /**
     * Follows the game's own question: "What do you want to tie the rope to?" ->
     * "Just name it, or say the whole command, like tie the rope to the railing."
     * Null if the question can't be read.
     */
    private String askedCommand(String lowerResponse, String canonical) {
        Matcher asked = ASKED.matcher(lowerResponse);
        if (!asked.find()) {
            return null;
        }
        String action = asked.group(1).trim();
        String preposition = asked.group(2);
        String example = null;
        for (String thing : visibleThings()) {
            if (!(" " + action + " ").contains(" " + thing + " ")) {
                example = thing;
                break;
            }
        }
        String start = action + (preposition != null ? " " + preposition : "");
        if (example == null) {
            return "Just say what, or say the whole command, starting " + start + ".";
        }
        return "Just say what, like " + example + ", or say the whole command, like " + start + " the "
                + example + ".";
    }

    // "use the lamp": what that thing is usually used for.
    private List<String> useAlone(String thing) {
        List<String> options = new ArrayList<>();
        String the = "the " + thing;
        if (thing.matches(".*\\b(lamp|lantern|torch|flashlight)\\b.*")) {
            addOption(options, "turn", "turn on " + the);
            addOption(options, "light", "light " + the);
            addOption(options, "turn", "turn off " + the);
        } else if (thing.matches(".*\\b(match|matches|candle|candles)\\b.*")) {
            addOption(options, "light", "light " + the);
        } else if (thing.matches(".*\\brope\\b.*")) {
            for (String other : visibleThings()) {
                if (!other.contains("rope") && !CREATURES.contains(other)) {
                    addOption(options, "tie", "tie " + the + " to the " + other);
                    break;
                }
            }
            addOption(options, "climb", "climb " + the);
        } else if (thing.matches(".*\\b(sword|knife|axe|dagger|stiletto|blade)\\b.*")) {
            String creature = creatureHere();
            if (creature != null) {
                addOption(options, "attack", "attack the " + creature + " with " + the);
            }
        } else if (thing.matches(".*\\b(key|keys)\\b.*")) {
            String lock = "door";
            for (String other : visibleThings()) {
                if (other.matches(".*\\b(door|grate|grating|gate|chest|lock)\\b.*")) {
                    lock = other;
                    break;
                }
            }
            addOption(options, "unlock", "unlock the " + lock + " with " + the);
        }
        addOption(options, "examine", "examine " + the);
        String[] words = thing.split(" ");
        if (OPEN_THINGS.contains(words[words.length - 1])) {
            addOption(options, "open", "open " + the);
        }
        return options;
    }

    // "stop" or "pause", unless the game itself knows that one-word command.
    private boolean isStop(String canonical) {
        return STOP_PHRASES.contains(canonical) && (canonical.contains(" ") || !game.contains(canonical));
    }

    private void addOption(List<String> options, String verb, String option) {
        if (options.size() < 3 && game.contains(verb) && !options.contains(option)) {
            options.add(option);
        }
    }

    private static String exampleFor(String canonical) {
        String verb = canonical.split(" ")[0];
        switch (verb) {
            case "go": case "walk": return "go through the door";
            case "look": return "look in the box";
            case "put": return "put the coin in the slot";
            case "unlock": case "open": return verb + " the door with the key";
            default:
                if (verb.isEmpty()) {
                    return "open the door";
                }
                // "turn the bolt" -> "turn the bolt with the item you want to use"
                return canonical.contains(" ") ? canonical + " with the item you want to use" : verb + " the lamp";
        }
    }

    private void offer(List<String> options, Result result) {
        if (options.isEmpty()) {
            result.note(REPHRASE_NOTE);
            return;
        }
        pending = options;
        result.suggestions = options;
        result.note(CommandSuggester.buildPrompt(options));
    }

    private boolean goTo(String clause, String place, Result result) {
        Matcher behind = BEHIND_PLACE.matcher(place);
        if (behind.matches()) {
            place = "behind " + behind.group(1);
        }
        place = unglue(place);
        // Let the game try first ("go into the house" may be a real command); parser
        // errors don't use a turn, so on failure the reply is replaced by the walk.
        // Zork doesn't know "back" in "go back to the kitchen".
        String command = CommandNormalizer.normalize(
                clause.replaceFirst("(?i)\\bback (to|into|toward|towards)\\b", "$1"), game);
        String before = room;
        String response = send(command, result);
        String target = findRoom(place);
        boolean stayed = before != null && before.equals(room);
        if (!cantGo(response) && !(stayed && target != null && !target.equals(room))) {
            return true;
        }
        // Either the game didn't understand, or it answered without moving ("It's here!")
        // although the app knows the place is elsewhere.
        Exchange attempt = result.exchanges.remove(result.exchanges.size() - 1);
        if (target == null) {
            int mark = result.exchanges.size();
            // A nearby room the walkthrough knows by that name ("living room" next to the kitchen).
            HintAdvisor advisor = hints;
            int[] state = advisor == null ? null : game.stateVector();
            List<String> nearby = state == null ? null : advisor.routeToRoom(place, room, state, MAX_UNSEEN_WALK);
            if (nearby != null) {
                for (String step : nearby) {
                    if (!runCommand(step, result)) {
                        return false;
                    }
                }
                result.note("You walk to " + room + ".");
                return true;
            }
            result.exchanges.add(attempt);
            if (tryLandmark(CommandNormalizer.canonicalize(clause), result, mark)) {
                return true;
            }
            target = roomMentioning(place);
            if (target != null) {
                while (result.exchanges.size() > mark) {
                    result.exchanges.remove(result.exchanges.size() - 1);
                }
                return walkTo(target, result);
            }
            // The place the last hint sent the player to, by the walkthrough's route.
            List<String> hinted = advisor == null ? null : advisor.routeToHinted(place, room, game.stateVector());
            if (hinted != null && !hinted.isEmpty()) {
                while (result.exchanges.size() > mark) {
                    result.exchanges.remove(result.exchanges.size() - 1);
                }
                for (String step : hinted) {
                    if (!runCommand(step, result)) {
                        return false;
                    }
                }
                result.note("You walk to " + room + ".");
                return true;
            }
            result.rejected = true;
            result.note("I don't know a place called " + place + " yet. Try a direction, like north or go up.");
            return false;
        }
        return walkTo(target, result);
    }

    // Speech sometimes runs a room's name together ("livingroom"): split it back into the name.
    private String unglue(String place) {
        Set<String> names = new HashSet<>();
        for (String name : map.keySet()) {
            names.add(name.toLowerCase(Locale.ROOT));
        }
        HintAdvisor advisor = hints;
        if (advisor != null) {
            names.addAll(advisor.roomNames());
        }
        StringBuilder out = new StringBuilder();
        for (String word : place.split(" ")) {
            String replacement = word;
            for (String name : names) {
                if (name.contains(" ") && name.replace(" ", "").equals(word.toLowerCase(Locale.ROOT))) {
                    replacement = name;
                    break;
                }
            }
            out.append(out.length() == 0 ? "" : " ").append(replacement);
        }
        return out.toString();
    }

    private boolean walkTo(String target, Result result) {
        if (target.equals(room)) {
            result.note("You are already in " + target + ".");
            return true;
        }
        List<String> path = route(room, target);
        if (path == null) {
            result.note("I don't know a way to " + target + " from here yet.");
            return false;
        }
        String expected = room;
        for (String direction : path) {
            expected = map.get(expected).get(direction);
            if (!runCommand(direction, result)) {
                return false;
            }
            if (room == null || !room.equals(expected)) {
                result.note("I expected to reach " + expected + ", so I stopped here.");
                return false;
            }
        }
        result.note("You walk to " + target + ".");
        return true;
    }

    // The nearest other room whose description mentions the place ("stairway" matches "stairs").
    private String roomMentioning(String place) {
        Set<String> nouns = new LinkedHashSet<>();
        for (String word : place.toLowerCase(Locale.ROOT).split("[^a-z]+")) {
            if (word.length() >= 4 && !NOUN_STOPS.contains(word) && !DIRECTION_ABBREVIATION.containsKey(word)
                    && !set("room", "area", "place", "back", "side", "other", "that", "this").contains(word)) {
                nouns.add(word);
            }
        }
        if (nouns.isEmpty() || room == null) {
            return null;
        }
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (Map.Entry<String, String> entry : roomTexts.entrySet()) {
            String name = entry.getKey();
            if (name.equals(room)) {
                continue;
            }
            List<String> textWords = Arrays.asList(entry.getValue().toLowerCase(Locale.ROOT).split("[^a-z]+"));
            boolean mentions = true;
            for (String noun : nouns) {
                String stem = noun.length() > 5 ? noun.substring(0, 5) : noun;
                boolean found = false;
                for (String word : textWords) {
                    found |= word.equals(noun) || (stem.length() == 5 && word.startsWith(stem));
                }
                mentions &= found;
            }
            List<String> path = mentions ? route(room, name) : null;
            if (path != null && path.size() < bestDistance) {
                best = name;
                bestDistance = path.size();
            }
        }
        return best;
    }

    private String findRoom(String place) {
        String wanted = place.toLowerCase(Locale.ROOT).replaceAll("^(the|a|an) ", "");
        List<String> words = new ArrayList<>();
        for (String word : wanted.split(" ")) {
            if (word.equals("back")) {
                word = "behind";
            }
            if (!set("the", "a", "an", "room", "area", "place", "of").contains(word)) {
                words.add(word);
            }
        }
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String name : map.keySet()) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.equals(wanted)) {
                return name;
            }
            Set<String> nameWords = new HashSet<>(Arrays.asList(lower.split(" ")));
            if (words.isEmpty() || !nameWords.containsAll(words)) {
                continue;
            }
            List<String> path = room == null ? null : route(room, name);
            int distance = name.equals(room) ? 0 : path == null ? 1000 + name.length() : path.size();
            if (distance < bestDistance) {
                best = name;
                bestDistance = distance;
            }
        }
        return best;
    }

    private String hint() {
        HintAdvisor advisor = hints;
        int[] state = advisor == null ? null : game.stateVector();
        if (state != null) {
            // Directions in hints should be right, so leave out the map's guesses.
            Map<String, Map<String, String>> visited = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, String>> entry : map.entrySet()) {
                Map<String, String> exits = new LinkedHashMap<>();
                for (Map.Entry<String, String> exit : entry.getValue().entrySet()) {
                    if (!guessedExits.contains(entry.getKey() + "\t" + exit.getKey())) {
                        exits.put(exit.getKey(), exit.getValue());
                    }
                }
                visited.put(entry.getKey(), exits);
            }
            return advisor.hint(state, room, visited);
        }
        return hintsLoading ? HINTS_LOADING : HINT_TEXT;
    }

    /**
     * Handles speech: picks the recognizer alternative that fits the game best, fixes
     * misheard game words (see {@link SpeechMatcher}), and runs it. If the game rejects
     * it, the reply also says what was heard, so the player can tell a mishearing apart
     * from a command the game doesn't understand.
     */
    public Result handleSpeech(List<String> alternatives) {
        List<String> heard = new ArrayList<>();
        if (alternatives != null) {
            for (String alternative : alternatives) {
                if (alternative != null && !alternative.trim().isEmpty()) {
                    heard.add(alternative);
                }
            }
        }
        if (pending != null) {
            for (String alternative : heard) {
                int choice = CommandSuggester.parseChoice(alternative);
                if (choice >= 1 && choice <= pending.size() + 1
                        || CommandNormalizer.canonicalize(alternative).equals("something else")) {
                    return handle(alternative);
                }
            }
        }
        for (String alternative : heard) {
            String canonical = CommandNormalizer.canonicalize(alternative);
            if (isRepeatedHint(canonical)) {
                Result result = handle("hint");
                result.heard = "hint";
                return result;
            }
            if (SITUATION_PHRASES.contains(canonical) || HELP_PHRASES.contains(canonical)
                    || HINT_PHRASES.contains(canonical) || UNDO_PHRASES.contains(canonical)
                    || isStop(canonical)) {
                Result result = handle(alternative);
                result.heard = canonical;
                return result;
            }
        }
        // A lone "hint" often comes back as "tent" or "can't", with no "hint" among the guesses.
        if (!heard.isEmpty() && !hasPendingSuggestions()) {
            String first = heard.get(0).trim().toLowerCase(Locale.ROOT);
            if (!first.contains(" ") && !game.contains(first) && !appWords().contains(first)) {
                for (String alternative : heard) {
                    if (HINT_ALIKES.contains(alternative.trim().toLowerCase(Locale.ROOT))) {
                        Result result = handle("hint");
                        result.heard = "hint";
                        return result;
                    }
                }
            }
        }
        SpeechMatcher.Vocabulary vocabulary = speechVocabulary();
        String chosen = SpeechMatcher.choose(heard, vocabulary);
        int rejectedBefore = rejectedInARow;
        Snapshot top = undo.peek();
        Result result = handle(chosen);
        // The recognizer's first guess was rejected without using a turn ("catch the rope to the
        // railing"): try the next best guesses ("attach the rope...") before asking the player again.
        if (result.rejected && onlyParserRejections(result)) {
            List<String> savedPending = pending;
            int tries = 0;
            for (String other : SpeechMatcher.rank(heard, vocabulary)) {
                if (tries >= 2) {
                    break;
                }
                if (other.equals(chosen) || SpeechMatcher.unknownWords(other, vocabulary) > 0) {
                    continue;
                }
                tries++;
                while (!undo.isEmpty() && undo.peek() != top) {
                    undo.pop();
                }
                rejectedInARow = rejectedBefore;
                pending = null;
                Result retry = handle(other);
                if (!retry.rejected || !onlyParserRejections(retry)) {
                    retry.heard = other;
                    return retry;
                }
            }
            if (tries > 0) {
                while (!undo.isEmpty() && undo.peek() != top) {
                    undo.pop();
                }
                takeSnapshot(chosen);
                rejectedInARow = rejectedBefore + 1;
                pending = savedPending;
            }
        }
        result.heard = chosen;
        if (result.rejected && !chosen.isEmpty()) {
            int at = result.exchanges.size();
            for (int i = 0; i < result.exchanges.size(); i++) {
                if (result.exchanges.get(i).command == null) {
                    at = i;
                    break;
                }
            }
            result.exchanges.add(at, new Exchange(null, null, "I heard: " + chosen + "."));
        }
        return result;
    }

    // "hint hint" or "hint hit": a repeated plea for a hint, with no other words.
    private boolean isRepeatedHint(String canonical) {
        String[] words = canonical.split(" ");
        if (words.length < 2 || words.length > 3 || game.contains("hint")) {
            return false;
        }
        boolean hint = false;
        for (String word : words) {
            if (word.equals("hint") || word.equals("hints")) {
                hint = true;
            } else if (!HINT_ALIKES.contains(word) && !word.equals("hit") && !word.equals("him")
                    && !word.equals("in") && !word.equals("hen")) {
                return false;
            }
        }
        return hint;
    }

    /** Words worth telling the speech recognizer to expect: directions, verbs and things seen in the game. */
    public List<String> speechHints() {
        Set<String> words = new LinkedHashSet<>(Arrays.asList("north", "south", "east", "west", "northeast",
                "northwest", "southeast", "southwest", "up", "down", "look", "inventory", "examine", "help",
                "hint", "undo"));
        for (String verb : COMMON_VERBS) {
            if (game.contains(verb)) {
                words.add(verb);
            }
        }
        for (String thing : visibleThings()) {
            words.add(thing.toLowerCase(Locale.ROOT));
        }
        for (String word : recentVocabulary()) {
            if (seenGameWords.contains(word)) {
                words.add(word);
            }
        }
        List<String> hints = new ArrayList<>(words);
        return hints.size() > 100 ? new ArrayList<>(hints.subList(0, 100)) : hints;
    }

    private SpeechMatcher.Vocabulary speechVocabulary() {
        final Set<String> candidates = new LinkedHashSet<>(seenGameWords);
        for (String word : Arrays.asList("north", "south", "east", "west", "northeast", "northwest",
                "southeast", "southwest", "inventory", "examine", "look")) {
            if (game.contains(word)) {
                candidates.add(word);
            }
        }
        for (String verb : COMMON_VERBS) {
            if (game.contains(verb)) {
                candidates.add(verb);
            }
        }
        final Set<String> visible = new HashSet<>();
        for (String thing : visibleThings()) {
            visible.addAll(Arrays.asList(thing.toLowerCase(Locale.ROOT).split("[^a-z]+")));
        }
        return new SpeechMatcher.Vocabulary() {
            @Override
            public boolean knows(String word) {
                return appWords().contains(word) || isGameWord(word);
            }

            @Override
            public boolean isVisible(String word) {
                return visible.contains(word) && isGameWord(word);
            }

            @Override
            public boolean isGameWord(String word) {
                return game.contains(word) && (seenGameWords.contains(word) || !isGluedWords(word));
            }

            @Override
            public java.util.Collection<String> candidates() {
                return candidates;
            }
        };
    }

    // "livingroom": two words run together. Old games compare only the first six letters, so
    // the game seems to know it ("living"), but it doesn't.
    private boolean isGluedWords(String word) {
        if (word.length() <= 6 || CommandNormalizer.DIRECTIONS.containsKey(word)) {
            return false;
        }
        for (int i = 3; i <= word.length() - 3; i++) {
            String first = word.substring(0, i);
            String second = word.substring(i);
            if ((game.contains(first) || appWords().contains(first)) && first.length() < 7
                    && (game.contains(second) || appWords().contains(second))) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> appWords;

    // Words the app itself understands, so speech matching never "corrects" them.
    private static synchronized Set<String> appWords() {
        if (appWords == null) {
            Set<String> words = new HashSet<>(CommandNormalizer.knownWords());
            for (Set<String> phrases : Arrays.asList(SITUATION_PHRASES, HELP_PHRASES, HINT_PHRASES, UNDO_PHRASES,
                    BACK_PHRASES, CONTINUE_PHRASES, EXIT_QUESTIONS, VERB_STARTERS, LANDMARK_VERBS, STOP_PHRASES)) {
                for (String phrase : phrases) {
                    words.addAll(Arrays.asList(phrase.split(" ")));
                }
            }
            words.addAll(Arrays.asList("one", "two", "three", "four", "five", "something", "else", "twice",
                    "times", "help", "hint", "undo", "again", "restart", "save", "restore", "quit", "yes", "no"));
            appWords = words;
        }
        return appWords;
    }

    private static final List<String> COMMON_VERBS = Arrays.asList("take", "drop", "open", "close", "read",
            "examine", "look", "move", "push", "pull", "turn", "light", "put", "give", "attack", "kill", "climb",
            "enter", "unlock", "tie", "wait", "inventory", "wear", "eat", "drink", "throw", "listen");
    private static final List<String> DIRECTION_ORDER = Arrays.asList("N", "NE", "E", "SE", "S", "SW", "W",
            "NW", "U", "D", "IN", "OUT");
    private static final Pattern MENTIONED_DIRECTION = Pattern.compile(
            "\\b(north(?:east|west)?|south(?:east|west)?|east|west)\\b"
                    + "(?!\\s+(?:of(?!\\s+here)|side|wall|walls|end|edge|bank|shore|part|half|corner|face)\\b)");
    private static final Pattern MENTIONED_UP_DOWN = Pattern.compile(
            "\\b(?:leads?|leading|goes|going|heads?|winds?|winding|climbs?|stairs?|staircase|stairway|ladder|"
                    + "steps|chimney|passage|path|tunnel)\\s+(?:\\w+\\s+)?(up|down)(?:ward|wards)?\\b");
    private static final Set<String> NOT_THINGS = set("you", "yourself", "me", "cretin", "adventurer",
            "player", "it", "ground", "floor", "sky", "air");
    private static final Pattern NOT_SEEN = Pattern.compile(
            "(?i)can't see|cannot see|don't see|do not see|don't know the word|isn't one i recognize");
    private static final Set<String> READ_THINGS = set("leaflet", "book", "note", "paper", "sign", "label",
            "inscription", "guidebook", "map", "letter", "scroll", "pamphlet", "manual", "card", "engravings",
            "prayer", "newspaper", "advertisement", "tome", "plaque", "matchbook", "brochure", "parchment");
    private static final Set<String> OPEN_THINGS = set("mailbox", "box", "door", "window", "case", "chest",
            "sack", "bag", "bottle", "lid", "coffin", "cabinet", "drawer", "gate", "grate", "grating", "trapdoor",
            "jar", "tube", "crate", "basket", "buoy", "egg", "hatch", "cupboard", "trunk", "container");
    private static final Set<String> LIGHT_THINGS = set("lamp", "lantern", "torch", "flashlight");
    private static final Set<String> MOVE_THINGS = set("rug", "carpet", "mat", "rock", "boulder", "leaves");
    private static final Set<String> CLIMB_THINGS = set("tree", "ladder", "stairs", "staircase", "rope",
            "cliff", "chimney");

    /**
     * Answers "help": where the player is, what they can see, the ways out, and two or three
     * concrete commands to try, chosen from things they haven't tried yet.
     */
    String situation() {
        // After a resume the last text is the recap, not the room, so look quietly first.
        if (room != null && (roomDescription == null || roomDescription.trim().split("\\s+").length < 8
                || !roomDescription.toLowerCase(Locale.ROOT).contains(room.toLowerCase(Locale.ROOT)))) {
            send("look", new Result());
        }
        StringBuilder text = new StringBuilder();
        if (room != null) {
            text.append("You're at ").append(room).append(". ");
        }
        List<String> things = visibleThings();
        if (!things.isEmpty()) {
            List<String> named = new ArrayList<>();
            for (String thing : things) {
                named.add("the " + thing);
            }
            text.append("You can see ").append(spokenList(named, "and")).append(". ");
        }
        List<String> exits = exitsHere();
        List<String> exitWords = new ArrayList<>();
        for (String exit : exits) {
            exitWords.add(spokenDirection(exit));
        }
        if (!exitWords.isEmpty()) {
            text.append("You can go ").append(spokenList(exitWords, "or")).append(". ");
        }
        List<String> tries = new ArrayList<>();
        for (String thing : things) {
            String command = commandFor(thing);
            if (command != null) {
                tries.add(command);
            }
            if (tries.size() == 2) {
                break;
            }
        }
        String move = untriedExit(exits);
        if (move != null) {
            tries.add("go " + spokenDirection(move));
        }
        if (tries.isEmpty()) {
            tries.add("look");
            tries.add("inventory");
        }
        text.append("You could try: ").append(spokenList(tries, "or")).append(". ");
        if (hints != null) {
            text.append("Say hint if you're stuck, or how do I play to learn more commands.");
        } else {
            text.append("Say how do I play to learn more commands.");
        }
        return text.toString().trim();
    }

    private boolean listThings;

    /**
     * After a move or "look", add a note naming the things here ("Things here: the mailbox and
     * the door."), so a listener knows which words to use without seeing the text.
     */
    public void setListThings(boolean listThings) {
        this.listThings = listThings;
    }

    private String thingsNote() {
        String text = roomDescription == null ? "" : " " + roomDescription.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z]+", " ") + " ";
        List<String> named = new ArrayList<>();
        for (String thing : visibleThings()) {
            String noun = nounOf(thing);
            // "There is no door here" doesn't put a door here.
            if (noun != null && text.contains(" " + noun + " ")
                    && !text.matches(".* no (?:[a-z]+ )?" + noun + " .*")) {
                named.add("the " + thing);
            }
        }
        return named.isEmpty() ? null : "Things here: " + spokenList(named, "and") + ".";
    }

    // Names of things in the room that the game has mentioned, so hidden objects stay hidden.
    private List<String> visibleThings() {
        StringBuilder seen = new StringBuilder();
        for (String response : recentResponses) {
            // "You can't see any leaflet here!" doesn't make the leaflet visible.
            if (response != null && !NOT_SEEN.matcher(response).find()) {
                seen.append(' ').append(response);
            }
        }
        if (roomDescription != null) {
            seen.append(' ').append(roomDescription);
        }
        String recent = " " + seen.toString().toLowerCase(Locale.ROOT).replaceAll("[^a-z]+", " ") + " ";
        List<String> visible = new ArrayList<>();
        Set<String> nouns = new HashSet<>();
        for (String name : game.thingsHere()) {
            String clean = name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]+", " ").trim();
            if (clean.isEmpty() || room != null && clean.equalsIgnoreCase(room)) {
                continue;
            }
            String noun = nounOf(clean);
            if (noun == null || NOT_THINGS.contains(noun) || DIRECTION_ABBREVIATION.containsKey(noun)
                    || !recent.contains(" " + noun + " ") || !nouns.add(noun)) {
                continue;
            }
            visible.add(clean);
            if (visible.size() == 5) {
                break;
            }
        }
        // Scenery such as Zork's window and rug isn't in the room's object tree, so also take
        // things the room description mentions that have an obvious action.
        if (roomDescription != null) {
            for (String word : roomDescription.toLowerCase(Locale.ROOT).split("[^a-z]+")) {
                if (visible.size() >= 5) {
                    break;
                }
                if ((OPEN_THINGS.contains(word) || READ_THINGS.contains(word) || MOVE_THINGS.contains(word)
                        || CLIMB_THINGS.contains(word) || LIGHT_THINGS.contains(word))
                        && game.contains(word) && nouns.add(word)) {
                    visible.add(word);
                }
            }
        }
        return visible;
    }

    // "pile of leaves" -> "leaves"; "brass lantern" -> "lantern".
    private static String nounOf(String name) {
        String[] words = name.split(" ");
        for (int i = words.length - 1; i >= 0; i--) {
            if (words[i].length() >= 2) {
                return words[i];
            }
        }
        return null;
    }

    private String commandFor(String thing) {
        String noun = nounOf(thing);
        List<String> options = new ArrayList<>();
        if (READ_THINGS.contains(noun)) {
            options.add("read");
        }
        if (OPEN_THINGS.contains(noun)) {
            options.add("open");
        }
        if (LIGHT_THINGS.contains(noun)) {
            options.add("turn on");
        }
        if (MOVE_THINGS.contains(noun)) {
            options.add("move");
        }
        if (CLIMB_THINGS.contains(noun)) {
            options.add("climb");
        }
        options.add(game.contains("examine") || !game.contains("x") ? "examine" : "x");
        options.add("take");
        for (String verb : options) {
            String command = verb + " the " + noun;
            if (!game.contains(verb.split(" ")[0]) || tried(command)) {
                continue;
            }
            return command;
        }
        return null;
    }

    private boolean tried(String command) {
        return triedCommands.contains(bareCommand(command));
    }

    // "x the lamp" and "examine lamp" count as the same command.
    private static String bareCommand(String command) {
        return command.toLowerCase(Locale.ROOT).trim().replace(" the ", " ").replaceFirst("^x ", "examine ");
    }

    // Prefers a way the player hasn't taken from this room yet.
    private String untriedExit(List<String> exits) {
        Map<String, String> used = room == null ? null : map.get(room);
        for (String exit : exits) {
            if (used == null || !used.containsKey(exit) || guessedExits.contains(room + "\t" + exit)) {
                return exit;
            }
        }
        return exits.isEmpty() ? null : exits.get(0);
    }

    // Exits from the walkthrough map when there is one, plus ways the player has used, plus
    // directions the room description mentions.
    private List<String> exitsHere() {
        Set<String> found = new HashSet<>();
        HintAdvisor advisor = hints;
        int[] state = advisor == null ? null : game.stateVector();
        boolean mapped = false;
        if (state != null) {
            List<String> known = advisor.exitsFrom(HintAdvisor.roomId(room, state));
            found.addAll(known);
            mapped = !known.isEmpty();
        }
        Map<String, String> used = room == null ? null : map.get(room);
        if (used != null) {
            for (String key : used.keySet()) {
                if (DIRECTION_ORDER.contains(key) && !guessedExits.contains(room + "\t" + key)) {
                    found.add(key);
                }
            }
        }
        if (!mapped && roomDescription != null) {
            String description = roomDescription.toLowerCase(Locale.ROOT);
            Matcher compass = MENTIONED_DIRECTION.matcher(description);
            while (compass.find()) {
                String key = DIRECTION_ABBREVIATION.get(compass.group(1));
                if (key != null) {
                    found.add(key);
                }
            }
            Matcher upDown = MENTIONED_UP_DOWN.matcher(description);
            while (upDown.find()) {
                found.add(upDown.group(1).equals("up") ? "U" : "D");
            }
        }
        List<String> ordered = new ArrayList<>();
        for (String key : DIRECTION_ORDER) {
            if (found.contains(key)) {
                ordered.add(key);
            }
        }
        return ordered;
    }

    private static String spokenList(List<String> items, String conjunction) {
        if (items.size() <= 1) {
            return items.isEmpty() ? "" : items.get(0);
        }
        return String.join(", ", items.subList(0, items.size() - 1)) + " " + conjunction + " "
                + items.get(items.size() - 1);
    }

    // Prefers exits the player has used; a guessed way back may not exist (Zork's forest).
    private List<String> route(String from, String to) {
        List<String> sure = route(from, to, false);
        return sure != null ? sure : route(from, to, true);
    }

    private List<String> route(String from, String to, boolean guesses) {
        if (from == null) {
            return null;
        }
        Map<String, List<String>> paths = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        paths.put(from, new ArrayList<>());
        queue.add(from);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (current.equals(to)) {
                return paths.get(current);
            }
            Map<String, String> exits = map.get(current);
            if (exits == null || paths.get(current).size() >= MAX_WALK) {
                continue;
            }
            for (Map.Entry<String, String> exit : exits.entrySet()) {
                if (!guesses && guessedExits.contains(current + "\t" + exit.getKey())) {
                    continue;
                }
                if (!paths.containsKey(exit.getValue())) {
                    List<String> path = new ArrayList<>(paths.get(current));
                    path.add(exit.getKey());
                    paths.put(exit.getValue(), path);
                    queue.add(exit.getValue());
                }
            }
        }
        return null;
    }

    private String backDirection() {
        if (lastMove == null) {
            return null;
        }
        Map<String, String> exits = room == null ? null : map.get(room);
        if (exits != null) {
            for (Map.Entry<String, String> exit : exits.entrySet()) {
                if (exit.getValue().equals(lastMove.from)) {
                    return exit.getKey();
                }
            }
        }
        return OPPOSITE.get(lastMove.direction);
    }

    private boolean takeSnapshot(String input) {
        Object state = game.snapshot();
        if (state == null) {
            return false;
        }
        undo.push(new Snapshot(state, room, lastMove, lastNoun, input));
        while (undo.size() > MAX_UNDO) {
            undo.removeLast();
        }
        return true;
    }

    private void undo(Result result) {
        Snapshot snapshot = undo.poll();
        if (snapshot == null || !game.restore(snapshot.state)) {
            result.note("There is nothing to undo.");
            return;
        }
        String status = game.currentRoom();
        room = status != null ? status : snapshot.room;
        lastMove = snapshot.lastMove;
        lastNoun = snapshot.lastNoun;
        String what = snapshot.input == null || snapshot.input.trim().isEmpty() ? "Undone" : "Took back: " + snapshot.input.trim();
        result.note(room == null ? what + "." : what + ". You are in " + room + ".");
    }

    private void rememberNoun(String command, String response) {
        String[] words = command.toLowerCase(Locale.ROOT).split(" ");
        boolean handledObject = false;
        for (int i = words.length - 1; i >= 1; i--) {
            String word = words[i];
            if (!NOUN_STOPS.contains(word) && !PRONOUNS.contains(word)
                    && !DIRECTION_ABBREVIATION.containsKey(word) && game.contains(word)) {
                lastNoun = word;
                handledObject = true;
                break;
            }
        }
        // Only object interactions ("open the mailbox" -> "reveals a leaflet") introduce a new noun;
        // room descriptions from look or movement mention too much scenery.
        String noun = handledObject ? lastNounIn(response) : null;
        if (noun != null) {
            lastNoun = noun;
        }
    }

    // The last "a/an/the <known words>" phrase in the response; the last word is the noun.
    private String lastNounIn(String response) {
        if (response == null) {
            return null;
        }
        String[] words = response.toLowerCase(Locale.ROOT).replaceAll("[^a-z\\s]", " ").trim().split("\\s+");
        String noun = null;
        for (int i = 0; i < words.length; i++) {
            if (!words[i].equals("a") && !words[i].equals("an") && !words[i].equals("the")) {
                continue;
            }
            String candidate = null;
            for (int j = i + 1; j < words.length && j <= i + 4; j++) {
                if (NOUN_STOPS.contains(words[j]) || !game.contains(words[j])) {
                    break;
                }
                candidate = words[j];
            }
            if (candidate != null) {
                noun = candidate;
            }
        }
        return noun;
    }

    private void learnVocabulary(String response) {
        if (response == null) {
            return;
        }
        for (String word : response.toLowerCase(Locale.ROOT).split("[^a-z]+")) {
            if (word.length() >= 3) {
                if (vocabulary.add(word) && game.contains(word)) {
                    seenGameWords.add(word);
                }
            }
        }
    }

    // Words from the last few replies and the room description: things the player can refer to now.
    private Set<String> recentVocabulary() {
        Set<String> words = new LinkedHashSet<>();
        List<String> texts = new ArrayList<>(recentResponses);
        texts.add(roomDescription);
        for (String text : texts) {
            if (text == null) {
                continue;
            }
            for (String word : text.toLowerCase(Locale.ROOT).split("[^a-z]+")) {
                if (word.length() >= 3) {
                    words.add(word);
                }
            }
        }
        return words;
    }

    private boolean isRejected(String response) {
        return CommandSuggester.isRejection(response);
    }

    // True if the player's words name the thing in the command ("in the bottle", "open the glass bottle").
    private static boolean mentionsNoun(String canonical, String command) {
        String[] words = command.split(" ");
        String noun = words[words.length - 1];
        return Arrays.asList(canonical.split(" ")).contains(noun);
    }

    // True if nothing reached the game but parser errors, which don't use a turn.
    private boolean onlyParserRejections(Result result) {
        for (Exchange exchange : result.exchanges) {
            if (exchange.command != null && !isRejected(exchange.response)) {
                return false;
            }
        }
        return true;
    }

    private static boolean cantGo(String response) {
        String lower = response == null ? "" : response.toLowerCase(Locale.ROOT).replace('\u2019', '\'');
        for (String phrase : CANT_GO_PHRASES) {
            if (lower.contains(phrase)) {
                return true;
            }
        }
        return false;
    }

    // A room heading is a short capitalised first line without sentence punctuation, e.g. "West of House".
    static String roomFromResponse(String response, boolean moved) {
        if (response == null || response.trim().isEmpty()) {
            return null;
        }
        String[] lines = response.trim().split("\\r?\\n");
        for (int i = 0; i < Math.min(lines.length, moved ? 1 : 6); i++) {
            String line = lines[i].trim();
            if (looksLikeRoomName(line)) {
                return line;
            }
        }
        return null;
    }

    // The intro's title and copyright come first; the room heading is the last one.
    static String lastRoomName(String text) {
        if (text == null) {
            return null;
        }
        String[] lines = text.trim().split("\\r?\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            if (looksLikeRoomName(lines[i].trim())) {
                return lines[i].trim();
            }
        }
        return null;
    }

    private static boolean looksLikeRoomName(String line) {
        if (line.isEmpty() || line.matches(".*[.!?:,\"]$") || line.contains(">")) {
            return false;
        }
        String[] words = line.split("\\s+");
        if (words.length > 6) {
            return false;
        }
        Set<String> small = set("of", "the", "and", "a", "an", "in", "on", "to", "at", "by", "with");
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            if (i > 0 && small.contains(word)) {
                continue;
            }
            if (!Character.isUpperCase(word.charAt(0))) {
                return false;
            }
        }
        return true;
    }

    /** Splits "open the mailbox and take the leaflet. go north" into separate commands. */
    List<String> splitClauses(String input) {
        List<String> clauses = new ArrayList<>();
        for (String sentence : CLAUSE_SPLIT.split(input.trim())) {
            String[] parts = AND_SPLIT.split(sentence);
            StringBuilder current = new StringBuilder();
            Matcher separators = AND_SPLIT.matcher(sentence);
            List<String> seps = new ArrayList<>();
            while (separators.find()) {
                seps.add(separators.group());
            }
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i].trim();
                if (part.isEmpty()) {
                    continue;
                }
                String first = CommandNormalizer.canonicalize(part).split(" ")[0];
                if (current.length() > 0 && !VERB_STARTERS.contains(first)) {
                    current.append(seps.get(i - 1)).append(part);
                } else {
                    if (current.length() > 0) {
                        clauses.add(current.toString());
                    }
                    current = new StringBuilder(part);
                }
            }
            if (current.length() > 0) {
                clauses.add(current.toString());
            }
        }
        return clauses;
    }

    private static int repeatCount(String phrase) {
        switch (phrase) {
            case "twice": case "two times": return 2;
            case "three times": return 3;
            case "four times": return 4;
            case "five times": return 5;
            default:
                return Math.max(1, Math.min(MAX_REPEAT, Integer.parseInt(phrase.substring(0, 1))));
        }
    }

    private static Set<String> set(String... words) {
        return new HashSet<>(Arrays.asList(words));
    }

    private static Set<String> canonicalSet(String... phrases) {
        Set<String> result = new HashSet<>();
        for (String phrase : phrases) {
            result.add(phrase);
            result.add(CommandNormalizer.canonicalize(phrase));
        }
        return result;
    }
}

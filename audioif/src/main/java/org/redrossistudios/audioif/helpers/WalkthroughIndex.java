package org.redrossistudios.audioif.helpers;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A walkthrough replayed on a hidden copy of the game. Each step records the room it
 * is taken in, the room it leads to, and which parts of the game state it changes
 * (see {@link GameStateReader}). {@link HintAdvisor} compares those changes with the
 * player's game to find the first step the player hasn't done yet.
 */
public final class WalkthroughIndex {

    /** A fresh, hidden game to replay the walkthrough on. */
    public interface Player {
        String send(String command);

        /** The current room from the status line, or null. */
        String room();

        int[] state();

        Object snapshot();

        void restore(Object snapshot);
    }

    public static final class Step {
        public final String command;
        public final String room;
        /** The room after the step if it moved the player, else null. */
        public final String toRoom;
        /** For a move: the opposite move was tried and led back to {@link #room}. */
        public final boolean returns;
        final int[] fields;
        final int[] values;

        Step(String command, String room, String toRoom, int[] fields, int[] values) {
            this(command, room, toRoom, false, fields, values);
        }

        Step(String command, String room, String toRoom, boolean returns, int[] fields, int[] values) {
            this.command = command;
            this.room = room;
            this.toRoom = toRoom;
            this.returns = returns;
            this.fields = fields;
            this.values = values;
        }

        /** A step the player should do: it changes lasting state and isn't just walking or dropping things. */
        public boolean isGoal() {
            return toRoom == null && fields.length > 0 && !command.matches("drop [^ ]+( and [^ ]+)*|drop all.*");
        }
    }

    static final int FORMAT = 8;
    private static final String[] PROBES = {"n", "s", "e", "w", "ne", "nw", "se", "sw", "u", "d"};
    private static final int MAX_MAPPED_ROOMS = 400;
    private static final Pattern DARK = Pattern.compile("(?is).*\\b(pitch black|too dark)\\b.*");
    private static final int MAX_REPEAT = 12;
    private static final int[] REWIND_BACK = {0, 0, 1, 1, 2, 2, 4, 4, 8, 8};
    private static final int REWIND_TRIES = REWIND_BACK.length;
    private static final int MAX_RETRIES = 13;
    private static final int MAX_DEATH_RETRIES = 16;
    private static final WalkthroughParser.Step WAIT = new WalkthroughParser.Step("wait", null);
    private static final Pattern DIRECTION = Pattern.compile(
            "(n|s|e|w|ne|nw|se|sw|u|d|up|down|north|south|east|west|northeast|northwest|southeast|southwest)");
    private static final Pattern FAILURE = Pattern.compile("(?is)\\s*(you can't see any|you can't go that way"
            + "|i don't know the word|there is no|there's no|those things aren't here|it's not clear"
            + "|you don't have|you can't reach|i don't understand|that sentence isn't|it's too dark"
            + "|the .{1,30} is closed|the .{1,30} (fends|blocks|bars)).*");
    private static final Pattern NEGATIVE = Pattern.compile(
            "(?i)\\b(can't|cannot|can not|won't|isn't|aren't|doesn't|don't|not|nothing|no)\\b");
    private static final double VOLATILE_SHARE = 0.2;
    private static final int MAX_CHANGES = 4;
    private static final int MIN_COUNTER_RISES = 3;
    private static final Pattern NO_EFFECT_EXPECTED = Pattern.compile(
            "^(wait|z|look|l|examine|x|read|listen|smell|inventory|i|again|g|diagnose|score)\\b.*");
    private static final Pattern WAIT_OR_FIGHT = Pattern.compile("^(wait|z|kill|attack|fight|hit|stab|slay)\\b.*");
    private static final Pattern FIGHT = Pattern.compile("^(kill|attack|fight|hit|stab|slay)\\b.*");
    private static final Pattern DEFEATED = Pattern.compile("(?s).*\\b(dies|dead|breathes? (his|her|its) last"
            + "|carcass|disappears|disappeared|vanishes|flees|unconscious|fatal|corpse)\\b.*");
    private static final Pattern PLAYER_DIED = Pattern.compile("(?is).*(you have died|you are dead"
            + "|\\*\\*\\*\\s+you have).*");
    private static final Set<String> NOTE_STOPS = new LinkedHashSet<>(Arrays.asList("till", "until", "keep",
            "doing", "the", "you", "you're", "there", "here", "nobody", "is", "if", "shows", "appears",
            "reappears", "disappear", "disappears", "he's", "she's", "it's", "and", "a", "an", "back"));

    public final List<Step> steps;
    /** Exits found by trying every direction in each room the walkthrough visits: room -> (move -> room). */
    public final Map<String, Map<String, String>> exits;
    /** For each state field, the steps that change it, in order. */
    private final Map<Integer, int[]> changers = new HashMap<>();

    WalkthroughIndex(List<Step> steps) {
        this(steps, new LinkedHashMap<>());
    }

    WalkthroughIndex(List<Step> steps, Map<String, Map<String, String>> exits) {
        this.steps = Collections.unmodifiableList(steps);
        this.exits = Collections.unmodifiableMap(exits);
        Map<Integer, List<Integer>> lists = new HashMap<>();
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            if (step.toRoom != null) {
                continue;
            }
            for (int field : step.fields) {
                lists.computeIfAbsent(field, k -> new ArrayList<>()).add(i);
            }
        }
        for (Map.Entry<Integer, List<Integer>> entry : lists.entrySet()) {
            int[] at = new int[entry.getValue().size()];
            for (int i = 0; i < at.length; i++) {
                at[i] = entry.getValue().get(i);
            }
            changers.put(entry.getKey(), at);
        }
    }

    /** Number of steps that are goals. */
    public int goalCount() {
        int n = 0;
        for (Step step : steps) {
            n += step.isGoal() ? 1 : 0;
        }
        return n;
    }

    /**
     * True if the player's state already reflects step {@code index}: every field it
     * changes holds its value, or the value a later walkthrough step gives it.
     */
    public boolean isDone(int index, int[] state) {
        Step step = steps.get(index);
        for (int f = 0; f < step.fields.length; f++) {
            int field = step.fields[f];
            if (state == null || field >= state.length) {
                return true;
            }
            if (state[field] == step.values[f]) {
                continue;
            }
            boolean later = false;
            for (int at : changers.get(field)) {
                if (at > index && valueAt(at, field) == state[field]) {
                    later = true;
                    break;
                }
            }
            if (!later) {
                return false;
            }
        }
        return true;
    }

    private int valueAt(int index, int field) {
        Step step = steps.get(index);
        for (int f = 0; f < step.fields.length; f++) {
            if (step.fields[f] == field) {
                return step.values[f];
            }
        }
        return Integer.MIN_VALUE;
    }

    /** The first goal not yet done, or -1 if all are. */
    public int nextGoal(int[] state) {
        return nextGoal(state, java.util.Collections.<Integer>emptySet());
    }

    /** The first goal not yet done and not in {@code skip}, or -1. */
    public int nextGoal(int[] state, java.util.Set<Integer> skip) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).isGoal() && !skip.contains(i) && !isDone(i, state)) {
                return i;
            }
        }
        return -1;
    }

    // ---- Building -------------------------------------------------------------------

    /** Raw replay result before volatile fields are filtered out. */
    static final class Run {
        final List<String> commands = new ArrayList<>();
        final List<String> rooms = new ArrayList<>();
        final List<String> toRooms = new ArrayList<>();
        final List<Boolean> returns = new ArrayList<>();
        final Map<String, Map<String, String>> exits = new LinkedHashMap<>();
        final List<int[]> changed = new ArrayList<>();
        final List<int[]> values = new ArrayList<>();
        final List<String> replies = new ArrayList<>();
        boolean died;
        /** Failed attempts, for diagnostics. */
        final List<String> retries = new ArrayList<>();

        int score() {
            int n = 0;
            for (int i = 0; i < changed.size(); i++) {
                n += toRooms.get(i) == null && changed.get(i).length > 0 ? 1 : 0;
            }
            return n + (died ? 0 : changed.size());
        }
    }

    /**
     * Replays the steps once on {@code player}. A step that fails (an error reply, a move
     * that doesn't move, or death) is retried from a snapshot, since random events such as
     * fights and spinning rooms may need another try: from the snapshot before the step, then
     * from up to eight steps earlier, then after letting time pass (repeating the wait or attack
     * before it, or waiting). A step that still kills the player is skipped. Stops if the player dies anyway.
     */
    static Run replay(List<WalkthroughParser.Step> walkthrough, Player player) {
        Run run = new Run();
        List<Object> snapshots = new ArrayList<>();
        List<int[]> states = new ArrayList<>();
        List<String> roomsBefore = new ArrayList<>();
        int[] attempts = new int[walkthrough.size()];
        Map<Integer, Object[]> original = new HashMap<>();
        snapshots.add(player.snapshot());
        states.add(player.state());
        roomsBefore.add(player.room());
        int i = 0;
        while (i < walkthrough.size()) {
            WalkthroughParser.Step step = walkthrough.get(i);
            String room = roomsBefore.get(i);
            if (room != null && !run.exits.containsKey(room)) {
                explore(player, room, run.exits);
            }
            String reply = send(step, player);
            String now = player.room();
            boolean died = PLAYER_DIED.matcher(reply).matches() || reply.trim().isEmpty();
            boolean failed = died || failed(step.command, reply, room, now, states.get(i), player.state());
            boolean repeating = i > 0 && attempts[i] < MAX_RETRIES;
            if (failed && (attempts[i] < REWIND_TRIES || repeating || (died && attempts[i] < MAX_DEATH_RETRIES))) {
                int attempt = ++attempts[i];
                run.retries.add(i + " " + attempt + " [" + room + "] " + step.command + " -> " + now + ": "
                        + reply.replace('\n', ' '));
                if (!repeating && attempt > REWIND_TRIES) {
                    // Out of ideas, but don't accept a random death: replay the step as it was.
                    restoreOriginal(i, original, snapshots, states, roomsBefore);
                    rewind(player, snapshots, states, roomsBefore, run, i);
                } else if (attempt <= REWIND_TRIES || i == 0) {
                    // Two tries from each of the last few snapshots: a random effect such as
                    // the grue repellent wearing off may have been decided several steps earlier.
                    int back = Math.min(REWIND_BACK[attempt - 1], i);
                    rewind(player, snapshots, states, roomsBefore, run, i - back);
                    i -= back;
                } else {
                    // Let time pass, repeating the previous wait or attack, or just waiting, then
                    // try this step again: timed puzzles may need it.
                    rewind(player, snapshots, states, roomsBefore, run, i);
                    if (!original.containsKey(i)) {
                        original.put(i, new Object[] {snapshots.get(i), states.get(i), roomsBefore.get(i)});
                    }
                    WalkthroughParser.Step previous = walkthrough.get(i - 1);
                    send(WAIT_OR_FIGHT.matcher(previous.command).matches() ? previous : WAIT, player);
                    snapshots.set(i, player.snapshot());
                    states.set(i, player.state());
                    roomsBefore.set(i, player.room());
                }
                continue;
            }
            if (failed && original.containsKey(i) && !died) {
                // The repeats didn't help, so go back to playing the step once.
                restoreOriginal(i, original, snapshots, states, roomsBefore);
                rewind(player, snapshots, states, roomsBefore, run, i);
                reply = send(step, player);
                now = player.room();
                died = PLAYER_DIED.matcher(reply).matches() || reply.trim().isEmpty();
                failed = died || failed(step.command, reply, room, now, states.get(i), player.state());
            }
            if (died) {
                if (snapshots.get(i) == null) {
                    run.died = true;
                    break;
                }
                // The step kills the player however it's tried, so the walkthrough is out of
                // step with this game here: skip it.
                restoreOriginal(i, original, snapshots, states, roomsBefore);
                rewind(player, snapshots, states, roomsBefore, run, i);
                reply = "(skipped: fatal) " + reply;
                now = room;
            }
            int[] before = states.get(i);
            int[] after = died ? before : player.state();
            List<Integer> fields = new ArrayList<>();
            // A step that never worked here isn't something to hint at.
            for (int f = 0; !failed && f < Math.min(before.length, after.length); f++) {
                if (before[f] != after[f]) {
                    fields.add(f);
                }
            }
            int[] changed = new int[fields.size()];
            int[] values = new int[fields.size()];
            for (int k = 0; k < changed.length; k++) {
                changed[k] = fields.get(k);
                values[k] = after[changed[k]];
            }
            run.commands.add(step.command);
            run.rooms.add(room);
            String toRoom = now != null && !now.equals(room) && !failed ? now : null;
            run.toRooms.add(toRoom);
            run.returns.add(toRoom != null && room != null && returnsTo(player, step.command, room));
            run.changed.add(changed);
            run.values.add(values);
            run.replies.add(reply);
            i++;
            snapshots.add(player.snapshot());
            states.add(after);
            roomsBefore.add(now);
        }
        return run;
    }

    // Undoes the extra waits or attacks added before step i.
    private static void restoreOriginal(int i, Map<Integer, Object[]> original, List<Object> snapshots,
                                        List<int[]> states, List<String> roomsBefore) {
        Object[] saved = original.remove(i);
        if (saved != null) {
            snapshots.set(i, saved[0]);
            states.set(i, (int[]) saved[1]);
            roomsBefore.set(i, (String) saved[2]);
        }
    }

    // Restores the game to just before step {@code to} and forgets later records.
    private static void rewind(Player player, List<Object> snapshots, List<int[]> states,
                               List<String> roomsBefore, Run run, int to) {
        player.restore(snapshots.get(to));
        while (snapshots.size() > to + 1) {
            snapshots.remove(snapshots.size() - 1);
            states.remove(states.size() - 1);
            roomsBefore.remove(roomsBefore.size() - 1);
        }
        while (run.commands.size() > to) {
            int last = run.commands.size() - 1;
            run.commands.remove(last);
            run.rooms.remove(last);
            run.toRooms.remove(last);
            run.returns.remove(last);
            run.changed.remove(last);
            run.values.remove(last);
            run.replies.remove(last);
        }
    }

    /**
     * Maps every room reachable from {@code start} that isn't mapped yet, walking to each from
     * a snapshot and trying every direction there, then puts the game back as it was. Players
     * wander off the walkthrough's path, and hints need a way back from wherever they are.
     */
    private static void explore(Player player, String start, Map<String, Map<String, String>> exits) {
        Object base = player.snapshot();
        Map<String, List<String>> pathTo = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        pathTo.put(start, new ArrayList<>());
        queue.add(start);
        while (!queue.isEmpty() && exits.size() < MAX_MAPPED_ROOMS) {
            String id = queue.poll();
            List<String> path = pathTo.get(id);
            if (!exits.containsKey(id)) {
                player.restore(base);
                for (String move : path) {
                    player.send(move);
                }
                if (!id.equals(player.room())) {
                    continue;
                }
                exits.put(id, probeExits(player, id));
            }
            for (Map.Entry<String, String> exit : exits.get(id).entrySet()) {
                if (!pathTo.containsKey(exit.getValue())) {
                    List<String> longer = new ArrayList<>(path);
                    longer.add(exit.getKey());
                    pathTo.put(exit.getValue(), longer);
                    queue.add(exit.getValue());
                }
            }
        }
        player.restore(base);
    }

    // Tries every direction from {@code room}, undoing each, and returns where they lead.
    private static Map<String, String> probeExits(Player player, String room) {
        Map<String, String> found = new LinkedHashMap<>();
        Object snapshot = player.snapshot();
        for (String direction : PROBES) {
            String reply = player.send(direction);
            String now = player.room();
            if (now != null && !now.equals(room) && !PLAYER_DIED.matcher(reply).matches()
                    && !DARK.matcher(reply).matches()) {
                found.put(direction.toUpperCase(Locale.ROOT), now);
            }
            player.restore(snapshot);
        }
        return found;
    }

    // Tries the opposite of a move, notes whether it leads back to {@code room}, then undoes it.
    private static boolean returnsTo(Player player, String command, String room) {
        String back = HintAdvisor.opposite(command);
        if (back == null) {
            return false;
        }
        Object snapshot = player.snapshot();
        player.send(back);
        boolean returned = room.equals(player.room());
        player.restore(snapshot);
        return returned;
    }

    static boolean failed(String command, String reply, String before, String after,
                          int[] stateBefore, int[] stateAfter) {
        if (FAILURE.matcher(reply).matches()) {
            return true;
        }
        if (NO_EFFECT_EXPECTED.matcher(command).matches()) {
            return false;
        }
        if (DIRECTION.matcher(command).matches() && (before == null || !before.equals(after))) {
            return false;
        }
        // A step that changed no object (and, for a move, didn't change the room name, since
        // different rooms can share one) didn't work if the game said so in any words. A
        // positive reply may have changed only globals, such as a timer ("spray repellent").
        if (!DIRECTION.matcher(command).matches() && !NEGATIVE.matcher(reply).find()) {
            return false;
        }
        int objectFields = Math.min(stateBefore.length, stateAfter.length) - GameStateReader.GLOBALS_FROM_END;
        for (int f = 0; f < objectFields; f++) {
            if (stateBefore[f] != stateAfter[f]) {
                return false;
            }
        }
        return true;
    }

    // Sends a step, repeating it while its note asks ("wait (till the man appears)").
    private static String send(WalkthroughParser.Step step, Player player) {
        boolean fight = step.repeats() && FIGHT.matcher(step.command).matches();
        List<String> keys = new ArrayList<>();
        if (step.repeats()) {
            for (String word : step.note.split("[^a-z0-9'\"]+")) {
                word = word.replace("\"", "");
                if (word.length() >= 2 && !NOTE_STOPS.contains(word)) {
                    keys.add(word);
                }
            }
        }
        String reply = player.send(step.command);
        for (int i = 1; i < MAX_REPEAT && step.repeats(); i++) {
            String lower = reply.toLowerCase(Locale.ROOT);
            if (PLAYER_DIED.matcher(reply).matches() || (fight && DEFEATED.matcher(lower).matches())) {
                break;
            }
            boolean found = !keys.isEmpty();
            for (String key : keys) {
                found &= Pattern.compile("\\b" + Pattern.quote(key) + "\\b").matcher(lower).find();
            }
            if (found || (!fight && keys.isEmpty() && i >= 3)) {
                break;
            }
            reply = player.send(step.command);
        }
        return reply;
    }
    /** Builds the index from a replay, dropping fields that change too often to mean progress. */
    static WalkthroughIndex fromRun(Run run, int fieldCount) {
        int[] counts = new int[fieldCount];
        int[] goalCounts = new int[fieldCount];
        for (int i = 0; i < run.changed.size(); i++) {
            for (int field : run.changed.get(i)) {
                counts[field]++;
                if (run.toRooms.get(i) == null) {
                    goalCounts[field]++;
                }
            }
        }
        int limit = (int) Math.ceil(run.changed.size() * VOLATILE_SHARE);
        // Globals that only ever go up (the score) depend on what else the player did first.
        int[] last = new int[fieldCount];
        int[] rises = new int[fieldCount];
        boolean[] fell = new boolean[fieldCount];
        for (int i = 0; i < run.changed.size(); i++) {
            int[] changed = run.changed.get(i);
            for (int f = 0; f < changed.length; f++) {
                int field = changed[f];
                int value = run.values.get(i)[f];
                if (rises[field] > 0 && value < last[field]) {
                    fell[field] = true;
                }
                rises[field]++;
                last[field] = value;
            }
        }
        for (int field = Math.max(0, fieldCount - GameStateReader.GLOBALS_FROM_END); field < fieldCount; field++) {
            if (rises[field] >= MIN_COUNTER_RISES && !fell[field]) {
                counts[field] = Integer.MAX_VALUE;
            }
        }
        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < run.changed.size(); i++) {
            int[] changed = run.changed.get(i);
            int[] values = run.values.get(i);
            int kept = 0;
            for (int field : changed) {
                kept += keep(field, counts, goalCounts, limit, fieldCount) ? 1 : 0;
            }
            int[] fields = new int[kept];
            int[] vals = new int[kept];
            for (int f = 0, k = 0; f < changed.length; f++) {
                if (keep(changed[f], counts, goalCounts, limit, fieldCount)) {
                    fields[k] = changed[f];
                    vals[k++] = values[f];
                }
            }
            steps.add(new Step(run.commands.get(i), run.rooms.get(i), run.toRooms.get(i), run.returns.get(i),
                    fields, vals));
        }
        return new WalkthroughIndex(steps, run.exits);
    }

    // Counters (score, turns, timers) change on many steps; object parents are kept unless volatile.
    private static boolean keep(int field, int[] counts, int[] goalCounts, int limit, int fieldCount) {
        if (counts[field] > limit) {
            return false;
        }
        boolean global = field >= fieldCount - GameStateReader.GLOBALS_FROM_END;
        return !global || goalCounts[field] <= MAX_CHANGES;
    }

    /** Factory for fresh hidden games, one per attempt with a different random seed. */
    public interface PlayerFactory {
        Player create(int seed) throws Exception;
    }

    /** Replays with a few seeds and keeps the run that gets furthest. */
    public static WalkthroughIndex build(List<WalkthroughParser.Step> walkthrough, PlayerFactory factory,
                                         int attempts) throws Exception {
        Run best = null;
        int fieldCount = 0;
        for (int seed = 1; seed <= attempts; seed++) {
            Player player = factory.create(seed);
            fieldCount = player.state().length;
            Run run = replay(walkthrough, player);
            if (best == null || run.score() > best.score()) {
                best = run;
            }
            if (!run.died && run.changed.size() == walkthrough.size()) {
                break;
            }
        }
        return best == null ? new WalkthroughIndex(new ArrayList<>()) : fromRun(best, fieldCount);
    }

    // ---- Cache format: one tab-separated line per step, then one per probed exit -------

    private static final String EXIT_LINE = "@exit";

    public String serialize() {
        StringBuilder sb = new StringBuilder("walkthrough-index\t").append(FORMAT).append('\n');
        for (Step step : steps) {
            sb.append(clean(step.command)).append('\t').append(clean(step.room)).append('\t')
                    .append(clean(step.toRoom)).append('\t').append(step.returns ? "1" : "").append('\t');
            for (int f = 0; f < step.fields.length; f++) {
                sb.append(f > 0 ? "," : "").append(step.fields[f]).append(':').append(step.values[f]);
            }
            sb.append('\n');
        }
        for (Map.Entry<String, Map<String, String>> room : exits.entrySet()) {
            for (Map.Entry<String, String> exit : room.getValue().entrySet()) {
                sb.append(EXIT_LINE).append('\t').append(clean(room.getKey())).append('\t')
                        .append(exit.getKey()).append('\t').append(clean(exit.getValue())).append('\n');
            }
        }
        return sb.toString();
    }

    /** Returns null if the text isn't a current-format index. */
    public static WalkthroughIndex deserialize(String text) {
        if (text == null) {
            return null;
        }
        String[] lines = text.split("\n");
        if (lines.length == 0 || !lines[0].equals("walkthrough-index\t" + FORMAT)) {
            return null;
        }
        List<Step> steps = new ArrayList<>();
        Map<String, Map<String, String>> exits = new LinkedHashMap<>();
        try {
            for (int i = 1; i < lines.length; i++) {
                String[] parts = lines[i].split("\t", -1);
                if (parts.length == 4 && parts[0].equals(EXIT_LINE)) {
                    exits.computeIfAbsent(parts[1], k -> new LinkedHashMap<>()).put(parts[2], parts[3]);
                    continue;
                }
                if (parts.length != 5) {
                    return null;
                }
                String[] pairs = parts[4].isEmpty() ? new String[0] : parts[4].split(",");
                int[] fields = new int[pairs.length];
                int[] values = new int[pairs.length];
                for (int p = 0; p < pairs.length; p++) {
                    String[] kv = pairs[p].split(":");
                    fields[p] = Integer.parseInt(kv[0]);
                    values[p] = Integer.parseInt(kv[1]);
                }
                steps.add(new Step(parts[0], empty(parts[1]), empty(parts[2]), parts[3].equals("1"), fields, values));
            }
        } catch (RuntimeException e) {
            return null;
        }
        return new WalkthroughIndex(steps, exits);
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ');
    }

    private static String empty(String s) {
        return s.isEmpty() ? null : s;
    }
}

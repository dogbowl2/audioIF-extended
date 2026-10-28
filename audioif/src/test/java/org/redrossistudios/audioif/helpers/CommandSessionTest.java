package org.redrossistudios.audioif.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CommandSessionTest {

    /** A tiny Zork-like world: Field -N-> Path -N-> Clearing, Path -E-> Hut. */
    private static final class FakeGame implements CommandSession.Game {
        final Set<String> words = new HashSet<>(Arrays.asList(
                "n", "s", "e", "w", "north", "south", "east", "west", "take", "open", "drop", "examine",
                "the", "lamp", "box", "leaflet", "turn", "unlock", "door", "key", "with", "look", "in", "i",
                "z", "read", "it", "what", "out", "exit", "u", "d", "put", "x", "pull"));
        final Map<String, Map<String, String>> exits = new HashMap<>();
        final Map<String, String> replies = new HashMap<>();
        final List<String> sent = new ArrayList<>();
        String room = "Field";

        FakeGame() {
            exit("Field", "N", "Path");
            exit("Path", "S", "Field");
            exit("Path", "N", "Clearing");
            exit("Clearing", "S", "Path");
            exit("Path", "E", "Hut");
            exit("Hut", "W", "Path");
            exit("Field", "IN", "Shed");
            exit("Shed", "OUT", "Field");
            exit("Shed", "EXIT", "Field");
            replies.put("open the box", "Opening the box reveals a leaflet.");
            replies.put("take the leaflet", "Taken.");
            replies.put("take the lamp", "Taken.");
            replies.put("read the leaflet", "WELCOME!");
            replies.put("read it", "I don't see what you are referring to.");
            replies.put("z", "Time passes...");
        }

        void exit(String from, String direction, String to) {
            exits.computeIfAbsent(from, k -> new HashMap<>()).put(direction, to);
        }

        @Override
        public String send(String command) {
            sent.add(command);
            String upper = command.toUpperCase();
            if (upper.matches("N|S|E|W|U|D|IN|OUT|EXIT")) {
                String to = exits.getOrDefault(room, new HashMap<>()).get(upper);
                if (to == null) {
                    return "You can't go that way.";
                }
                room = to;
                return to + "\nYou are in the " + to.toLowerCase() + ".";
            }
            if (replies.containsKey(command)) {
                return replies.get(command);
            }
            for (String word : command.toLowerCase().split(" ")) {
                if (!words.contains(word)) {
                    return "I don't know the word \"" + word + "\".";
                }
            }
            return "You can't see any such thing.";
        }

        @Override
        public List<String> dictionaryWords() {
            return new ArrayList<>(words);
        }

        @Override
        public String currentRoom() {
            return room;
        }

        int[] state;

        @Override
        public int[] stateVector() {
            return state;
        }

        @Override
        public Object snapshot() {
            return room;
        }

        @Override
        public boolean restore(Object snapshot) {
            room = (String) snapshot;
            return true;
        }

        @Override
        public boolean contains(String word) {
            return words.contains(word);
        }

        List<String> things = new ArrayList<>();
        List<String> carried = new ArrayList<>();

        @Override
        public List<String> carried() {
            return carried;
        }

        @Override
        public List<String> thingsHere() {
            return room.equals("Field") ? things : new ArrayList<>();
        }
    }

    private FakeGame game;
    private CommandSession session;

    @Before
    public void setUp() {
        game = new FakeGame();
        session = new CommandSession(game);
        session.start("Field\nYou are in a field. There is a lamp here. A narrow trail leads north.");
    }

    @Test
    public void normalizesBeforeSending() {
        session.handle("please go north");
        assertEquals(Arrays.asList("N"), game.sent);
    }

    @Test
    public void splitsCompoundCommands() {
        CommandSession.Result result = session.handle("open the box and take the leaflet. go north");
        assertEquals(Arrays.asList("open the box", "take the leaflet", "N"), result.sentCommands());
    }

    @Test
    public void keepsObjectListsTogether() {
        assertEquals(Arrays.asList("take the lamp and the leaflet"),
                session.splitClauses("take the lamp and the leaflet"));
        assertEquals(Arrays.asList("take the lamp", "go north"),
                session.splitClauses("take the lamp, then go north"));
    }

    @Test
    public void stopsAfterRejectedClause() {
        CommandSession.Result result = session.handle("frobnicate the box and go north");
        assertTrue(result.rejected);
        assertEquals(1, result.sentCommands().size());
    }

    @Test
    public void repeatsTwice() {
        session.handle("go north twice");
        assertEquals(Arrays.asList("N", "N"), game.sent);
        assertEquals("Clearing", game.room);
    }

    @Test
    public void goBackReversesLastMove() {
        session.handle("go north");
        session.handle("go east");
        session.handle("go back");
        assertEquals("Path", game.room);
    }

    @Test
    public void keepGoingRepeatsLastDirection() {
        session.handle("go north");
        session.handle("keep going");
        assertEquals("Clearing", game.room);
    }

    @Test
    public void goToWalksRememberedRoute() {
        session.handle("go north");
        session.handle("go north");
        CommandSession.Result result = session.handle("go to the field");
        assertEquals("Field", game.room);
        assertTrue(result.text().contains("You walk to Field."));
        assertFalse(result.text().contains("I don't know the word"));
    }

    @Test
    public void goToUnknownPlaceExplains() {
        CommandSession.Result result = session.handle("go to the castle");
        assertTrue(result.text().contains("I don't know a place called castle"));
    }

    @Test
    public void undoRestoresPreviousTurn() {
        session.handle("go north");
        CommandSession.Result result = session.handle("undo");
        assertEquals("Field", game.room);
        assertTrue(result.text().contains("Took back: go north. You are in Field."));
    }

    @Test
    public void undoWithNothingToUndo() {
        assertEquals("There is nothing to undo.", session.handle("undo").text());
    }

    @Test
    public void helpAndHintAreAnsweredByTheApp() {
        assertEquals(CommandSession.HELP_TEXT, session.handle("how do I play").text());
        assertEquals(CommandSession.HELP_TEXT, session.handle("commands").text());
        assertEquals(CommandSession.HINT_TEXT, session.handle("what should I do now?").text());
        assertTrue(session.handle("Help").text().startsWith("You're at Field."));
        assertTrue(game.sent.isEmpty());
    }

    @Test
    public void helpDescribesTheSituation() {
        game.things = Arrays.asList("cretin", "brass lamp", "hidden key");
        assertEquals("You're at Field. You can see the brass lamp. You can go north. "
                        + "You could try: turn on the lamp or go north. Say how do I play to learn more commands.",
                session.handle("help").text());
        session.handle("turn on the lamp");
        assertEquals("You're at Field. You can see the brass lamp. You can go north. "
                        + "You could try: examine the lamp or go north. Say how do I play to learn more commands.",
                session.handle("what can I do").text());
    }

    @Test
    public void nudgesTowardHelpAfterTwoRejections() {
        assertFalse(session.handle("frobnicate").text().contains(CommandSession.STUCK_NOTE));
        assertTrue(session.handle("frobnicate").text().contains(CommandSession.STUCK_NOTE));
        assertFalse(session.handle("frobnicate").text().contains(CommandSession.STUCK_NOTE));
    }

    @Test
    public void speechFixesMisheardGameWords() {
        session.handle("open the box");
        CommandSession.Result result = session.handleSpeech(Arrays.asList("reed the leaf lit"));
        assertEquals("read the leaflet", result.heard);
        assertEquals("read the leaflet", game.sent.get(game.sent.size() - 1));
        session.handleSpeech(Arrays.asList("go nor"));
        assertEquals("Path", game.room);
    }

    @Test
    public void speechPrefersAnAlternativeTheGameKnows() {
        session.handleSpeech(Arrays.asList("take the lump zz", "take the lamp"));
        assertEquals("take the lamp", game.sent.get(0));
    }

    @Test
    public void speechSaysWhatItHeardWhenRejected() {
        CommandSession.Result result = session.handleSpeech(Arrays.asList("frobnicate"));
        assertTrue(result.rejected);
        assertTrue(result.text().contains("I heard: frobnicate."));
    }

    @Test
    public void speechRecognizesHelpAmongAlternatives() {
        CommandSession.Result result = session.handleSpeech(Arrays.asList("kelp me out", "help"));
        assertTrue(result.text().startsWith("You're at Field."));
        assertTrue(game.sent.isEmpty());
    }

    @Test
    public void hintGivesWalkthroughHintsOnceLoaded() {
        session.setHintsLoading(true);
        assertEquals(CommandSession.HINTS_LOADING, session.handle("hint").text());

        game.state = new int[] {0};
        session.setHintAdvisor(new HintAdvisor(new WalkthroughIndex(java.util.Arrays.asList(
                new WalkthroughIndex.Step("n", "Field", "Path", new int[0], new int[0]),
                new WalkthroughIndex.Step("open box", "Path", null, new int[] {0}, new int[] {1})))));
        assertEquals("Your next step is somewhere else, at Path. Say hint again for directions.",
                session.handle("help me").text());
        assertEquals("From here, go north. Say hint again to hear what to do there.",
                session.handle("hint").text());
        session.handle("n");
        assertEquals("Try: open box.", session.handle("I'm stuck").text());
        assertEquals(CommandSession.HELP_TEXT, session.handle("how do I play").text());
        assertEquals(Arrays.asList("n"), game.sent);
    }

    @Test
    public void runningAwayGoesBack() {
        session.handle("go north");
        session.handle("run away from the troll");
        assertEquals(Arrays.asList("N", "S"), game.sent);
        assertEquals("Field", game.room);
    }

    @Test
    public void stopPausesListening() {
        CommandSession.Result result = session.handleSpeech(Arrays.asList("stop"));
        assertTrue(result.pauseListening);
        assertTrue(game.sent.isEmpty());
    }

    @Test
    public void loneHintMishearingsMeanHint() {
        CommandSession.Result result = session.handleSpeech(Arrays.asList("tent", "can't", "tint"));
        assertEquals(CommandSession.HINT_TEXT, result.text());
        assertTrue(game.sent.isEmpty());
    }

    @Test
    public void swingingASwordAtACreatureAttacksIt() {
        game.words.addAll(Arrays.asList("swing", "sword", "troll", "attack"));
        CommandSession fight = new CommandSession(game);
        fight.start("Field\nA nasty-looking troll blocks the way.");
        String text = fight.handle("swing the sword").text();
        assertEquals(Arrays.asList("attack the troll with the sword"), game.sent);
        assertTrue(text, text.contains("To fight, say attack the troll with the sword."));
    }

    @Test
    public void useAThingSuggestsWhatItIsFor() {
        game.words.addAll(Arrays.asList("on", "off"));
        CommandSession.Result result = session.handle("use the lamp");
        assertEquals("turn on the lamp", result.suggestions.get(0));
    }

    @Test
    public void examiningTheRoomLooks() {
        game.replies.put("look", "Field\nYou are in a field.");
        session.handle("examine the room");
        assertEquals(Arrays.asList("look"), game.sent);
    }

    @Test
    public void goToWorksForTheRoomTheHintNamed() {
        game.state = new int[] {0};
        session.setHintAdvisor(new HintAdvisor(new WalkthroughIndex(Arrays.asList(
                new WalkthroughIndex.Step("n", "Field", "Path", new int[0], new int[0]),
                new WalkthroughIndex.Step("open box", "Path", null, new int[] {0}, new int[] {1})))));
        session.handle("hint");
        session.handle("go to the path");
        assertEquals("Path", game.room);
    }

    @Test
    public void hintSkipsAStepTheGameSaysIsAlreadyDone() {
        game.words.addAll(Arrays.asList("get", "egg"));
        game.replies.put("take the egg", "You already have that!");
        game.state = new int[] {0};
        session.setHintAdvisor(new HintAdvisor(new WalkthroughIndex(Arrays.asList(
                new WalkthroughIndex.Step("get egg", "Field", null, new int[] {0}, new int[] {1}),
                new WalkthroughIndex.Step("open box", "Field", null, new int[] {0}, new int[] {2})))));
        session.handle("hint");
        session.handle("hint");
        assertEquals("Try: get egg.", session.handle("hint").text());
        session.handle("take the egg");
        assertTrue(session.handle("hint").text().startsWith(HintAdvisor.SKIPPED));
    }

    @Test
    public void speechDoesNotTurnGameVerbsIntoHint() {
        game.words.addAll(Arrays.asList("hang", "rope"));
        session.handleSpeech(Arrays.asList("hang the rope"));
        assertEquals("hang the rope", game.sent.get(0));
    }

    @Test
    public void followUpNoteMatchesTheGamesQuestion() {
        game.words.addAll(Arrays.asList("tie", "rope", "railing"));
        game.replies.put("tie the rope", "What do you want to tie the rope to?");
        game.things.add("railing");
        String text = session.handle("tie the rope").text();
        assertTrue(text, text.contains("like tie the rope to the lamp"));
        assertFalse(text, text.contains("with the item"));
    }

    @Test
    public void helpAfterResumeLooksAtTheRoomFirst() {
        game.replies.put("look", "Field\nYou are in a field. There is a lamp here. A narrow trail leads north.");
        CommandSession resumed = new CommandSession(game);
        resumed.start("There's nothing special about the canary.");
        String text = resumed.handle("help").text();
        assertTrue(text, text.contains("the lamp"));
        assertEquals(Arrays.asList("look"), game.sent);
    }

    @Test
    public void pronounFallsBackToLastNoun() {
        session.handle("open the box");
        CommandSession.Result result = session.handle("read it");
        assertEquals("read the leaflet", game.sent.get(game.sent.size() - 1));
        assertTrue(result.text().contains("WELCOME!"));
        assertFalse(result.text().contains("referring to"));
    }

    @Test
    public void questionsGetAnExplanation() {
        CommandSession.Result result = session.handle("why is the sky blue?");
        assertTrue(result.text().contains(CommandSession.QUESTION_NOTE));
    }

    @Test
    public void useXOnYOffersRealVerbs() {
        CommandSession.Result result = session.handle("use the key on the door");
        assertEquals(Arrays.asList("unlock the door with the key", "open the door with the key",
                "put the key in the door"),
                result.suggestions);
        session.handle("one");
        assertEquals("unlock the door with the key", game.sent.get(game.sent.size() - 1));
    }

    @Test
    public void numberPicksPendingSuggestionAndSomethingElseAsksAgain() {
        session.handle("take the lamq");
        assertTrue(session.hasPendingSuggestions());
        assertEquals(CommandSession.ASK_AGAIN, session.handle("something else").text());
        session.handle("take the lamq");
        session.handle("one");
        assertEquals("take the lamp", game.sent.get(game.sent.size() - 1));
    }

    @Test
    public void newCommandIgnoresPendingSuggestions() {
        session.handle("take the lamq");
        session.handle("go north");
        assertEquals("N", game.sent.get(game.sent.size() - 1));
        assertFalse(session.hasPendingSuggestions());
    }

    @Test
    public void detectsRoomNameHeadings() {
        assertEquals("West of House", CommandSession.roomFromResponse("West of House\nYou are standing.", true));
        assertEquals(null, CommandSession.roomFromResponse("Taken.", true));
    }

    @Test
    public void goBackReversesNonCompassMoves() {
        session.handle("go inside");
        assertEquals("Shed", game.room);
        session.handle("go back");
        assertEquals("Field", game.room);
        assertEquals(Arrays.asList("in", "OUT"), game.sent);
    }

    @Test
    public void leaveTheCurrentRoomExits() {
        session.handle("go inside");
        session.handle("leave the shed");
        assertEquals("exit", game.sent.get(game.sent.size() - 1));
        assertEquals("Field", game.room);
    }

    @Test
    public void landmarkInRoomDescriptionGivesDirection() {
        CommandSession.Result result = session.handle("follow the narrow trail");
        assertEquals("Path", game.room);
        assertFalse(result.rejected);
        assertTrue(result.text().contains("I took that to mean go north"));
    }

    @Test
    public void goToLandmarkSeenInAnotherRoom() {
        session.handle("go north");
        CommandSession.Result result = session.handle("go to the trail");
        assertEquals("Field", game.room);
        assertFalse(result.rejected);
        assertTrue(result.text().contains("You walk to Field"));
    }

    @Test
    public void exitQuestionListsKnownExits() {
        session.handle("go north");
        CommandSession.Result result = session.handle("which way can I go?");
        assertEquals("look", game.sent.get(game.sent.size() - 1));
        assertTrue(result.text(), result.text().contains("south to Field"));
    }

    @Test
    public void chatterAfterCommandIsIgnored() {
        CommandSession.Result result = session.handle("take the lamp, I don't need anything else");
        assertEquals(Arrays.asList("take the lamp"), result.sentCommands());
        assertFalse(result.rejected);
    }

    @Test
    public void lookingDoesNotUseAnUndoStep() {
        session.handle("go north");
        session.handle("look");
        session.handle("take that back");
        assertEquals("Field", game.room);
    }

    @Test
    public void absurdVerbSuggestionsAreFiltered() {
        CommandSession.Result result = session.handle("fry the lamp");
        for (String suggestion : result.suggestions) {
            assertFalse(suggestion, suggestion.startsWith("fry"));
        }
        assertTrue(result.suggestions.isEmpty() || result.suggestions.get(0).startsWith("try") == false);
    }

    @Test
    public void loudRoomEchoIsExplained() {
        game.replies.put("take the lamp", "lamp lamp ...");
        session.handle("go north");
        CommandSession.Result result = session.handle("take the lamp");
        assertFalse(result.rejected);
        assertTrue(result.text(), result.text().contains("Say echo to quiet it, or leave by saying a direction, "
                + "like go south."));
    }

    @Test
    public void darknessNamesTheLightYouCarry() {
        game.replies.put("look", "It is pitch black. You are likely to be eaten by a grue.");
        assertTrue(session.handle("look").text().contains(CommandSession.DARK_NOTE));
        game.carried.add("brass lantern");
        assertTrue(session.handle("look").text().contains("You're carrying the lantern, so say turn on the lantern."));
    }

    @Test
    public void paddedDirectionsMove() {
        session.handle("the north");
        assertEquals("Path", game.room);
        session.handle("no south");
        assertEquals("Field", game.room);
    }

    @Test
    public void findsTheCommandAtTheEndOfARambling() {
        assertEquals("go south", CommandSession.commandInSentence(
                "how about ghosts this is what i said go south"));
        assertEquals("go back into the house", CommandSession.commandInSentence(
                "no no go back into the house do not leave the house"));
        assertEquals(null, CommandSession.commandInSentence("take the lamp"));
        CommandSession.Result result = session.handle("how about ghosts this is what i said go north");
        assertEquals("Path", game.room);
        assertTrue(result.text(), result.text().contains("(I took that as: go north.)"));
    }

    @Test
    public void climbInEntersThings() {
        session.handle("climb in");
        assertEquals("Shed", game.room);
        game.words.add("enter");
        game.words.add("window");
        session.handle("climb in the window");
        assertEquals("enter the window", game.sent.get(game.sent.size() - 1));
    }

    @Test
    public void goWithARoomNameWalksThere() {
        session.handle("go north");
        session.handle("go east");
        session.handle("go west");
        session.handle("go south");
        session.handle("go hut");
        assertEquals("Hut", game.room);
    }

    @Test
    public void goForwardAfterClimbingDownAsks() {
        session.handle("go inside");
        CommandSession.Result result = session.handle("go forward");
        assertEquals("Shed", game.room);
        assertTrue(result.text(), result.text().startsWith("Your last move was in."));
    }

    @Test
    public void sideOfAPlaceIsNotADirection() {
        session.start("Field\nYou are on the north side of a white house.");
        CommandSession.Result result = session.handle("go to the white house");
        assertEquals("Field", game.room);
        assertFalse(result.text(), result.text().contains("I took that to mean go north"));
    }

    @Test
    public void goToANearbyRoomTheWalkthroughKnows() {
        game.state = new int[] {0};
        session.setHintAdvisor(new HintAdvisor(new WalkthroughIndex(Arrays.asList(
                new WalkthroughIndex.Step("n", "Field", "Path", new int[0], new int[0]),
                new WalkthroughIndex.Step("e", "Path", "Hut", new int[0], new int[0]),
                new WalkthroughIndex.Step("open box", "Hut", null, new int[] {0}, new int[] {1})))));
        CommandSession.Result result = session.handle("go back to the hut");
        assertEquals("Hut", game.room);
        assertTrue(result.text(), result.text().contains("You walk to Hut."));
    }

    @Test
    public void hintSkipsSurviveARestart() {
        game.words.addAll(Arrays.asList("get", "egg"));
        game.replies.put("take the egg", "You already have that!");
        game.state = new int[] {0};
        session.setHintAdvisor(new HintAdvisor(new WalkthroughIndex(Arrays.asList(
                new WalkthroughIndex.Step("get egg", "Field", null, new int[] {0}, new int[] {1}),
                new WalkthroughIndex.Step("open box", "Field", null, new int[] {0}, new int[] {2})))));
        session.handle("hint");
        session.handle("hint");
        session.handle("hint");
        session.handle("take the egg");
        String saved = session.hintSkips();
        assertEquals("0", saved);

        CommandSession resumed = new CommandSession(game);
        resumed.start("Field\nYou are in a field.");
        resumed.restoreHintSkips(saved);
        resumed.setHintAdvisor(new HintAdvisor(new WalkthroughIndex(Arrays.asList(
                new WalkthroughIndex.Step("get egg", "Field", null, new int[] {0}, new int[] {1}),
                new WalkthroughIndex.Step("open box", "Field", null, new int[] {0}, new int[] {2})))));
        resumed.handle("hint");
        resumed.handle("hint");
        assertEquals("Try: open box.", resumed.handle("hint").text());
    }

    @Test
    public void speechPrefersWordsForThingsInView() {
        game.words.addAll(Arrays.asList("rope", "road"));
        game.things.add("rope");
        session.start("Field\nA large coil of rope is lying in the corner.");
        session.handleSpeech(Arrays.asList("take the road", "take the rope"));
        assertEquals("take the rope", game.sent.get(0));
    }

    @Test
    public void speechTriesTheNextGuessWhenTheFirstIsRejected() {
        game.words.addAll(Arrays.asList("rope", "railing", "attach", "catch", "to"));
        game.replies.put("attach the rope to the railing", "The rope drops over the side.");
        game.replies.put("catch the rope to the railing", "That sentence isn't one I recognize.");
        CommandSession.Result result = session.handleSpeech(Arrays.asList(
                "catch the rope to the railing", "attach the rope to the railing"));
        assertFalse(result.text(), result.rejected);
        assertEquals("attach the rope to the railing", result.heard);
        assertEquals("attach the rope to the railing", game.sent.get(game.sent.size() - 1));
        assertTrue(result.text(), result.text().contains("The rope drops"));
        assertFalse(result.text(), result.text().contains("recognize"));
    }

    @Test
    public void repeatedHintIsAHint() {
        CommandSession.Result result = session.handleSpeech(Arrays.asList("hint hint", "hint hit"));
        assertEquals("hint", result.heard);
        assertTrue(game.sent.isEmpty());
    }

    @Test
    public void crawlDownIsADirection() {
        game.exit("Field", "D", "Cellar");
        game.words.add("kill");
        session.handleSpeech(Arrays.asList("crawl down", "call down"));
        assertEquals("Cellar", game.room);
    }

    @Test
    public void wordsRunTogetherAreNotGameWords() {
        // Zork compares only the first six letters, so it seems to know "livingroom".
        game.words.addAll(Arrays.asList("go", "to", "living", "livingroom", "room"));
        session.handleSpeech(Arrays.asList("go to the living room", "go to the livingroom"));
        assertFalse(game.sent.toString(), game.sent.contains("go to the livingroom"));
    }

    @Test
    public void roomNameRunTogetherIsStillFound() {
        game.exit("Field", "D", "Living Room");
        game.exit("Living Room", "U", "Field");
        game.words.addAll(Arrays.asList("go", "to"));
        session.handle("go down");
        session.handle("go up");
        session.handle("go to the livingroom");
        assertEquals("Living Room", game.room);
    }

    @Test
    public void notAtAPlaceWalksThere() {
        game.words.addAll(Arrays.asList("go", "to", "hut"));
        game.replies.put("go to the hut", "You're not at the hut.");
        session.handle("go north");
        session.handle("go east");
        session.handle("go west");
        session.handle("go south");
        session.handle("go to the hut");
        assertEquals("Hut", game.room);
    }

    @Test
    public void loudRoomNamesTheExits() {
        game.replies.put("take the lamp", "lamp lamp ...");
        game.replies.put("look", "Path\nA path runs east and west. A ladder leads up.");
        session.handle("go north");
        session.handle("look");
        CommandSession.Result result = session.handle("take the lamp");
        assertTrue(result.text(), result.text().contains("like go east, west or up."));
    }

    @Test
    public void garbledReplyToAMustDoFirstDoesIt() {
        game.words.addAll(Arrays.asList("bottle", "glass", "drink", "water"));
        game.replies.put("drink the water", "You'll have to open the glass bottle first.");
        game.replies.put("in the bottle", "You used the word \"the\" in a way that I don't understand.");
        game.replies.put("open the glass bottle", "Opened.");
        session.handle("drink the water");
        CommandSession.Result result = session.handle("in the bottle");
        assertEquals("open the glass bottle", game.sent.get(game.sent.size() - 1));
        assertTrue(result.text(), result.text().contains("Opened."));
    }
}
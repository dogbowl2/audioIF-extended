package org.redrossistudios.audioif.helpers;

import static org.junit.Assert.assertEquals;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class HintAdvisorTest {

    // Field -n-> Path (open box) -e-> Hut (take lamp); fields 0 = box open, 1 = lamp held.
    private static WalkthroughIndex index() {
        return new WalkthroughIndex(Arrays.asList(
                new WalkthroughIndex.Step("n", "Field", "Path", new int[0], new int[0]),
                new WalkthroughIndex.Step("open box", "Path", null, new int[] {0}, new int[] {1}),
                new WalkthroughIndex.Step("e", "Path", "Hut", new int[0], new int[0]),
                new WalkthroughIndex.Step("take lamp", "Hut", null, new int[] {1}, new int[] {1})));
    }

    private HintAdvisor advisor;

    @Before
    public void setUp() {
        advisor = new HintAdvisor(index());
    }

    @Test
    public void routesToANearbyRoomByName() {
        int[] state = {0, 0};
        assertEquals(Arrays.asList("N", "E"), advisor.routeToRoom("the hut", "Field", state, 4));
        assertEquals(null, advisor.routeToRoom("hut", "Field", state, 1));
        assertEquals(null, advisor.routeToRoom("castle", "Field", state, 4));
    }

    @Test
    public void restoresOnlyRealGoalsAsSkipped() {
        advisor.restoreSkipped(Arrays.asList(1, 0, 99, -1));
        assertEquals(new java.util.HashSet<>(Arrays.asList(1)), advisor.skippedGoals());
    }

    @Test
    public void hintsGetMorePreciseInTheRightRoom() {
        int[] state = {0, 0};
        assertEquals("There's something to do right here. Look around and examine what you see."
                + HintAdvisor.MORE, advisor.hint(state, "Path", null));
        assertEquals("Try doing something with the box. Say hint again to hear exactly what to do.",
                advisor.hint(state, "Path", null));
        assertEquals("Try: open box.", advisor.hint(state, "Path", null));
        assertEquals("Try: open box.", advisor.hint(state, "Path", null));
    }

    @Test
    public void hintsGiveDirectionsFromElsewhere() {
        int[] state = {0, 0};
        assertEquals("Your next step is somewhere else, at Path. Say hint again for directions.",
                advisor.hint(state, "Field", null));
        assertEquals("From here, go north. Say hint again to hear what to do there.",
                advisor.hint(state, "Field", null));
        assertEquals("At Path, try: open box. From here, go north.", advisor.hint(state, "Field", null));
    }

    @Test
    public void nextGoalStartsVagueAgain() {
        advisor.hint(new int[] {0, 0}, "Path", null);
        advisor.hint(new int[] {0, 0}, "Path", null);
        assertEquals("Your next step is somewhere else, at Hut. Say hint again for directions.",
                advisor.hint(new int[] {1, 0}, "Path", null));
        assertEquals("From here, go east. Say hint again to hear what to do there.",
                advisor.hint(new int[] {1, 0}, "Path", null));
    }

    @Test
    public void skipsAGoalThatCanNoLongerBeDone() {
        int[] state = {0, 0};
        advisor.hint(state, "Path", null);
        advisor.hint(state, "Path", null);
        assertEquals("Try: open box.", advisor.hint(state, "Path", null));
        assertEquals(false, advisor.isHintedCommand("take lamp"));
        assertEquals(true, advisor.isHintedCommand("open the box"));
        advisor.skipHintedIfStuck(state);
        assertEquals(HintAdvisor.SKIPPED + "Your next step is somewhere else, at Hut. Say hint again for directions.",
                advisor.hint(state, "Path", null));
        assertEquals("From here, go east. Say hint again to hear what to do there.", advisor.hint(state, "Path", null));
    }

    @Test
    public void knownPlacesUseGoTo() {
        Map<String, Map<String, String>> known = new HashMap<>();
        known.put("Field", new HashMap<>());
        known.get("Field").put("N", "Path");
        known.put("Path", new HashMap<>());
        known.get("Path").put("E", "Hut");
        advisor.hint(new int[] {1, 0}, "Field", known);
        assertEquals("You've been there: say go to Hut. Say hint again to hear what to do there.",
                advisor.hint(new int[] {1, 0}, "Field", known));
    }

    @Test
    public void longerRoutesAndUnknownWays() {
        advisor.hint(new int[] {1, 0}, "Field", null);
        assertEquals("From here, go north, then east. Say hint again to hear what to do there.",
                advisor.hint(new int[] {1, 0}, "Field", null));
        HintAdvisor lost = new HintAdvisor(index());
        lost.hint(new int[] {1, 0}, "Cellar", null);
        assertEquals("I don't know the way from here. Try to retrace your steps. Say hint again to hear what to do there.",
                lost.hint(new int[] {1, 0}, "Cellar", null));
    }

    @Test
    public void routesAvoidExitsThatNeedAnEarlierStep() {
        // Forest -u-> Tree (get egg), Forest -n-> Lane -n-> Hall -e-> Dome (tie rope) -d-> Torch
        // -e-> Temple -pray-> Forest. Fields: 0 = rope tied, 1 = egg held.
        WalkthroughIndex index = new WalkthroughIndex(Arrays.asList(
                new WalkthroughIndex.Step("u", "Forest", "Tree", true, new int[0], new int[0]),
                new WalkthroughIndex.Step("get egg", "Tree", null, new int[] {1}, new int[] {1}),
                new WalkthroughIndex.Step("d", "Tree", "Forest", false, new int[0], new int[0]),
                new WalkthroughIndex.Step("n", "Forest", "Lane", true, new int[0], new int[0]),
                new WalkthroughIndex.Step("n", "Lane", "Hall", true, new int[0], new int[0]),
                new WalkthroughIndex.Step("e", "Hall", "Dome", true, new int[0], new int[0]),
                new WalkthroughIndex.Step("tie rope to railing", "Dome", null, new int[] {0}, new int[] {1}),
                new WalkthroughIndex.Step("d", "Dome", "Torch", false, new int[0], new int[0]),
                new WalkthroughIndex.Step("e", "Torch", "Temple", false, new int[0], new int[0]),
                new WalkthroughIndex.Step("pray", "Temple", "Forest", false, new int[0], new int[0])));
        HintAdvisor advisor = new HintAdvisor(index);
        advisor.hint(new int[] {0, 0}, "Dome", null);
        assertEquals("From here, go west, south, south, then up. Say hint again to hear what to do there.",
                advisor.hint(new int[] {0, 0}, "Dome", null));
        HintAdvisor tied = new HintAdvisor(index);
        tied.hint(new int[] {1, 0}, "Dome", null);
        assertEquals("From here, go down, east, pray, then up. Say hint again to hear what to do there.",
                tied.hint(new int[] {1, 0}, "Dome", null));
    }

    @Test
    public void onlyCheckedWaysBackAreUsed() {
        WalkthroughIndex index = new WalkthroughIndex(Arrays.asList(
                new WalkthroughIndex.Step("n", "Field", "Path", true, new int[0], new int[0]),
                new WalkthroughIndex.Step("e", "Path", "Hut", false, new int[0], new int[0]),
                new WalkthroughIndex.Step("take lamp", "Hut", null, new int[] {1}, new int[] {1}),
                new WalkthroughIndex.Step("open box", "Field", null, new int[] {0}, new int[] {1})));
        HintAdvisor fromPath = new HintAdvisor(index);
        fromPath.hint(new int[] {0, 1}, "Path", null);
        assertEquals("From here, go south. Say hint again to hear what to do there.",
                fromPath.hint(new int[] {0, 1}, "Path", null));
        HintAdvisor fromHut = new HintAdvisor(index);
        fromHut.hint(new int[] {0, 1}, "Hut", null);
        assertEquals("I don't know the way from here. Try to retrace your steps. Say hint again to hear what to do there.",
                fromHut.hint(new int[] {0, 1}, "Hut", null));
    }

    @Test
    public void probedExitsGiveShortRoutesAndSurviveTheCache() {
        Map<String, Map<String, String>> exits = new HashMap<>();
        exits.put("Hut", new HashMap<>());
        exits.get("Hut").put("SW", "Field");
        WalkthroughIndex index = WalkthroughIndex.deserialize(new WalkthroughIndex(Arrays.asList(
                new WalkthroughIndex.Step("n", "Field", "Path", true, new int[0], new int[0]),
                new WalkthroughIndex.Step("e", "Path", "Hut", false, new int[0], new int[0]),
                new WalkthroughIndex.Step("take lamp", "Hut", null, new int[] {1}, new int[] {1}),
                new WalkthroughIndex.Step("open box", "Field", null, new int[] {0}, new int[] {1})),
                exits).serialize());
        HintAdvisor advisor = new HintAdvisor(index);
        advisor.hint(new int[] {0, 1}, "Hut", null);
        assertEquals("From here, go southwest. Say hint again to hear what to do there.",
                advisor.hint(new int[] {0, 1}, "Hut", null));
    }

    @Test
    public void laterValuesCountAsDone() {
        WalkthroughIndex index = new WalkthroughIndex(Arrays.asList(
                new WalkthroughIndex.Step("take lamp", "Hut", null, new int[] {1}, new int[] {1}),
                new WalkthroughIndex.Step("open box", "Hut", null, new int[] {0}, new int[] {1}),
                new WalkthroughIndex.Step("put lamp in box", "Hut", null, new int[] {1}, new int[] {2})));
        assertEquals(1, index.nextGoal(new int[] {0, 2}));
        assertEquals(-1, index.nextGoal(new int[] {1, 2}));
        assertEquals(HintAdvisor.ALL_DONE, new HintAdvisor(index).hint(new int[] {1, 2}, "Hut", null));
    }

    @Test
    public void thingsHintNamesTheNouns() {
        assertEquals("Try doing something with the chest and the rope.", HintAdvisor.thingsHint("tie chest to rope"));
        assertEquals("Try doing something with the dial and the 776.", HintAdvisor.thingsHint("set dial to 776"));
        assertEquals("Think about the word pray.", HintAdvisor.thingsHint("pray"));
        assertEquals("Try doing something with the egg, the nest and the tree.",
                HintAdvisor.thingsHint("take egg from nest in tree"));
    }
}

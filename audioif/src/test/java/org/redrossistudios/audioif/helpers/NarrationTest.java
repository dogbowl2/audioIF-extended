package org.redrossistudios.audioif.helpers;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class NarrationTest {

    @Test
    public void dropsTitleTheDescriptionRepeats() {
        assertEquals("You are in the kitchen of the white house.",
                Narration.forSpeech("Kitchen\nYou are in the kitchen of the white house."));
        assertEquals("You have entered a low cave with passages leading northwest and east.\n"
                        + "There are old engravings on the walls here.",
                Narration.forSpeech("Engravings Cave\nYou have entered a low cave with passages leading "
                        + "northwest and east.\nThere are old engravings on the walls here."));
    }

    @Test
    public void keepsOtherTitlesWithAPause() {
        assertEquals("Round Room.", Narration.forSpeech("Round Room"));
        assertEquals("Up a Tree.\nYou are about 10 feet above the ground.",
                Narration.forSpeech("Up a Tree\nYou are about 10 feet above the ground."));
    }

    @Test
    public void dropsTheHeardEcho() {
        assertEquals("You already have that!", Narration.forSpeech("> get egg\n\nYou already have that!"));
    }

    @Test
    public void endsBareLinesWithAPeriod() {
        assertEquals("The glass bottle contains:\nA quantity of water.",
                Narration.forSpeech("The glass bottle contains:\n  A quantity of water"));
    }
}

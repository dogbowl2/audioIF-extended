package org.redrossistudios.audioif.helpers;

import org.zmpp.ExecutionControl;
import org.zmpp.base.DefaultMemory;
import org.zmpp.iff.DefaultFormChunk;
import org.zmpp.vm.Machine;
import org.zmpp.vm.MachineRunState;
import org.zmpp.vm.AudioIfExtensions;
import org.zmpp.vm.PortableGameState;
import org.zmpp.windowing.AnnotatedText;
import org.zmpp.windowing.BufferedScreenModel;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Adapts a running ZMPP machine to {@link CommandSession.Game}. Create it after
 * {@code executionControl.run()} has reached the first input prompt.
 */
public final class ZGame implements CommandSession.Game {

    private final ExecutionControl executionControl;
    private final BufferedScreenModel screenModel;
    private volatile String room;
    private List<String> dictionaryWords;

    public ZGame(ExecutionControl executionControl, BufferedScreenModel screenModel) {
        this.executionControl = executionControl;
        this.screenModel = screenModel;
        screenModel.addStatusLineListener((objectDescription, status) -> {
            if (objectDescription != null && !objectDescription.trim().isEmpty()) {
                room = objectDescription.trim();
            }
        });
    }

    /** Text printed before the first prompt; sends a blank line if the game is waiting for a key. */
    public String readIntro() {
        String intro = readOutput();
        return intro.length() > 1 ? intro : send(" ");
    }

    /** Sends a line; an empty response means the game wants more input (e.g. "press a key"). */
    @Override
    public String send(String command) {
        String text = "";
        for (int i = 0; i < 5 && text.length() <= 1; i++) {
            executionControl.resumeWithInput(i == 0 ? command : " ");
            text = readOutput();
        }
        return text;
    }

    @Override
    public boolean contains(String word) {
        try {
            return executionControl.getMachine().lookupToken(0, word) != 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public List<String> dictionaryWords() {
        if (dictionaryWords == null) {
            try {
                dictionaryWords = AudioIfExtensions.getDictionaryWords(executionControl.getMachine());
            } catch (RuntimeException e) {
                dictionaryWords = Collections.emptyList();
            }
        }
        return dictionaryWords;
    }

    @Override
    public String currentRoom() {
        return room;
    }

    /**
     * Short names of the objects in the player's room and one level inside them, from the
     * object tree. The location is global 0, which is only trusted when its name matches the
     * status line; otherwise this returns an empty list.
     */
    @Override
    public List<String> thingsHere() {
        List<String> names = new ArrayList<>();
        try {
            Machine machine = executionControl.getMachine();
            int location = machine.readUnsigned16(machine.readUnsigned16(0x0C));
            // The status line isn't shown until the first command; until then trust global 0.
            if (location <= 0 || room != null && !room.equalsIgnoreCase(objectName(machine, location))) {
                return names;
            }
            for (int child = machine.getChild(location); child != 0 && names.size() < 40;
                    child = machine.getSibling(child)) {
                String name = objectName(machine, child);
                names.add(name);
                if (PLAYER_NAMES.contains(name.toLowerCase(java.util.Locale.ROOT)) || CommandSession.isCreature(name)) {
                    continue; // what the player or the thief carries isn't "here"
                }
                for (int inner = machine.getChild(child); inner != 0 && names.size() < 40;
                        inner = machine.getSibling(inner)) {
                    names.add(objectName(machine, inner));
                }
            }
        } catch (RuntimeException e) {
            names.clear();
        }
        return names;
    }

    /**
     * Dictionary words that name the object called {@code thing} here or carried ("prayer" and
     * "inscription" for the prayer), from its synonym property; empty if not found.
     */
    @Override
    public List<String> nounsFor(String thing) {
        List<String> words = new ArrayList<>();
        try {
            Machine machine = executionControl.getMachine();
            int location = machine.readUnsigned16(machine.readUnsigned16(0x0C));
            int object = location <= 0 ? 0 : findNamed(machine, location, thing, 2);
            if (object == 0) {
                return words;
            }
            int dictionary = machine.readUnsigned16(0x08);
            int separators = machine.readUnsigned8(dictionary);
            int entryLength = machine.readUnsigned8(dictionary + 1 + separators);
            int entries = Math.abs((short) machine.readUnsigned16(dictionary + 2 + separators));
            int first = dictionary + 4 + separators;
            int end = first + entries * entryLength;
            int textBytes = machine.getVersion() <= 3 ? 4 : 6;
            for (int property = machine.getNextProperty(object, 0); property != 0;
                    property = machine.getNextProperty(object, property)) {
                int address = machine.getPropertyAddress(object, property);
                int length = machine.getPropertyLength(address);
                if (length < 2 || length % 2 != 0) {
                    continue;
                }
                List<String> found = new ArrayList<>();
                for (int i = 0; i < length; i += 2) {
                    int word = machine.readUnsigned16(address + i);
                    if (word < first || word >= end || (word - first) % entryLength != 0) {
                        found = null;
                        break;
                    }
                    found.add(machine.decode2Zscii(word, textBytes).trim().toLowerCase(java.util.Locale.ROOT));
                }
                if (found != null) {
                    words.addAll(found);
                }
            }
        } catch (RuntimeException e) {
            words.clear();
        }
        return words;
    }

    private static int findNamed(Machine machine, int parent, String name, int depth) {
        for (int child = machine.getChild(parent); child != 0; child = machine.getSibling(child)) {
            if (objectName(machine, child).equalsIgnoreCase(name)) {
                return child;
            }
            if (depth > 1) {
                int inner = findNamed(machine, child, name, depth - 1);
                if (inner != 0) {
                    return inner;
                }
            }
        }
        return 0;
    }

    private static final java.util.Set<String> PLAYER_NAMES = new java.util.HashSet<>(java.util.Arrays.asList(
            "cretin", "you", "yourself", "me", "adventurer", "player"));

    /**
     * The name of the creature carrying something called {@code noun} ("thief" for "torch"),
     * searching the whole object tree, or null if no creature has one.
     */
    @Override
    public String holderOf(String noun) {
        if (noun == null || noun.isEmpty()) {
            return null;
        }
        try {
            Machine machine = executionControl.getMachine();
            int count = GameStateReader.objectCount(machine);
            for (int object = 1; object <= count; object++) {
                String name = objectName(machine, object).toLowerCase(java.util.Locale.ROOT);
                if (!(" " + name + " ").contains(" " + noun + " ")) {
                    continue;
                }
                // The thief keeps his loot in his bag, so look two levels up.
                int parent = machine.getParent(object);
                for (int up = 0; up < 2 && parent > 0 && parent <= count; up++) {
                    String holder = objectName(machine, parent);
                    if (CommandSession.isCreature(holder)) {
                        return holder;
                    }
                    parent = machine.getParent(parent);
                }
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    /** Short names of what the player carries, from the object tree; empty if unknown. Works in the dark. */
    @Override
    public List<String> carried() {
        List<String> names = new ArrayList<>();
        try {
            Machine machine = executionControl.getMachine();
            int location = machine.readUnsigned16(machine.readUnsigned16(0x0C));
            if (location <= 0) {
                return names;
            }
            for (int child = machine.getChild(location); child != 0; child = machine.getSibling(child)) {
                if (!PLAYER_NAMES.contains(objectName(machine, child).toLowerCase(java.util.Locale.ROOT))) {
                    continue;
                }
                for (int held = machine.getChild(child); held != 0 && names.size() < 40;
                        held = machine.getSibling(held)) {
                    names.add(objectName(machine, held));
                }
                break;
            }
        } catch (RuntimeException e) {
            names.clear();
        }
        return names;
    }

    private static String objectName(Machine machine, int object) {
        return machine.decode2Zscii(machine.getPropertiesDescriptionAddress(object), 0).trim();
    }

    @Override
    public int[] stateVector() {
        try {
            return GameStateReader.read(executionControl.getMachine());
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public Object snapshot() {
        try {
            return AudioIfExtensions.captureInputState(executionControl.getMachine());
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public boolean restore(Object snapshot) {
        if (!(snapshot instanceof PortableGameState)) {
            return false;
        }
        try {
            AudioIfExtensions.restoreInputState(executionControl.getMachine(), (PortableGameState) snapshot);
            executionControl.run();
            readOutput();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * The game state as Quetzal bytes, for resuming later. Returns null unless
     * the game is waiting for a command (for example after it has ended).
     */
    public byte[] saveState() {
        try {
            MachineRunState runState = executionControl.getMachine().getRunState();
            if (runState == null || !runState.isReadLine()) {
                return null;
            }
            return AudioIfExtensions.captureInputState(executionControl.getMachine()).exportToFormChunk().getBytes();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Restores bytes from {@link #saveState()}. Returns false, leaving the game
     * untouched, if they are unreadable or belong to a different story file.
     */
    public boolean loadState(byte[] data) {
        if (data == null) {
            return false;
        }
        try {
            PortableGameState state = new PortableGameState();
            if (!state.readSaveGame(new DefaultFormChunk(new DefaultMemory(data)))) {
                return false;
            }
            Machine machine = executionControl.getMachine();
            PortableGameState current = AudioIfExtensions.captureInputState(machine);
            if (state.getRelease() != current.getRelease() || state.getChecksum() != current.getChecksum()
                    || !state.getSerialNumber().equals(current.getSerialNumber())
                    || state.getDynamicMemoryDump() == null
                    || state.getDynamicMemoryDump().length != current.getDynamicMemoryDump().length) {
                return false;
            }
            return restore(state);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private String readOutput() {
        StringBuilder result = new StringBuilder();
        for (AnnotatedText text : screenModel.getLowerBuffer()) {
            result.append(text.getText());
        }
        String s = result.toString();
        // Drop the trailing ">" prompt.
        return s.length() > 2 ? s.substring(0, s.length() - 2).trim() : s;
    }
}

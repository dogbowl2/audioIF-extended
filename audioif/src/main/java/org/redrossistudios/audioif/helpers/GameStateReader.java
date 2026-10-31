package org.redrossistudios.audioif.helpers;

import org.zmpp.vm.Machine;

/**
 * Reads the parts of a Z-machine game's state that puzzles change (each object's
 * parent and attribute flags, and the global variables) as one flat vector, so
 * two moments of the game can be compared field by field.
 *
 * Layout: for objects 1..n, [parent, one 0/1 field per attribute], then the 240 globals.
 * Field {@link #GLOBALS_FROM_END} from the end is global 0.
 */
public final class GameStateReader {

    public static final int GLOBALS_FROM_END = 240;

    private GameStateReader() {
    }

    /** The number of objects: the object table runs up to the first object's property table. */
    public static int objectCount(Machine machine) {
        boolean small = machine.getVersion() <= 3;
        int entrySize = small ? 9 : 14;
        int entries = machine.readUnsigned16(0x0A) + (small ? 31 : 63) * 2;
        int firstProperties = machine.readUnsigned16(entries + (small ? 7 : 12));
        return Math.max(0, (firstProperties - entries) / entrySize);
    }

    public static int[] read(Machine machine) {
        int version = machine.getVersion();
        boolean small = version <= 3;
        int defaults = small ? 31 : 63;
        int entrySize = small ? 9 : 14;
        int attributeBytes = small ? 4 : 6;
        int objectTable = machine.readUnsigned16(0x0A);
        int entries = objectTable + defaults * 2;
        int count = objectCount(machine);
        int globals = machine.readUnsigned16(0x0C);

        int perObject = 1 + attributeBytes * 8;
        int[] state = new int[count * perObject + 240];
        int i = 0;
        for (int object = 1; object <= count; object++) {
            int address = entries + (object - 1) * entrySize;
            state[i++] = small ? machine.readUnsigned8(address + attributeBytes)
                    : machine.readUnsigned16(address + attributeBytes);
            for (int b = 0; b < attributeBytes; b++) {
                int flags = machine.readUnsigned8(address + b);
                for (int bit = 7; bit >= 0; bit--) {
                    state[i++] = (flags >> bit) & 1;
                }
            }
        }
        for (int g = 0; g < 240; g++) {
            state[i++] = machine.readUnsigned16(globals + g * 2);
        }
        return state;
    }
}

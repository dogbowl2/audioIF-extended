package org.redrossistudios.audioif.harness;

import org.junit.Assume;
import org.junit.Test;
import org.redrossistudios.audioif.helpers.CommandSession;
import org.redrossistudios.audioif.helpers.ZGame;
import org.zmpp.ExecutionControl;
import org.zmpp.iff.FormChunk;
import org.zmpp.iff.WritableFormChunk;
import org.zmpp.io.IOSystem;
import org.zmpp.vm.MachineFactory;
import org.zmpp.vm.SaveGameDataStore;
import org.zmpp.windowing.BufferedScreenModel;

import java.io.File;
import java.io.FileInputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Developer tool: plays a game headlessly through the same input pipeline as
 * text and audio mode (CommandSession -> CommandNormalizer/CommandSuggester -> ZMPP).
 *
 * Skipped unless given a commands file:
 *   gradlew :audioif:testDebugUnitTest --tests *PlayHarness* -Pplay=C:\path\commands.txt
 * Optional: -PplayGame=path\to\story.z3 (defaults to the bundled Zork I), -PplaySeed=1234.
 *
 * Commands are replayed from the start on every run, one per line; blank lines
 * and lines starting with '#' are ignored. The random generator is seeded so
 * replays are deterministic. Output goes to audioif/build/play/:
 * transcript.md, transcript.json and latest.txt (the last three turns).
 */
public class PlayHarness {

    static final class Turn {
        String typed;
        CommandSession.Result result;
    }

    private BufferedScreenModel screenModel;
    private ExecutionControl executionControl;

    @Test
    public void play() throws Exception {
        String commandsPath = System.getProperty("play.commands", "");
        Assume.assumeTrue("pass -Pplay=<commands file> to run", !commandsPath.isEmpty());
        String gamePath = System.getProperty("play.game", "");
        if (gamePath.isEmpty()) {
            gamePath = "src/main/assets/games/Zork I.z3";
        }
        short seed = Short.parseShort(System.getProperty("play.seed", "1234"));

        List<String> commands = new ArrayList<>();
        for (String line : Files.readAllLines(new File(commandsPath).toPath(), StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                commands.add(trimmed);
            }
        }

        File outDir = new File("build/play");
        outDir.mkdirs();
        start(new File(gamePath));
        executionControl.getMachine().random((short) -Math.abs(seed));
        ZGame game = new ZGame(executionControl, screenModel);
        String intro = game.readIntro();
        CommandSession session = new CommandSession(game);
        session.setListThings(true);
        session.start(intro);
        // Walkthrough hints, as in the app, when tools/fetch-walkthroughs.ps1 has fetched one.
        File storyFile = new File(gamePath);
        String storyName = storyFile.getName().replaceFirst("\\.[^.]+$", "");
        File walkthrough = new File("src/main/assets/walkthroughs/" + storyName + ".txt");
        if (walkthrough.exists()) {
            org.redrossistudios.audioif.helpers.WalkthroughIndex index =
                    org.redrossistudios.audioif.helpers.HintCatalog.load(storyName,
                            Files.readAllBytes(storyFile.toPath()),
                            new String(Files.readAllBytes(walkthrough.toPath()), StandardCharsets.ISO_8859_1),
                            new File(outDir, "hints"));
            session.setHintAdvisor(index == null ? null
                    : new org.redrossistudios.audioif.helpers.HintAdvisor(index));
        }

        List<Turn> turns = new ArrayList<>();
        for (String typed : commands) {
            Turn turn = new Turn();
            turn.typed = typed;
            // "~ nor | north" simulates speech: the recognizer's alternatives, best first.
            turn.result = typed.startsWith("~")
                    ? session.handleSpeech(Arrays.asList(typed.substring(1).split("\\|")))
                    : session.handle(typed);
            turns.add(turn);
        }

        write(new File(outDir, "transcript.md"), markdown(intro, turns, 0));
        write(new File(outDir, "latest.txt"), markdown(null, turns, Math.max(0, turns.size() - 3)));
        write(new File(outDir, "transcript.json"), json(turns));
        int rejected = 0;
        for (Turn turn : turns) {
            rejected += turn.result.rejected ? 1 : 0;
        }
        write(new File(outDir, "summary.txt"), "inputs " + turns.size() + ", rejected " + rejected + "\n");
    }

    private void start(File game) throws Exception {
        MachineFactory.MachineInitStruct init = new MachineFactory.MachineInitStruct();
        init.storyFile = new FileInputStream(game);
        init.nativeImageFactory = inputStream -> null;
        init.saveGameDataStore = new SaveGameDataStore() {
            public boolean saveFormChunk(WritableFormChunk formchunk) { return false; }
            public FormChunk retrieveFormChunk() { return null; }
        };
        init.ioSystem = new IOSystem() {
            public Reader getInputStreamReader() { return null; }
            public Writer getTranscriptWriter() { return null; }
        };
        screenModel = new BufferedScreenModel();
        init.statusLine = screenModel;
        init.screenModel = screenModel;
        init.keyboardInputStream = new org.zmpp.io.InputStream() {
            public void close() { }
            public String readLine() { return null; }
        };
        executionControl = new ExecutionControl(init);
        executionControl.resizeScreen(100, 500);
        screenModel.init(executionControl.getMachine(), executionControl.getZsciiEncoding());
        executionControl.run();
    }

    private static String markdown(String intro, List<Turn> turns, int from) {
        StringBuilder sb = new StringBuilder();
        if (intro != null) {
            sb.append(intro).append("\n\n");
        }
        for (int i = from; i < turns.size(); i++) {
            Turn t = turns.get(i);
            List<String> sent = t.result.sentCommands();
            sb.append("## ").append(i + 1).append(". > ").append(t.typed);
            if (t.result.heard != null) {
                sb.append("  [heard: ").append(t.result.heard).append("]");
            }
            if (!(sent.size() == 1 && sent.get(0).trim().equalsIgnoreCase(t.typed.trim()))) {
                sb.append("  [sent: ").append(String.join("; ", sent)).append("]");
            }
            if (t.result.rejected) {
                sb.append("  **REJECTED**");
            }
            sb.append("\n\n");
            for (CommandSession.Exchange e : t.result.exchanges) {
                if (e.response != null) {
                    if (sent.size() > 1) {
                        sb.append("> ").append(e.command).append("\n");
                    }
                    sb.append(e.response).append("\n\n");
                }
                if (e.note != null) {
                    sb.append("(app) ").append(e.note).append("\n\n");
                }
            }
        }
        return sb.toString();
    }

    private static String json(List<Turn> turns) {
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < turns.size(); i++) {
            Turn t = turns.get(i);
            sb.append("  {\"n\":").append(i + 1)
                    .append(",\"typed\":").append(q(t.typed))
                    .append(",\"rejected\":").append(t.result.rejected)
                    .append(",\"suggestions\":[");
            for (int j = 0; j < t.result.suggestions.size(); j++) {
                sb.append(j > 0 ? "," : "").append(q(t.result.suggestions.get(j)));
            }
            sb.append("],\"exchanges\":[");
            for (int j = 0; j < t.result.exchanges.size(); j++) {
                CommandSession.Exchange e = t.result.exchanges.get(j);
                sb.append(j > 0 ? "," : "").append("{\"sent\":").append(q(e.command))
                        .append(",\"response\":").append(q(e.response))
                        .append(",\"note\":").append(q(e.note)).append("}");
            }
            sb.append("]}").append(i < turns.size() - 1 ? ",\n" : "\n");
        }
        return sb.append("]\n").toString();
    }

    private static String q(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }

    private static void write(File file, String text) throws Exception {
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }
}

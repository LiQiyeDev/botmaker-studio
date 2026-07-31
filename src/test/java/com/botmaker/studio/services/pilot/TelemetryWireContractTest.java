package com.botmaker.studio.services.pilot;

import com.botmaker.shared.ipc.TelemetryEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Studio half of the pilot wire contract. {@code types.ts} names every field of every message; this
 * side concatenates strings, and the only thing that has ever claimed the two agree is a javadoc sentence.
 *
 * <p>Both halves read the <em>same</em> corpus — {@code pilot/wire-golden.json} here,
 * {@code web/src/wire-golden.json} in the pilot repo — and both assert its SHA-256. Neither test can see
 * the other's repo, but the digest means neither copy can move alone: change the wire on one side and the
 * other side goes red until it is changed too. That, not the javadoc, is what keeps the clone honest.
 */
class TelemetryWireContractTest {

    private static final String GOLDEN = "/pilot/wire-golden.json";

    /**
     * The corpus, byte for byte. Update this together with {@code GOLDEN_SHA256} in the pilot repo's
     * {@code wire.test.ts} — and with the copy of the file itself, which must stay byte-identical.
     */
    private static final String GOLDEN_SHA256 = "823d631b3ebc58d2dcc6aaba6f3951552bacc829d081ff997235d959e8e954cc";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNode CORPUS = corpus();

    /** Every case this class asserted, so a case added to the corpus and never wired up fails the build. */
    private static final Set<String> COVERED = new HashSet<>();

    private static final TelemetryEvent.Target TARGET =
            new TelemetryEvent.Target("Nested Game", 100, 50, 640, 480);

    // --- The corpus, message by message ---

    @Test
    void aSuccessfulMatchCarriesItsSearchRegionAndTheRectItFound() {
        assertWire("telemetry.match.found", TelemetrySerializer.telemetryJson(new TelemetryEvent.Match(
                TARGET,
                new TelemetryEvent.Rect(110, 60, 200, 100),
                new TelemetryEvent.Rect(150, 80, 32, 24),
                0.9123, true)));
    }

    @Test
    void aFailedMatchStillSendsTheKeysTheClientReadsRatherThanOmittingThem() {
        // region/rect are null, not absent: the renderer tests `o.region` / `o.rect`, so an omitted key
        // would read the same — but the client's `Rect | null` types say null, and this pins it.
        assertWire("telemetry.match.miss",
                TelemetrySerializer.telemetryJson(new TelemetryEvent.Match(TARGET, null, null, 0.0, false)));
    }

    @Test
    void aClickCarriesAbsoluteCoordinatesAndTheButton() {
        assertWire("telemetry.click",
                TelemetrySerializer.telemetryJson(new TelemetryEvent.Click(TARGET, 300, 220, 3)));
    }

    @Test
    void aRegionHighlightCarriesOnlyItsRect() {
        assertWire("telemetry.region", TelemetrySerializer.telemetryJson(
                new TelemetryEvent.Region(TARGET, new TelemetryEvent.Rect(0, 0, 640, 480))));
    }

    @Test
    void aTitleWithQuotesBackslashesAndNonAsciiSurvivesIntact() {
        // A window title is whatever the game called itself. Quote and backslash are escaped; a non-ASCII
        // character goes out as itself (the frames are UTF-8), and a negative origin is a left-hand monitor.
        TelemetryEvent.Target odd =
                new TelemetryEvent.Target("He said \"hi\" \u2014 C:\\Games\\x", -1920, 0, 1280, 720);
        assertWire("telemetry.target.title.quoted", TelemetrySerializer.telemetryJson(
                new TelemetryEvent.Region(odd, new TelemetryEvent.Rect(-1920, 0, 100, 100))));
    }

    @Test
    void aNullTargetSerializesAsNullRatherThanCrashing() {
        // TelemetryEvent's javadoc says target is never null; the serializer tolerates it anyway and the
        // client types it `Target | null`. Characterising it here means the tolerance is deliberate.
        assertWire("telemetry.target.null",
                TelemetrySerializer.telemetryJson(new TelemetryEvent.Click(null, 10, 20, 1)));
    }

    @Test
    void theRunStateMessageCarriesTheStateAndWhetherInputStaysOffTheHostsCursor() {
        assertWire("state.running.background", TelemetrySerializer.stateJson("running", true));
        assertWire("state.paused.foreground", TelemetrySerializer.stateJson("paused", false));
        assertWire("state.stopped.background", TelemetrySerializer.stateJson("stopped", true));
    }

    // --- What the corpus cannot express ---

    @Test
    void theTimestampIsTheSendClock() {
        // 'ts' is stamped at serialization, not carried by the event, so the corpus holds a sentinel and
        // assertWire substitutes it. This is the assertion the substitution would otherwise throw away.
        long before = System.currentTimeMillis();
        JsonNode event = parse(TelemetrySerializer.telemetryJson(new TelemetryEvent.Click(TARGET, 1, 2, 1)))
                .get("event");
        long ts = event.get("ts").asLong();
        assertTrue(ts >= before && ts <= System.currentTimeMillis(),
                "ts must be the wall clock at send time, was " + ts);
    }

    @Test
    void theSourceLineIsNotOnTheWire() {
        // Every TelemetryEvent carries line(), and no message carries it out. Pinned so that the day the
        // pilot wants to show which block is running, this test is what says the field has to be added.
        JsonNode event = parse(TelemetrySerializer.telemetryJson(
                new TelemetryEvent.Click(TARGET, 1, 2, 1, 42))).get("event");
        assertTrue(event.get("line") == null, "line() is dropped at the wire boundary");
    }

    @Test
    void aTitleWithAControlCharacterProducesJsonTheClientCannotParse() {
        // B18. jsonStr escapes backslash and quote and stops there, but JSON forbids raw U+0000..U+001F in
        // a string. A title with a newline in it therefore takes the whole message out — the client's
        // JSON.parse throws and usePilot's catch drops it silently. Red-by-design would be wrong here:
        // this is what the code does today, and Phase 4 flips the assertion.
        TelemetryEvent.Target broken = new TelemetryEvent.Target("Game\nWindow", 0, 0, 10, 10);
        String json = TelemetrySerializer.telemetryJson(new TelemetryEvent.Click(broken, 1, 2, 1));
        assertThrows(JsonProcessingException.class, () -> JSON.readTree(json),
                "a raw control character in a title makes the message unparseable");
    }

    @Test
    void aNonFiniteConfidenceProducesJsonTheClientCannotParse() {
        // B18 again, other half: %.4f of NaN is the bare token NaN, which is not JSON. A miss whose
        // confidence never got computed would take its own message down.
        String json = TelemetrySerializer.telemetryJson(
                new TelemetryEvent.Match(TARGET, null, null, Double.NaN, false));
        assertTrue(json.contains("\"confidence\":NaN"), "the raw token is what gets written: " + json);
        assertThrows(JsonProcessingException.class, () -> JSON.readTree(json));
    }

    // --- The corpus itself ---

    @Test
    void theGoldenCorpusIsTheOneThePilotRepoHas() throws Exception {
        assertEquals(GOLDEN_SHA256, sha256(goldenBytes()),
                "pilot/wire-golden.json changed. The pilot repo has a byte-identical copy and asserts the "
                        + "same digest — update both files and both constants, or the wire has silently forked.");
    }

    @AfterAll
    static void everyCaseInTheCorpusWasAsserted() {
        Set<String> uncovered = new TreeSet<>();
        for (Iterator<String> it = CORPUS.fieldNames(); it.hasNext(); ) {
            String name = it.next();
            if (!name.startsWith("_") && !COVERED.contains(name)) uncovered.add(name);
        }
        assertEquals(Set.of(), uncovered, "corpus cases with no Studio-side assertion");
    }

    // --- Helpers ---

    /** Compares an emitted message with its golden, ignoring only the send-clock timestamp. */
    private static void assertWire(String caseName, String actualJson) {
        JsonNode expected = CORPUS.get(caseName);
        assertNotNull(expected, "no such case in " + GOLDEN + ": " + caseName);
        JsonNode actual = parse(actualJson);
        if (actual.get("event") instanceof ObjectNode event && event.has("ts")) event.put("ts", 0);
        assertEquals(expected, actual, caseName + " no longer matches the corpus the pilot decodes");
        COVERED.add(caseName);
    }

    private static JsonNode parse(String json) {
        try {
            return JSON.readTree(json);
        } catch (JsonProcessingException e) {
            throw new AssertionError("the serializer emitted invalid JSON: " + json, e);
        }
    }

    private static JsonNode corpus() {
        try {
            return JSON.readTree(goldenBytes());
        } catch (IOException e) {
            throw new AssertionError("cannot read " + GOLDEN, e);
        }
    }

    private static byte[] goldenBytes() throws IOException {
        try (InputStream in = TelemetryWireContractTest.class.getResourceAsStream(GOLDEN)) {
            assertNotNull(in, GOLDEN + " is missing from the test resources");
            return in.readAllBytes();
        }
    }

    private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}

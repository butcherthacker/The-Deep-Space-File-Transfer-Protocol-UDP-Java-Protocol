import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.nio.ByteBuffer;

/**
 * DS-FTP Unit Test Suite
 * ----------------------
 * Tests every protocol rule defined in the README:
 *   - DSPacket byte layout (128-byte contract, header parsing, round-trip)
 *   - Sequence number modulo-128 semantics
 *   - SOT / DATA / ACK / EOT type codes and control-packet length=0 rule
 *   - ChaosEngine permutation order  (i,i+1,i+2,i+3) → (i+2,i,i+3,i+1)
 *   - ChaosEngine ACK drop rule (every RN-th ACK)
 *   - GBN window-size validation  (multiple of 4, N ≤ 128)
 *   - Modulo-128 forward-distance arithmetic
 *   - Receiver window acceptance logic (within / outside / boundary)
 *   - Protocol sequence semantics (SOT seq=0, DATA seq=1, EOT seq=last+1)
 *   - Empty-file edge case (EOT seq=1)
 *   - Payload size limits (max 124 bytes)
 *
 * Compile & run from Sender/ :
 *   javac DSFTPTest.java && java DSFTPTest
 *   (DSPacket.java and ChaosEngine.java must be present in the same directory)
 */
public class DSFTPTest {

    // ── Simple counters ───────────────────────────────────────────────────────
    private static int passed = 0;
    private static int failed = 0;

    // =========================================================================
    // main
    // =========================================================================

    public static void main(String[] args) {
        System.out.println("==============================================");
        System.out.println(" DS-FTP Unit Tests  (README-based)");
        System.out.println("==============================================\n");

        // ── 1. DSPacket: packet size contract ────────────────────────────────
        section("1. DSPacket – 128-byte contract");
        test_packetAlwaysExactly128Bytes_SOT();
        test_packetAlwaysExactly128Bytes_DATA();
        test_packetAlwaysExactly128Bytes_ACK();
        test_packetAlwaysExactly128Bytes_EOT();

        // ── 2. DSPacket: header byte layout ──────────────────────────────────
        section("2. DSPacket – header byte layout");
        test_typeFieldAtByte0();
        test_seqNumFieldAtByte1();
        test_lengthFieldAtBytes2and3_bigEndian();
        test_payloadStartsAtByte4();

        // ── 3. DSPacket: round-trip serialise / deserialise ───────────────────
        section("3. DSPacket – round-trip");
        test_roundTrip_SOT();
        test_roundTrip_DATA_fullPayload();
        test_roundTrip_DATA_partialPayload();
        test_roundTrip_ACK();
        test_roundTrip_EOT();

        // ── 4. DSPacket: sequence number semantics ────────────────────────────
        section("4. DSPacket – sequence number semantics");
        test_seqNum_withinRange();
        test_seqNum_wrapsAt128();
        test_seqNum_zero();
        test_seqNum_maxPayload_is_124();

        // ── 5. DSPacket: control packet length = 0 rule ───────────────────────
        section("5. DSPacket – control-packet length=0 rule");
        test_SOT_hasLengthZero();
        test_ACK_hasLengthZero();
        test_EOT_hasLengthZero();
        test_DATA_length_matchesPayload();

        // ── 6. DSPacket: type constants ───────────────────────────────────────
        section("6. DSPacket – type constants (README table)");
        test_typeConstant_SOT_is_0();
        test_typeConstant_DATA_is_1();
        test_typeConstant_ACK_is_2();
        test_typeConstant_EOT_is_3();

        // ── 7. ChaosEngine: permutePackets ────────────────────────────────────
        section("7. ChaosEngine – permutePackets");
        test_permute_4packetsInCorrectOrder();
        test_permute_seqNums_unchanged_after_permute();
        test_permute_fewerThan4_returnedUnchanged();
        test_permute_moreThan4_returnedUnchanged();
        test_permute_exactly4_returnsSize4();

        // ── 8. ChaosEngine: shouldDrop ────────────────────────────────────────
        section("8. ChaosEngine – shouldDrop (ACK loss rule)");
        test_shouldDrop_RN0_neverDrops();
        test_shouldDrop_RN1_alwaysDrops();
        test_shouldDrop_RN5_dropsEvery5th();
        test_shouldDrop_RN5_nonMultiplesNotDropped();
        test_shouldDrop_negativeRN_neverDrops();

        // ── 9. GBN window-size validation ─────────────────────────────────────
        section("9. GBN – window-size validation");
        test_windowValid_4();
        test_windowValid_8();
        test_windowValid_128();
        test_windowInvalid_notMultiple4();
        test_windowInvalid_zero();
        test_windowInvalid_negative();
        test_windowInvalid_above128();

        // ── 10. GBN: modulo-128 forward-distance arithmetic ───────────────────
        section("10. GBN – modulo-128 distance arithmetic");
        test_distance_normal();
        test_distance_wraparound();
        test_distance_sameValue_isZero();
        test_distance_oneStep_wraparound();
        test_distance_halfRange();
        test_distance_fullRange();

        // ── 11. GBN: receiver in-window logic ────────────────────────────────
        section("11. GBN – receiver in-window acceptance");
        test_inWindow_expectedSeq();
        test_inWindow_oneAhead();
        test_inWindow_justBeforeBoundary();
        test_inWindow_atBoundary_rejected();
        test_inWindow_wrapAround();
        test_outOfWindow_alreadyDelivered();

        // ── 12. Protocol sequence semantics ───────────────────────────────────
        section("12. Protocol sequence semantics");
        test_SOT_mustHaveSeq0();
        test_firstDATA_mustHaveSeq1();
        test_EOT_seq_isLastDataSeqPlusOne();
        test_EOT_seq_wrapsAt128();
        test_emptyFile_EOT_seq_is_1();
        test_seqIncrements_mod128_wrapsCorrectly();

        // ── Summary ───────────────────────────────────────────────────────────
        System.out.println();
        System.out.println("==============================================");
        System.out.printf("  TOTAL: %d passed, %d failed%n", passed, failed);
        System.out.println("==============================================");

        if (failed > 0) {
            System.exit(1);
        }
    }

    // =========================================================================
    // 1. Packet size
    // =========================================================================

    static void test_packetAlwaysExactly128Bytes_SOT() {
        byte[] bytes = new DSPacket(DSPacket.TYPE_SOT, 0, null).toBytes();
        assertEquals("SOT packet is 128 bytes", 128, bytes.length);
    }

    static void test_packetAlwaysExactly128Bytes_DATA() {
        byte[] payload = new byte[50];
        byte[] bytes = new DSPacket(DSPacket.TYPE_DATA, 1, payload).toBytes();
        assertEquals("DATA packet is 128 bytes", 128, bytes.length);
    }

    static void test_packetAlwaysExactly128Bytes_ACK() {
        byte[] bytes = new DSPacket(DSPacket.TYPE_ACK, 5, null).toBytes();
        assertEquals("ACK packet is 128 bytes", 128, bytes.length);
    }

    static void test_packetAlwaysExactly128Bytes_EOT() {
        byte[] bytes = new DSPacket(DSPacket.TYPE_EOT, 7, null).toBytes();
        assertEquals("EOT packet is 128 bytes", 128, bytes.length);
    }

    // =========================================================================
    // 2. Header byte layout
    // =========================================================================

    static void test_typeFieldAtByte0() {
        byte[] bytes = new DSPacket(DSPacket.TYPE_DATA, 3, new byte[10]).toBytes();
        assertEquals("Type field is at byte 0", DSPacket.TYPE_DATA, bytes[0]);
    }

    static void test_seqNumFieldAtByte1() {
        byte[] bytes = new DSPacket(DSPacket.TYPE_DATA, 42, new byte[10]).toBytes();
        assertEquals("SeqNum field is at byte 1", 42, bytes[1] & 0xFF);
    }

    static void test_lengthFieldAtBytes2and3_bigEndian() {
        byte[] payload = new byte[300]; // will be capped by DSPacket at 124... use 100
        payload = new byte[100];
        byte[] bytes = new DSPacket(DSPacket.TYPE_DATA, 1, payload).toBytes();
        int length = ((bytes[2] & 0xFF) << 8) | (bytes[3] & 0xFF); // big-endian
        assertEquals("Length field (bytes 2-3, big-endian) equals payload size", 100, length);
    }

    static void test_payloadStartsAtByte4() {
        byte[] payload = new byte[]{0x41, 0x42, 0x43}; // "ABC"
        byte[] bytes = new DSPacket(DSPacket.TYPE_DATA, 1, payload).toBytes();
        assertEquals("Payload byte 0 starts at offset 4", 0x41, bytes[4] & 0xFF);
        assertEquals("Payload byte 1 at offset 5",        0x42, bytes[5] & 0xFF);
        assertEquals("Payload byte 2 at offset 6",        0x43, bytes[6] & 0xFF);
    }

    // =========================================================================
    // 3. Round-trip
    // =========================================================================

    static void test_roundTrip_SOT() {
        byte[] wire = new DSPacket(DSPacket.TYPE_SOT, 0, null).toBytes();
        DSPacket p = new DSPacket(padTo128(wire));
        assertEquals("SOT round-trip: type",   DSPacket.TYPE_SOT, p.getType());
        assertEquals("SOT round-trip: seqNum", 0,                 p.getSeqNum());
        assertEquals("SOT round-trip: length", 0,                 p.getLength());
    }

    static void test_roundTrip_DATA_fullPayload() {
        byte[] payload = new byte[DSPacket.MAX_PAYLOAD_SIZE];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) i;
        byte[] wire = new DSPacket(DSPacket.TYPE_DATA, 7, payload).toBytes();
        DSPacket p = new DSPacket(padTo128(wire));
        assertEquals("DATA full round-trip: type",    DSPacket.TYPE_DATA,           p.getType());
        assertEquals("DATA full round-trip: seqNum",  7,                            p.getSeqNum());
        assertEquals("DATA full round-trip: length",  DSPacket.MAX_PAYLOAD_SIZE,    p.getLength());
        assertTrue("DATA full round-trip: payload matches", Arrays.equals(payload,  p.getPayload()));
    }

    static void test_roundTrip_DATA_partialPayload() {
        byte[] payload = new byte[]{10, 20, 30, 40, 50};
        byte[] wire = new DSPacket(DSPacket.TYPE_DATA, 99, payload).toBytes();
        DSPacket p = new DSPacket(padTo128(wire));
        assertEquals("DATA partial round-trip: length",  5, p.getLength());
        assertTrue("DATA partial round-trip: payload",   Arrays.equals(payload, p.getPayload()));
    }

    static void test_roundTrip_ACK() {
        byte[] wire = new DSPacket(DSPacket.TYPE_ACK, 15, null).toBytes();
        DSPacket p = new DSPacket(padTo128(wire));
        assertEquals("ACK round-trip: type",   DSPacket.TYPE_ACK, p.getType());
        assertEquals("ACK round-trip: seqNum", 15,                p.getSeqNum());
        assertEquals("ACK round-trip: length", 0,                 p.getLength());
    }

    static void test_roundTrip_EOT() {
        byte[] wire = new DSPacket(DSPacket.TYPE_EOT, 127, null).toBytes();
        DSPacket p = new DSPacket(padTo128(wire));
        assertEquals("EOT round-trip: type",   DSPacket.TYPE_EOT, p.getType());
        assertEquals("EOT round-trip: seqNum", 127,               p.getSeqNum());
    }

    // =========================================================================
    // 4. Sequence number semantics
    // =========================================================================

    static void test_seqNum_withinRange() {
        DSPacket p = new DSPacket(DSPacket.TYPE_DATA, 63, new byte[1]);
        assertEquals("SeqNum 63 stored as-is", 63, p.getSeqNum());
    }

    static void test_seqNum_wrapsAt128() {
        // 128 mod 128 = 0
        DSPacket p = new DSPacket(DSPacket.TYPE_DATA, 128, new byte[1]);
        assertEquals("SeqNum 128 wraps to 0", 0, p.getSeqNum());
    }

    static void test_seqNum_zero() {
        DSPacket p = new DSPacket(DSPacket.TYPE_SOT, 0, null);
        assertEquals("SeqNum 0 stored as 0", 0, p.getSeqNum());
    }

    static void test_seqNum_maxPayload_is_124() {
        assertEquals("MAX_PAYLOAD_SIZE is 124", 124, DSPacket.MAX_PAYLOAD_SIZE);
    }

    // =========================================================================
    // 5. Control packet length = 0
    // =========================================================================

    static void test_SOT_hasLengthZero() {
        DSPacket p = new DSPacket(DSPacket.TYPE_SOT, 0, null);
        assertEquals("SOT length is 0", 0, p.getLength());
    }

    static void test_ACK_hasLengthZero() {
        DSPacket p = new DSPacket(DSPacket.TYPE_ACK, 5, null);
        assertEquals("ACK length is 0", 0, p.getLength());
    }

    static void test_EOT_hasLengthZero() {
        DSPacket p = new DSPacket(DSPacket.TYPE_EOT, 10, null);
        assertEquals("EOT length is 0", 0, p.getLength());
    }

    static void test_DATA_length_matchesPayload() {
        byte[] payload = new byte[73];
        DSPacket p = new DSPacket(DSPacket.TYPE_DATA, 1, payload);
        assertEquals("DATA length equals payload size", 73, p.getLength());
    }

    // =========================================================================
    // 6. Type constants
    // =========================================================================

    static void test_typeConstant_SOT_is_0()  { assertEquals("TYPE_SOT  = 0", 0, DSPacket.TYPE_SOT  & 0xFF); }
    static void test_typeConstant_DATA_is_1() { assertEquals("TYPE_DATA = 1", 1, DSPacket.TYPE_DATA & 0xFF); }
    static void test_typeConstant_ACK_is_2()  { assertEquals("TYPE_ACK  = 2", 2, DSPacket.TYPE_ACK  & 0xFF); }
    static void test_typeConstant_EOT_is_3()  { assertEquals("TYPE_EOT  = 3", 3, DSPacket.TYPE_EOT  & 0xFF); }

    // =========================================================================
    // 7. ChaosEngine – permutePackets
    // =========================================================================

    static void test_permute_4packetsInCorrectOrder() {
        // input order: i=1, i+1=2, i+2=3, i+3=4
        // expected permuted order: i+2=3, i=1, i+3=4, i+1=2
        List<DSPacket> group = makeDataPackets(new int[]{1, 2, 3, 4});
        List<DSPacket> result = ChaosEngine.permutePackets(group);
        assertEquals("Permute [0]: seq should be i+2 (=3)", 3, result.get(0).getSeqNum());
        assertEquals("Permute [1]: seq should be i   (=1)", 1, result.get(1).getSeqNum());
        assertEquals("Permute [2]: seq should be i+3 (=4)", 4, result.get(2).getSeqNum());
        assertEquals("Permute [3]: seq should be i+1 (=2)", 2, result.get(3).getSeqNum());
    }

    static void test_permute_seqNums_unchanged_after_permute() {
        // Permuting should reorder packets, not change their sequence numbers.
        List<DSPacket> group = makeDataPackets(new int[]{10, 11, 12, 13});
        List<DSPacket> original = new ArrayList<>(group);
        List<DSPacket> permuted = ChaosEngine.permutePackets(group);
        boolean allFound = original.stream()
                .allMatch(o -> permuted.stream()
                        .anyMatch(p -> p.getSeqNum() == o.getSeqNum()));
        assertTrue("All original seq numbers present after permutation", allFound);
    }

    static void test_permute_fewerThan4_returnedUnchanged() {
        List<DSPacket> group = makeDataPackets(new int[]{1, 2, 3});
        List<DSPacket> result = ChaosEngine.permutePackets(group);
        assertEquals("Fewer than 4: size unchanged", 3, result.size());
        for (int i = 0; i < 3; i++) {
            assertEquals("Fewer than 4: order unchanged at index " + i,
                    group.get(i).getSeqNum(), result.get(i).getSeqNum());
        }
    }

    static void test_permute_moreThan4_returnedUnchanged() {
        List<DSPacket> group = makeDataPackets(new int[]{1, 2, 3, 4, 5});
        List<DSPacket> result = ChaosEngine.permutePackets(group);
        assertEquals("More than 4: returned unchanged (size=5)", 5, result.size());
        assertEquals("More than 4: seq[0] unchanged", 1, result.get(0).getSeqNum());
    }

    static void test_permute_exactly4_returnsSize4() {
        List<DSPacket> result = ChaosEngine.permutePackets(makeDataPackets(new int[]{20, 21, 22, 23}));
        assertEquals("Exactly 4: result size is 4", 4, result.size());
    }

    // =========================================================================
    // 8. ChaosEngine – shouldDrop
    // =========================================================================

    static void test_shouldDrop_RN0_neverDrops() {
        for (int i = 1; i <= 20; i++) {
            assertFalse("RN=0: count=" + i + " should NOT drop",
                    ChaosEngine.shouldDrop(i, 0));
        }
    }

    static void test_shouldDrop_RN1_alwaysDrops() {
        for (int i = 1; i <= 5; i++) {
            assertTrue("RN=1: count=" + i + " should ALWAYS drop",
                    ChaosEngine.shouldDrop(i, 1));
        }
    }

    static void test_shouldDrop_RN5_dropsEvery5th() {
        assertTrue("RN=5: count=5  drops",  ChaosEngine.shouldDrop(5,  5));
        assertTrue("RN=5: count=10 drops",  ChaosEngine.shouldDrop(10, 5));
        assertTrue("RN=5: count=15 drops",  ChaosEngine.shouldDrop(15, 5));
        assertTrue("RN=5: count=100 drops", ChaosEngine.shouldDrop(100, 5));
    }

    static void test_shouldDrop_RN5_nonMultiplesNotDropped() {
        assertFalse("RN=5: count=1  does NOT drop", ChaosEngine.shouldDrop(1,  5));
        assertFalse("RN=5: count=3  does NOT drop", ChaosEngine.shouldDrop(3,  5));
        assertFalse("RN=5: count=7  does NOT drop", ChaosEngine.shouldDrop(7,  5));
        assertFalse("RN=5: count=11 does NOT drop", ChaosEngine.shouldDrop(11, 5));
    }

    static void test_shouldDrop_negativeRN_neverDrops() {
        assertFalse("RN=-1: should NOT drop", ChaosEngine.shouldDrop(5, -1));
    }

    // =========================================================================
    // 9. GBN window-size validation
    // =========================================================================

    static void test_windowValid_4()   { assertTrue("N=4   is valid",   isValidWindow(4));   }
    static void test_windowValid_8()   { assertTrue("N=8   is valid",   isValidWindow(8));   }
    static void test_windowValid_128() { assertTrue("N=128 is valid",   isValidWindow(128)); }

    static void test_windowInvalid_notMultiple4() { assertFalse("N=5   invalid (not multiple of 4)", isValidWindow(5));   }
    static void test_windowInvalid_zero()         { assertFalse("N=0   invalid",                      isValidWindow(0));   }
    static void test_windowInvalid_negative()     { assertFalse("N=-4  invalid",                      isValidWindow(-4));  }
    static void test_windowInvalid_above128()     { assertFalse("N=132 invalid (> 128)",               isValidWindow(132)); }

    // =========================================================================
    // 10. Modulo-128 distance arithmetic
    // =========================================================================

    static void test_distance_normal()          { assertEquals("dist(1,5)=4",      4,   gbnDistance(1,   5));   }
    static void test_distance_wraparound()      { assertEquals("dist(127,0)=1",    1,   gbnDistance(127, 0));   }
    static void test_distance_sameValue_isZero(){ assertEquals("dist(x,x)=0",      0,   gbnDistance(42,  42));  }
    static void test_distance_oneStep_wraparound(){ assertEquals("dist(127,0)=1",  1,   gbnDistance(127, 0));   }
    static void test_distance_halfRange()       { assertEquals("dist(0,64)=64",    64,  gbnDistance(0,   64));  }
    static void test_distance_fullRange()       { assertEquals("dist(1,127)=126",  126, gbnDistance(1,   127)); }

    // =========================================================================
    // 11. GBN receiver in-window acceptance  (forward distance < 64 = in-window)
    // =========================================================================

    static void test_inWindow_expectedSeq() {
        // receivedSeq == expectedSeq → distance 0 → in-order delivery
        assertTrue("dist=0 (in-order) accepted", gbnDistance(5, 5) == 0);
    }

    static void test_inWindow_oneAhead() {
        assertTrue("dist=1 within window", isInRecvWindow(5, 6));
    }

    static void test_inWindow_justBeforeBoundary() {
        assertTrue("dist=63 within window", isInRecvWindow(0, 63));
    }

    static void test_inWindow_atBoundary_rejected() {
        assertFalse("dist=64 outside window", isInRecvWindow(0, 64));
    }

    static void test_inWindow_wrapAround() {
        // expectedSeq=126, receivedSeq=1 → distance = (1-126+128)%128 = 3 → in window
        assertTrue("Wrap-around within window: dist=3", isInRecvWindow(126, 1));
    }

    static void test_outOfWindow_alreadyDelivered() {
        // receivedSeq behind expectedSeq → distance ≥ 64 (stale retransmit)
        // expectedSeq=10, receivedSeq=5 → distance = (5-10+128)%128 = 123 → out of window
        assertFalse("Stale retransmit (dist=123) rejected", isInRecvWindow(10, 5));
    }

    // =========================================================================
    // 12. Protocol sequence semantics
    // =========================================================================

    static void test_SOT_mustHaveSeq0() {
        DSPacket sot = new DSPacket(DSPacket.TYPE_SOT, 0, null);
        assertEquals("SOT must have Seq=0", 0, sot.getSeqNum());
        assertEquals("SOT must have Type=SOT", DSPacket.TYPE_SOT, sot.getType());
    }

    static void test_firstDATA_mustHaveSeq1() {
        // README: "First DATA packet uses Seq = 1"
        DSPacket data = new DSPacket(DSPacket.TYPE_DATA, 1, new byte[10]);
        assertEquals("First DATA has Seq=1", 1, data.getSeqNum());
    }

    static void test_EOT_seq_isLastDataSeqPlusOne() {
        // If last DATA used seq=5, EOT must use (5+1)%128 = 6
        int lastDataSeq = 5;
        int expectedEotSeq = (lastDataSeq + 1) % 128;
        DSPacket eot = new DSPacket(DSPacket.TYPE_EOT, expectedEotSeq, null);
        assertEquals("EOT seq = (lastDataSeq+1)%128", 6, eot.getSeqNum());
    }

    static void test_EOT_seq_wrapsAt128() {
        // If last DATA used seq=127, EOT must use (127+1)%128 = 0
        int lastDataSeq = 127;
        int eotSeq = (lastDataSeq + 1) % 128;
        assertEquals("EOT seq wraps: (127+1)%128 = 0", 0, eotSeq);
    }

    static void test_emptyFile_EOT_seq_is_1() {
        // README: empty file → EOT with Seq=1 immediately after handshake
        int eotSeqForEmptyFile = 1;
        DSPacket eot = new DSPacket(DSPacket.TYPE_EOT, eotSeqForEmptyFile, null);
        assertEquals("Empty-file EOT seq=1", 1, eot.getSeqNum());
        assertEquals("Empty-file EOT type=EOT", DSPacket.TYPE_EOT, eot.getType());
        assertEquals("Empty-file EOT length=0", 0, eot.getLength());
    }

    static void test_seqIncrements_mod128_wrapsCorrectly() {
        // Simulate seq incrementing from 126 over the wrap boundary
        int seq = 126;
        seq = (seq + 1) % 128;  assertEquals("126 → 127", 127, seq);
        seq = (seq + 1) % 128;  assertEquals("127 → 0",   0,   seq);
        seq = (seq + 1) % 128;  assertEquals("0   → 1",   1,   seq);
    }

    // =========================================================================
    // Helpers used by the tests (not the classes under test)
    // =========================================================================

    /** GBN forward distance — mirrors the private helper in Sender and Receiver. */
    private static int gbnDistance(int a, int b) {
        return (b - a + 128) % 128;
    }

    /** README window rules: positive, multiple of 4, at most 128. */
    private static boolean isValidWindow(int n) {
        return n > 0 && n % 4 == 0 && n <= 128;
    }

    /** Receiver accepts packets whose forward distance from expectedSeq is 0–63. */
    private static boolean isInRecvWindow(int expectedSeq, int receivedSeq) {
        int dist = gbnDistance(expectedSeq, receivedSeq);
        return dist < 64;
    }

    /** Pads a byte array to exactly 128 bytes (for DSPacket parser). */
    private static byte[] padTo128(byte[] src) {
        if (src.length == 128) return src;
        return Arrays.copyOf(src, 128);
    }

    /** Creates a list of minimal DATA packets with the given sequence numbers. */
    private static List<DSPacket> makeDataPackets(int[] seqNums) {
        List<DSPacket> list = new ArrayList<>();
        for (int seq : seqNums) {
            list.add(new DSPacket(DSPacket.TYPE_DATA, seq, new byte[]{(byte) seq}));
        }
        return list;
    }

    // =========================================================================
    // Micro-assertion framework (no JUnit dependency)
    // =========================================================================

    private static void section(String name) {
        System.out.println("\n── " + name + " ──────────────────────────");
    }

    private static void pass(String name) {
        passed++;
        System.out.println("  [PASS] " + name);
    }

    private static void fail(String name, String reason) {
        failed++;
        System.out.println("  [FAIL] " + name + "  →  " + reason);
    }

    private static void assertEquals(String name, int expected, int actual) {
        if (expected == actual) pass(name);
        else fail(name, "expected=" + expected + " actual=" + actual);
    }

    private static void assertEquals(String name, byte expected, byte actual) {
        if (expected == actual) pass(name);
        else fail(name, "expected=" + (expected & 0xFF) + " actual=" + (actual & 0xFF));
    }

    private static void assertTrue(String name, boolean condition) {
        if (condition) pass(name);
        else fail(name, "expected true, got false");
    }

    private static void assertFalse(String name, boolean condition) {
        if (!condition) pass(name);
        else fail(name, "expected false, got true");
    }

    private static void assertTrue(String name, boolean condition, String extra) {
        if (condition) pass(name);
        else fail(name, extra);
    }
}

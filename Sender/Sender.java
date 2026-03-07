import java.io.FileInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DS-FTP Sender
 * -------------
 * Implements Stop-and-Wait RDT 3.0 protocol.
 * Sends file data over UDP with reliability guarantees.
 *
 * Command-line:
 *   java Sender <rcv_ip> <rcv_data_port> <sender_ack_port> <input_file> <timeout_ms> [window_size]
 *   
 * Note: Omit window_size for Stop-and-Wait mode.
 */
public class Sender {

    private static final int MAX_CONSECUTIVE_TIMEOUTS = 3;

    public static void main(String[] args) {
        
        // Validate arguments
        if (args.length < 5 || args.length > 6) {
            System.err.println("Usage: java Sender <rcv_ip> <rcv_data_port> <sender_ack_port> <input_file> <timeout_ms> [window_size]");
            System.exit(1);
        }

        // Parse command-line arguments.
        String receiverIP = args[0];
        int rcvDataPort = Integer.parseInt(args[1]);
        int senderAckPort = Integer.parseInt(args[2]);
        String inputFile = args[3];
        int timeoutMs = Integer.parseInt(args[4]);
        
        // Check if Go-Back-N mode (has window_size parameter).
        boolean gbnMode = (args.length == 6);
        int windowSize = 0;
        if (gbnMode) {
            windowSize = Integer.parseInt(args[5]);
            if (windowSize <= 0 || windowSize % 4 != 0 || windowSize > 128) {
                System.err.println("Error: window_size must be a positive multiple of 4 and at most 128.");
                System.exit(1);
            }
        }

        DatagramSocket socket = null;
        FileInputStream fis = null;

        try {
            // Create socket for sending data and receiving ACKs.
            socket = new DatagramSocket(senderAckPort);
            socket.setSoTimeout(timeoutMs);
            
            InetAddress receiverAddress = InetAddress.getByName(receiverIP);
            
            if (gbnMode) {
                System.out.println("Sender running in Go-Back-N mode (N=" + windowSize + ")");
            } else {
                System.out.println("Sender running in Stop-and-Wait mode");
            }
            System.out.println("Timeout: " + timeoutMs + " ms");

            // Start timing.
            long startTime = System.currentTimeMillis();

            // Phase 1: Handshake (Send SOT).
            System.out.println("Starting handshake...");
            if (!sendAndWaitForAck(socket, receiverAddress, rcvDataPort, 
                                   DSPacket.TYPE_SOT, 0, null, timeoutMs)) {
                System.err.println("Unable to transfer file.");
                System.exit(1);
            }
            System.out.println("Handshake complete");

            // Phase 2: Data Transfer.
            fis = new FileInputStream(inputFile);
            int currentSeq;
            if (gbnMode) {
                // Go-Back-N mode — sliding window with ChaosEngine permutation.
                currentSeq = runGBN(socket, fis, receiverAddress, rcvDataPort, timeoutMs, windowSize);
            } else {
                // Stop-and-Wait mode (original logic preserved).
                currentSeq = 1;  // First DATA packet uses seq=1.
                byte[] buffer = new byte[DSPacket.MAX_PAYLOAD_SIZE];
                int bytesRead;
                boolean isEmptyFile = true;

                while ((bytesRead = fis.read(buffer)) != -1) {
                    isEmptyFile = false;
                    
                    // Create payload matching size read.
                    byte[] payload = new byte[bytesRead];
                    System.arraycopy(buffer, 0, payload, 0, bytesRead);

                    // Send DATA packet and wait for ACK.
                    System.out.println("Sending DATA (Seq=" + currentSeq + ", Length=" + bytesRead + ")");
                    if (!sendAndWaitForAck(socket, receiverAddress, rcvDataPort,
                                           DSPacket.TYPE_DATA, currentSeq, payload, timeoutMs)) {
                        System.err.println("Unable to transfer file.");
                        System.exit(1);
                    }

                    currentSeq = (currentSeq + 1) % 128;
                }

                // Handle empty file case.
                if (isEmptyFile) {
                    System.out.println("Empty file detected");
                    currentSeq = 1;  // EOT uses seq=1 for empty files.
                }
            }

            // Phase 3: Teardown (Send EOT).
            System.out.println("Sending EOT (Seq=" + currentSeq + ")");
            if (!sendAndWaitForAck(socket, receiverAddress, rcvDataPort,
                                   DSPacket.TYPE_EOT, currentSeq, null, timeoutMs)) {
                System.err.println("Unable to transfer file.");
                System.exit(1);
            }

            // Calculate and print total transmission time.
            long endTime = System.currentTimeMillis();
            double totalTime = (endTime - startTime) / 1000.0;
            System.out.printf("Total Transmission Time: %.2f seconds%n", totalTime);

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // Clean up resources.
            if (fis != null) {
                try {
                    fis.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        }
    }

    /**
     * Sends a packet and waits for the corresponding ACK with retransmission on timeout.
     * 
     * @return true if ACK received successfully, false if critical failure (3 consecutive timeouts).
     */
    private static boolean sendAndWaitForAck(DatagramSocket socket, InetAddress address, int port,
                                             byte packetType, int seqNum, byte[] payload, int timeoutMs) {
        int consecutiveTimeouts = 0;
        byte[] receiveBuffer = new byte[DSPacket.MAX_PACKET_SIZE];

        while (true) {
            try {
                // Create and send packet.
                DSPacket packet = new DSPacket(packetType, seqNum, payload);
                byte[] packetData = packet.toBytes();
                DatagramPacket sendPacket = new DatagramPacket(packetData, packetData.length, address, port);
                socket.send(sendPacket);

                String typeStr = getPacketTypeName(packetType);
                System.out.println("Sent " + typeStr + " (Seq=" + seqNum + ")");

                // Wait for ACK.
                DatagramPacket receivePacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                socket.receive(receivePacket);

                // Parse received packet.
                DSPacket ackPacket = new DSPacket(receivePacket.getData());

                // Verify it's an ACK for our sequence number.
                if (ackPacket.getType() == DSPacket.TYPE_ACK && ackPacket.getSeqNum() == seqNum) {
                    System.out.println("Received ACK (Seq=" + seqNum + ")");
                    return true;
                } else {
                    // Wrong ACK - ignore and wait for timeout.
                    System.out.println("Received unexpected ACK (Seq=" + ackPacket.getSeqNum() + ") - Expected " + seqNum);
                }

            } catch (SocketTimeoutException e) {
                consecutiveTimeouts++;
                System.out.println("Timeout occurred (attempt " + consecutiveTimeouts + "/" + MAX_CONSECUTIVE_TIMEOUTS + ")");

                if (consecutiveTimeouts >= MAX_CONSECUTIVE_TIMEOUTS) {
                    System.err.println("Critical failure: 3 consecutive timeouts for Seq=" + seqNum);
                    return false;
                }

                System.out.println("Retransmitting...");

            } catch (IOException e) {
                System.err.println("Network error: " + e.getMessage());
                return false;
            }
        }
    }

    /**
     * Helper method to get packet type name for logging.
     */
    private static String getPacketTypeName(byte type) {
        switch (type) {
            case DSPacket.TYPE_SOT:  return "SOT";
            case DSPacket.TYPE_DATA: return "DATA";
            case DSPacket.TYPE_ACK:  return "ACK";
            case DSPacket.TYPE_EOT:  return "EOT";
            default: return "UNKNOWN";
        }
    }

    // =========================================================================
    // Go-Back-N implementation
    // =========================================================================

    /**
     * Transfers file data using Go-Back-N sliding window protocol.
     *
     * Sending: packets are batched in groups of 4 and permuted by
     * ChaosEngine.permutePackets() before transmission (i, i+1, i+2, i+3) →
     * (i+2, i, i+3, i+1). Any remainder fewer than 4 is sent in normal order.
     *
     * Retransmit: on SocketTimeoutException, all un-ACKed packets from base
     * through nextSeq-1 are retransmitted in ascending order (no re-permutation).
     *
     * Failure: 3 consecutive timeouts with no window progress → exit(1).
     *
     * @return the EOT sequence number (equals base == nextSeq after all DATA ACKed,
     *         or 1 for an empty file per spec)
     */
    private static int runGBN(DatagramSocket socket, FileInputStream fis,
                               InetAddress address, int port,
                               int timeoutMs, int N) throws IOException {

        int base                = 1;   // oldest un-ACKed sequence number
        int nextSeq             = 1;   // next sequence number to assign
        int consecutiveTimeouts = 0;

        // sendBuffer: seq → raw 128-byte packet bytes kept for retransmission.
        Map<Integer, byte[]> sendBuffer = new LinkedHashMap<>();

        boolean fileExhausted = false;
        byte[]  readBuf       = new byte[DSPacket.MAX_PAYLOAD_SIZE];
        byte[]  recvBuf       = new byte[DSPacket.MAX_PACKET_SIZE];

        while (!fileExhausted || base != nextSeq) {

            // ── Step 1: Fill the window with newly read packets ───────────────
            List<DSPacket> newPackets = new ArrayList<>();
            while (gbnDistance(base, nextSeq) < N && !fileExhausted) {
                int bytesRead = fis.read(readBuf);
                if (bytesRead == -1) {
                    fileExhausted = true;
                    break;
                }
                byte[] payload = new byte[bytesRead];
                System.arraycopy(readBuf, 0, payload, 0, bytesRead);

                DSPacket pkt = new DSPacket(DSPacket.TYPE_DATA, nextSeq, payload);
                sendBuffer.put(nextSeq, pkt.toBytes());
                System.out.println("Sending DATA (Seq=" + nextSeq + ", Length=" + bytesRead + ")");
                newPackets.add(pkt);
                nextSeq = (nextSeq + 1) % 128;
            }

            // Empty file: nothing queued and nothing outstanding.
            if (newPackets.isEmpty() && base == nextSeq) {
                System.out.println("Empty file detected");
                return 1;   // per spec: EOT seq=1 for empty files
            }

            // ── Step 2: Transmit via ChaosEngine permutation ─────────────────
            // Groups of exactly 4 are permuted; any remainder is sent in order.
            int idx = 0;
            while (idx + 4 <= newPackets.size()) {
                List<DSPacket> group    = new ArrayList<>(newPackets.subList(idx, idx + 4));
                List<DSPacket> permuted = ChaosEngine.permutePackets(group);
                for (DSPacket p : permuted) {
                    byte[] data = p.toBytes();
                    socket.send(new DatagramPacket(data, data.length, address, port));
                    System.out.println("  [permuted] Seq=" + p.getSeqNum()
                            + " Length=" + p.getLength());
                }
                idx += 4;
            }
            for (; idx < newPackets.size(); idx++) {
                DSPacket p    = newPackets.get(idx);
                byte[]   data = p.toBytes();
                socket.send(new DatagramPacket(data, data.length, address, port));
                System.out.println("  [send] Seq=" + p.getSeqNum()
                        + " Length=" + p.getLength());
            }

            // ── Step 3: Receive one ACK, or timeout and retransmit ───────────
            try {
                DatagramPacket recvPkt = new DatagramPacket(recvBuf, recvBuf.length);
                socket.receive(recvPkt);
                DSPacket ack = new DSPacket(recvPkt.getData());

                if (ack.getType() == DSPacket.TYPE_ACK) {
                    int ackSeq      = ack.getSeqNum();
                    int newBase     = (ackSeq + 1) % 128;
                    int advanceDist = gbnDistance(base, newBase);
                    int windowUsed  = gbnDistance(base, nextSeq);

                    System.out.println("Received ACK (Seq=" + ackSeq + ")");

                    if (advanceDist > 0 && advanceDist <= windowUsed) {
                        // Cumulative ACK advances the window — purge ACKed entries.
                        int seq = base;
                        while (seq != newBase) {
                            sendBuffer.remove(seq);
                            seq = (seq + 1) % 128;
                        }
                        base = newBase;
                        consecutiveTimeouts = 0;   // reset counter on progress
                        System.out.println("Window advanced → base=" + base);
                    } else {
                        System.out.println("Duplicate/stale ACK ignored (base=" + base + ")");
                    }
                }

            } catch (SocketTimeoutException e) {
                consecutiveTimeouts++;
                System.out.println("Timeout (attempt " + consecutiveTimeouts
                        + "/" + MAX_CONSECUTIVE_TIMEOUTS + ")");

                if (consecutiveTimeouts >= MAX_CONSECUTIVE_TIMEOUTS) {
                    System.err.println("Unable to transfer file.");
                    System.exit(1);
                }

                // Retransmit all un-ACKed packets base → nextSeq-1 in order.
                System.out.println("Retransmitting window [base=" + base
                        + " ... " + ((nextSeq - 1 + 128) % 128) + "]");
                int seq = base;
                while (seq != nextSeq) {
                    byte[] data = sendBuffer.get(seq);
                    if (data != null) {
                        socket.send(new DatagramPacket(data, data.length, address, port));
                        System.out.println("  [retransmit] Seq=" + seq);
                    }
                    seq = (seq + 1) % 128;
                }
            }
        }

        // All DATA packets ACKed — return EOT sequence number.
        return nextSeq;
    }

    /**
     * Returns the modulo-128 forward distance from a to b.
     * gbnDistance(1,5)=4;  gbnDistance(127,0)=1;  gbnDistance(x,x)=0.
     */
    private static int gbnDistance(int a, int b) {
        return (b - a + 128) % 128;
    }
}

import java.io.FileInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;

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
        if (args.length == 6) {
            System.err.println("No Go-Back-N implementation yet. Use Stop-and-Wait (omit window_size).");
            System.exit(1);
        }

        DatagramSocket socket = null;
        FileInputStream fis = null;

        try {
            // Create socket for sending data and receiving ACKs.
            socket = new DatagramSocket(senderAckPort);
            socket.setSoTimeout(timeoutMs);
            
            InetAddress receiverAddress = InetAddress.getByName(receiverIP);
            
            System.out.println("Sender running in Stop-and-Wait mode");
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
            int currentSeq = 1;  // First DATA packet uses seq=1.
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
}

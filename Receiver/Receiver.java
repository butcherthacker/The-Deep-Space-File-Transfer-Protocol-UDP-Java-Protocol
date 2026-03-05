import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/**
 * DS-FTP Receiver
 * ---------------
 * Implements Stop-and-Wait RDT 3.0 protocol.
 * Receives file data over UDP with reliability guarantees.
 *
 * Command-line:
 *   java Receiver <sender_ip> <sender_ack_port> <rcv_data_port> <output_file> <RN>
 */
public class Receiver {

    public static void main(String[] args) {
        
        // Validate arguments.
        if (args.length != 5) {
            System.err.println("Usage: java Receiver <sender_ip> <sender_ack_port> <rcv_data_port> <output_file> <RN>");
            System.exit(1);
        }

        // Parse command-line arguments.
        String senderIP = args[0];
        int senderAckPort = Integer.parseInt(args[1]);
        int rcvDataPort = Integer.parseInt(args[2]);
        String outputFile = args[3];
        int reliabilityNumber = Integer.parseInt(args[4]);

        DatagramSocket socket = null;
        FileOutputStream fos = null;

        try {
            // Create socket to listen for data packets.
            socket = new DatagramSocket(rcvDataPort);
            InetAddress senderAddress = InetAddress.getByName(senderIP);

            System.out.println("Receiver listening on port " + rcvDataPort);

            // Variables for protocol state.
            int expectedSeq = 0;  // Start expecting SOT (seq=0).
            int ackCount = 0;     // Track ACKs for ChaosEngine.
            byte[] buffer = new byte[DSPacket.MAX_PACKET_SIZE];
            boolean handshakeComplete = false;
            boolean transferComplete = false;

            // Phase 1: Handshake (wait for SOT).
            while (!handshakeComplete) {
                DatagramPacket receivePacket = new DatagramPacket(buffer, buffer.length);
                socket.receive(receivePacket);

                DSPacket packet = new DSPacket(receivePacket.getData());

                if (packet.getType() == DSPacket.TYPE_SOT && packet.getSeqNum() == 0) {
                    System.out.println("Received SOT (Seq=0)");
                    
                    // Send ACK for SOT.
                    ackCount++;
                    if (!ChaosEngine.shouldDrop(ackCount, reliabilityNumber)) {
                        sendAck(socket, senderAddress, senderAckPort, 0);
                        System.out.println("Sent ACK (Seq=0)");
                    } else {
                        System.out.println("ACK (Seq=0) DROPPED by ChaosEngine");
                    }
                    
                    expectedSeq = 1;  // Next we expect first DATA packet.
                    handshakeComplete = true;
                    
                    // Open output file for writing.
                    fos = new FileOutputStream(outputFile);
                }
            }

            // Phase 2: Data Transfer.
            while (!transferComplete) {
                DatagramPacket receivePacket = new DatagramPacket(buffer, buffer.length);
                socket.receive(receivePacket);

                DSPacket packet = new DSPacket(receivePacket.getData());
                int receivedSeq = packet.getSeqNum();

                // Check for DATA packet.
                if (packet.getType() == DSPacket.TYPE_DATA) {
                    
                    if (receivedSeq == expectedSeq) {
                        // In-order packet - write to file.
                        System.out.println("Received DATA (Seq=" + receivedSeq + ", Length=" + packet.getLength() + ")");
                        
                        if (packet.getLength() > 0) {
                            fos.write(packet.getPayload());
                        }
                        
                        // Send ACK for this packet.
                        ackCount++;
                        if (!ChaosEngine.shouldDrop(ackCount, reliabilityNumber)) {
                            sendAck(socket, senderAddress, senderAckPort, receivedSeq);
                            System.out.println("Sent ACK (Seq=" + receivedSeq + ")");
                        } else {
                            System.out.println("ACK (Seq=" + receivedSeq + ") DROPPED by ChaosEngine");
                        }
                        
                        // Move to next expected sequence.
                        expectedSeq = (expectedSeq + 1) % 128;
                        
                    } else {
                        // Duplicate or out-of-order packet.
                        System.out.println("Received duplicate/out-of-order DATA (Seq=" + receivedSeq + ") - Expected " + expectedSeq);
                        
                        // Resend ACK for last correctly received packet.
                        int lastAckSeq = (expectedSeq - 1 + 128) % 128;
                        ackCount++;
                        if (!ChaosEngine.shouldDrop(ackCount, reliabilityNumber)) {
                            sendAck(socket, senderAddress, senderAckPort, lastAckSeq);
                            System.out.println("Resent ACK (Seq=" + lastAckSeq + ")");
                        } else {
                            System.out.println("ACK (Seq=" + lastAckSeq + ") DROPPED by ChaosEngine");
                        }
                    }
                }
                
                // Check for EOT packet.
                else if (packet.getType() == DSPacket.TYPE_EOT) {
                    System.out.println("Received EOT (Seq=" + receivedSeq + ")");
                    
                    // Send ACK for EOT.
                    ackCount++;
                    if (!ChaosEngine.shouldDrop(ackCount, reliabilityNumber)) {
                        sendAck(socket, senderAddress, senderAckPort, receivedSeq);
                        System.out.println("Sent ACK for EOT (Seq=" + receivedSeq + ")");
                    } else {
                        System.out.println("ACK for EOT (Seq=" + receivedSeq + ") DROPPED by ChaosEngine");
                    }
                    
                    transferComplete = true;
                }
            }

            System.out.println("File transfer complete: " + outputFile);

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // Clean up resources.
            if (fos != null) {
                try {
                    fos.close();
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
     * Helper method to send an ACK packet.
     */
    private static void sendAck(DatagramSocket socket, InetAddress address, int port, int seqNum) {
        try {
            DSPacket ackPacket = new DSPacket(DSPacket.TYPE_ACK, seqNum, null);
            byte[] ackData = ackPacket.toBytes();
            DatagramPacket sendPacket = new DatagramPacket(ackData, ackData.length, address, port);
            socket.send(sendPacket);
        } catch (IOException e) {
            System.err.println("Failed to send ACK: " + e.getMessage());
        }
    }
}

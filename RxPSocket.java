import java.net.*;
import java.io.*;
import java.util.*;

public class RxPSocket {

  public static final int MAX_PACKET_SIZE = 2000;

  // Private variables used in several states
  private DatagramSocket dgSocket;
  private int windowSize = 5; // Stop and Wait length

  // Variables that are set in the LISTEN/ACCEPT phases.
  private InetAddress acceptedAddress;

  private static ConnectionManager connectionManager;

  private short srcPort;
  private short destPort;

  private InetAddress srcAddress;
  private InetAddress destAddress;

  public boolean isClient;
  public boolean isConnected;
  public static boolean isClientSending;

  // TODO: Add a timer for timeouts.

  public RxPSocket()
      throws SocketException {
    dgSocket = new DatagramSocket();
    connectionManager = new ConnectionManager();
    this.isClient = false;
    this.isConnected = false;
  }

  public RxPSocket(int port) throws SocketException {

    this.srcPort = (short) port;

    dgSocket = new DatagramSocket(port);
    connectionManager = new ConnectionManager();
    this.isClient = false;
    this.isConnected = false;
	}

	public RxPSocket(int port, InetAddress address) throws SocketException {

    this.srcPort = (short) port;
    this.srcAddress = address;

    dgSocket = new DatagramSocket(port, address);
    connectionManager = new ConnectionManager();
    this.isClient = false;
    this.isConnected = false;
	}

  public RxPSocket(boolean isClient, int port, InetAddress address) throws SocketException {

    this.isClient = isClient;
    this.srcPort = (short) port;
    this.srcAddress = address;

    dgSocket = new DatagramSocket(port, address);
    connectionManager = new ConnectionManager();
    this.isConnected = false;
  }

  public static RxPSocket newRxPClientSocket(int port, InetAddress address) throws IOException {
    return new RxPSocket(true, port, address);
  }

  public void setWindowSize(int windowSize) {
    this.windowSize = windowSize;
  }

  // public boolean isConnected() {
  //   return connectionManager.getConnection(dgSocket.getPort(), dgSocket.getHostPort());
  // }

  /* Connect, Send, and Receive Methods */
  public void connect(InetAddress address, int port) {
    this.isConnected = true;
    this.isClientSending = true;

    this.destAddress = address;
    this.destPort = (short) port;

    dgSocket.connect(address, port);

    // Initiate handshake
    short src = (short) dgSocket.getLocalPort();
    short dest = (short) dgSocket.getPort();

    Connection connection = connectionManager.getConnection(dest, src);

    // Initial Handshake Packet
    RxPPacket handshakePacket = connectionManager.getNextHandshakePacket(connection);

    try {

      DatagramPacket dg = handshakePacket.asDatagramPacket();
      connectionManager.updateConnection(handshakePacket);

      // Send Initial Handshake Packet
      dgSocket.send(dg);

    } catch (IOException e) {
      // System.out.println("Handshake Packet did not send");
    }

    // Listen for next handshake packet
    try {
      this.handshake("Client", dest, src);
    } catch (IOException e) {
      // // System.out.println("Could not receive handshake packet");
    }

    this.isClientSending = false;
  }

  public void listen() {

    DatagramPacket dgPacket = new DatagramPacket(new byte[MAX_PACKET_SIZE], MAX_PACKET_SIZE);

    try {
      this.handshake("Server", (short) 0, (short) 0);
    } catch (IOException ioe) {
      // // System.out.println("Handshake Packet IOException");
    }
  }

  public void handshake(String hostType, short dest, short source) throws IOException {

    DatagramPacket dgPacket = new DatagramPacket(new byte[MAX_PACKET_SIZE], MAX_PACKET_SIZE);

    Connection serverConnection = null;
    boolean allowedToSendData = false;
    boolean sendingData = false;

    // Used to resend lost handshake packets
    int timeoutMillis = 300;
    try {
      dgSocket.setSoTimeout(timeoutMillis);
    } catch(SocketException se) {
      // TODO: Add error handling
    }

    while (!sendingData) {
      try {
        dgSocket.receive(dgPacket);
      }
      catch(SocketTimeoutException ste) {
        if (connectionManager.getConnection(dest, source).isAllowedToSendData()) {
          // If the client has moved into the sending data state, so let's give one
          // last shot.
          connectionManager.getConnection(dest, source).setSendingData(true);
          break;
        }
        else if (serverConnection != null ||  hostType.equals("Client")) { // successfully connected
          sendHandshakePacket(dest, source, dgPacket, false);
          // // System.out.println("Timeout");
        }
        continue;
      }

      RxPPacket receivedRxPPacket = new RxPPacket(dgPacket.getData());

      // Check the checksum to make sure no corruption occurred
      long checksum = receivedRxPPacket.getChecksum();
      long currentChecksum = receivedRxPPacket.calculateChecksum();
      if (currentChecksum == checksum) {

        // Initialize servers connection after first non corrupt packet is received
        if (hostType.equals("Server") && serverConnection == null) {
          serverConnection = connectionManager.getConnection(receivedRxPPacket);
          dest = receivedRxPPacket.getDestPort();
          source = receivedRxPPacket.getSrcPort();
        }

        // If it's a handshake packet, send the next handshake packet
        if (connectionManager.updateConnection(receivedRxPPacket)) {

          // // System.out.println("Received Handshake Request: " + connectionManager.getConnection(receivedRxPPacket).connectionStateToString());
          sendHandshakePacket(dest, source, dgPacket, true);

          // Update while loop condition
          Connection connection = connectionManager.getConnection(dest, source);
          allowedToSendData = connection.isAllowedToSendData();
          sendingData = connection.isSendingData();
        }
      } else {
        // // System.out.println("Handshake Packet Corrupted");
        sendHandshakePacket(dest, source, dgPacket, false);
      }
    }
    // do this on successful receive
    dgSocket.connect(dgPacket.getAddress(), dgPacket.getPort());
  }

  private void sendHandshakePacket(short dest, short source, DatagramPacket dgPacket, boolean sendNextPacket)
                throws IOException {

    this.isClientSending = true;
    Connection connection = connectionManager.getConnection(dest, source);
    RxPPacket handshakePacket = sendNextPacket ?
              connectionManager.getNextHandshakePacket(connection):
              connectionManager.getLastHandshakePacket(connection);

    // Handshake Packet is null if it's not necessary to send anymore handshake packets
    if (handshakePacket != null) {

      if (sendNextPacket)
        connectionManager.updateConnection(handshakePacket);

      if (dgPacket.getAddress() == null || dgPacket.getPort() == 0)
      {
        dgPacket.setAddress(dgSocket.getInetAddress());
        dgPacket.setPort(dgSocket.getPort());
      }

      DatagramPacket dg = handshakePacket.asDatagramPacket();
      dg.setAddress(dgPacket.getAddress());
      dg.setPort(dgPacket.getPort());
      dgSocket.send(dg);

      // // System.out.println("Sending Handshake Response: " + connectionManager.getConnection(handshakePacket).connectionStateToString());
      // // System.out.println();
    }
    this.isClientSending = false;
  }

  // Socket API Method
  public void close() {
    this.closeClientConnection(this.srcPort, this.destPort);
    connectionManager.removeConnection(this.srcPort, this.destPort);
    this.isConnected = false;
  }

  private void closeClientConnection(short src, short dest) {

    Connection connection = connectionManager.getConnection(src, dest);

    while (!connectionManager.getConnection(src, dest).isClientSentACK()) {

      // Initial FIN Packet
      RxPPacket rxpPacketFIN = connectionManager.getNextClosePacket(connection);
      connectionManager.updateConnection(rxpPacketFIN);

      // Send FIN Packet, will be ACK'd within send
      this.sendRxPPackets(new RxPPacket[] { rxpPacketFIN });

      // Receive FIN, send ACK
      try {
        this.receive();
      } catch (IOException ioe) {
      }

      if (connectionManager.getConnection(src, dest).isClientSentACK()) {
        this.dgSocket.close();
        // // System.out.println("Closed Socket");
      }
    }
  }

  private class ResendTimerTask extends TimerTask {
    private int oldestUnackedPointer;
    private int windowSize;
    private RxPPacket[] sendBuffer;
    private int numTimesSilent = 0;

    public ResendTimerTask(int oldestUnackedPointer, int windowSize, RxPPacket[] sendBuffer) {
      this.oldestUnackedPointer = oldestUnackedPointer;
      this.windowSize = windowSize;
      this.sendBuffer = sendBuffer;
    }

    public boolean incrementOldestUnackedPointer() {
      numTimesSilent = 0;
      if (oldestUnackedPointer < sendBuffer.length) {
        oldestUnackedPointer++;
        return true;
      }
      return false;
    }

    public int getNumTimesNoResponse() {
      return numTimesSilent;
    }

    public void resetTimesNoResponse() {
      numTimesSilent = 0;
    }

    public void run() {

      Connection connection = connectionManager.getConnection(sendBuffer[0]);

      // // System.out.println("Resending " + Math.min(windowSize, sendBuffer.length-oldestUnackedPointer) + " packets");
      numTimesSilent++;

      // Send all unsent packets
      for (int i = 0; i < windowSize && i < sendBuffer.length-oldestUnackedPointer; i++) {
        try {
          DatagramPacket dg = sendBuffer[i+oldestUnackedPointer].asDatagramPacket();
          dg.setAddress(dgSocket.getInetAddress());
          dg.setPort(dgSocket.getPort());
          dgSocket.send(dg);
        } catch(IOException e) {
          // ACtually do not care. Timer will requeue these.
        }
      }
    }
  }

  // Application sends a buffer, send creates RxPPacket from buffer then...
  // TODO: send window of packets
  public boolean send(byte[] sendBuffer) {

    this.isClientSending = true;
    int oldestUnackedPointer = 0;
    boolean PSH_ACKsent = false;

    int packetBufferLength = (int) Math.ceil((double) (sendBuffer.length) / RxPPacket.DEFAULT_PACKET_SIZE);

    // Set a receive timeout so we can resend after a time, rather
    // than just having it block
    int timeoutMillis = 300;
    try {
      dgSocket.setSoTimeout(timeoutMillis);
    } catch(SocketException se) {
      // TODO: Add error handling
    }

    RxPPacket[] packetBuffer = new RxPPacket[packetBufferLength];
    // Create payload for each RxPPacket based on default packet size
    for (int i = 0; i < packetBuffer.length; i += 1) {
      byte[] payload = Arrays.copyOfRange(sendBuffer, i*RxPPacket.DEFAULT_PACKET_SIZE,
            (i+1)*RxPPacket.DEFAULT_PACKET_SIZE);

      short src = (short) dgSocket.getLocalPort();
      short dest = (short) dgSocket.getPort();
      int seqNum = i+1;
      int ackNum = -2;
      boolean fin = false;
      boolean syn = false;
      boolean ack = false;
      boolean psh = (i == packetBuffer.length - 1) ? true : false;
      RxPPacket newPacket = new RxPPacket(src,
                                          dest,
                                          seqNum,
                                          ackNum,
                                          fin,
                                          syn,
                                          ack,
                                          psh);
      newPacket.setPayload(payload);
      newPacket.setChecksum(newPacket.calculateChecksum());

      packetBuffer[i] = newPacket;
    }

    // The timer task is charged with resending all packets in the window
    // every time it is fired. It needs to be encapsulated in a class because
    // Async tasks need to work on constants, not variables.
    Timer timer = new Timer();

    // Resend the current data every 300ms there is not an ACKed packet.
    ResendTimerTask resendTask = new ResendTimerTask(oldestUnackedPointer, windowSize, packetBuffer);
    timer.scheduleAtFixedRate(resendTask, 0, timeoutMillis);

    // TODO: 2) Set Window Size
    byte[] packetPayload = new byte[RxPPacket.DEFAULT_PACKET_SIZE];
    DatagramPacket dgPacket = new DatagramPacket(packetPayload, packetPayload.length);

    while (true) {
      try {
        // Wait for the ACK
        dgSocket.receive(dgPacket);

        resendTask.resetTimesNoResponse();
        RxPPacket rxpPacket = new RxPPacket(dgPacket);

        if (!rxpPacket.isSYN() && rxpPacket.isACK()) {

          int ackNumber = rxpPacket.getACKNum();
          int expectedAckNumber = packetBuffer[oldestUnackedPointer].getSeqNum();
          // // System.out.println("ACK Received: " + ackNumber);

          // If the ACK is equal to the oldest unACKed packet, move the index by one
          // Otherwise, wait until it comes OR timeout occurs.
          if (oldestUnackedPointer < packetBuffer.length &&
              ackNumber == expectedAckNumber) {
            oldestUnackedPointer++;
            boolean success = resendTask.incrementOldestUnackedPointer();

            // Reset timer
            timer.cancel();
            timer.purge();
            timer = new Timer();
            resendTask = new ResendTimerTask(oldestUnackedPointer, windowSize, packetBuffer);
            timer.scheduleAtFixedRate(resendTask, timeoutMillis, timeoutMillis);
          }

          if (rxpPacket.isPSH() && ackNumber == expectedAckNumber) {
            // If the received packet is a PSH+ACK, then send a PSH+ACK and quit.
            RxPPacket ackRxPPacket = new RxPPacket();
            ackRxPPacket.setACK(true);
            ackRxPPacket.setACKNum(rxpPacket.getACKNum()+1);
            ackRxPPacket.setSeqNum(rxpPacket.getACKNum()+1);
            ackRxPPacket.setDestPort((short)dgPacket.getPort());
            ackRxPPacket.setSrcPort((short)dgSocket.getLocalPort());
            ackRxPPacket.setPSH(true);
            ackRxPPacket.setChecksum(ackRxPPacket.calculateChecksum());

            // Send ACK+PSH
            DatagramPacket dg = ackRxPPacket.asDatagramPacket();
            dg.setAddress(dgPacket.getAddress());
            dg.setPort(dgPacket.getPort());
            dgSocket.send(dg);
            // // System.out.println("Sending PSH+ACK");
            timer.cancel();
            timer.purge();
            resendTask.cancel();
            this.isClientSending = false;
            return true;
          }
        }
      } catch(IOException e) {
        // System.err.println("Received Nothing " + resendTask.getNumTimesNoResponse() + " Times");
        if (resendTask.getNumTimesNoResponse() == 40) {
          timer.cancel();
          timer.purge();
          resendTask.cancel();
          this.isClientSending = false;
          return false;
        }
      }
    }
  }

  public byte[] receive() throws IOException {
    int expectedSeqNum = 1; // Initial Number

    boolean PSH_ACKsent = false;
    int receiveAttempts = 0;

    int timeoutMillis = 300;
    try {
      dgSocket.setSoTimeout(timeoutMillis);
    } catch(SocketException se) {
      // TODO: Add error handling
    }

    List<RxPPacket> tempRxPPacketList = new ArrayList<>();
    DatagramPacket dgPacket = new DatagramPacket(new byte[MAX_PACKET_SIZE], MAX_PACKET_SIZE);
    // Receive until PSH flag is set or timeout occurs
    while (true) {
      // Receive Datagram from Datagram Socket
      try {
        dgSocket.receive(dgPacket);
      } catch (SocketTimeoutException ste) {
        receiveAttempts++;
        if (receiveAttempts > 20) {
          break;
        }
        else {
          continue;
        }
      }
      receiveAttempts = 0;

      // Create RxPPacket from received Datagram Buffer
      RxPPacket receivedRxPPacket = new RxPPacket(dgPacket.getData());

      // Check the checksum to make sure no corruption occurred
      long checksum = receivedRxPPacket.getChecksum();
      long currentChecksum = receivedRxPPacket.calculateChecksum();

      boolean updateConnection = connectionManager.updateConnection(receivedRxPPacket);
      // // System.out.println("Expected: " + expectedSeqNum + "\tReceived: " + receivedRxPPacket.getSeqNum());
      // // System.out.println("FIN: " + receivedRxPPacket.isFIN());
      // // System.out.println("Update Connection: " + updateConnection);

      if (currentChecksum != checksum) {
        // // System.out.println("Packet Corrupted");
        continue;
      } else if (connectionManager.isTerminatePacket(receivedRxPPacket)) {

        // // System.out.println("Terminate");

        // ACK & call close()

      } else if (!connectionManager.getConnection(receivedRxPPacket).isAllowedToSendData() && updateConnection) {
        // System.out.println("Update Connection: " + true);
        // If the client is still waiting for the fifth handshake, process that instead.
        Connection connection = connectionManager.getConnection(receivedRxPPacket);
        // System.out.println("Received Handshake Request: " + connection.connectionStateToString());
        sendHandshakePacket(receivedRxPPacket.getDestPort(), receivedRxPPacket.getSrcPort(),
            dgPacket, false);
      }
      else if (expectedSeqNum < receivedRxPPacket.getSeqNum()) {
        // System.out.println("Packet Out of Order");
      }
      else if (receivedRxPPacket.isPSH() && receivedRxPPacket.isACK() && PSH_ACKsent) {
        // System.out.println("PSH+ACK Received");
        try {
          Thread.sleep(2000); // Let any remaining packets in the air die
        } catch (InterruptedException ie) {}
        break;
      }
      else {

        // Only add to the list if this is the right packet. ACK any other one.
        if (expectedSeqNum == receivedRxPPacket.getSeqNum() && !receivedRxPPacket.isACK()) {
          tempRxPPacketList.add(receivedRxPPacket);
          expectedSeqNum = receivedRxPPacket.getSeqNum() + 1;
        }
        else {
          // if (!receivedRxPPacket.isACK())
          // it one = 1
            // System.out.println("Packet Out of Order");
        }

        RxPPacket ackRxPPacket;
        if (receivedRxPPacket.isFIN() && !receivedRxPPacket.isSYN()) {

          // System.out.println("Received FIN: Close Connection");
          ackRxPPacket = connectionManager.getNextClosePacket(connectionManager.getConnection(receivedRxPPacket));

          // Send ACK
          DatagramPacket dg = ackRxPPacket.asDatagramPacket();
          dg.setAddress(dgPacket.getAddress());
          dg.setPort(dgPacket.getPort());
          dgSocket.send(dg);

          // System.out.println("Sending ACK in Response to FIN: " + ackRxPPacket.getACKNum());

          connectionManager.updateConnection(ackRxPPacket);

          // Send a FIN (if server, otherwise close)
          if (!connectionManager.getConnection(ackRxPPacket).isClient()) {

            // Server send FIN (Received FIN from Client and ACKd that FIN)
            RxPPacket finRxPPacket = connectionManager.getNextClosePacket(connectionManager.getConnection(receivedRxPPacket));

            // send FIN
            dg = finRxPPacket.asDatagramPacket();
            dg.setAddress(dgPacket.getAddress());
            dg.setPort(dgPacket.getPort());
            dgSocket.send(dg);

            // System.out.println("Sending FIN to Client");

            connectionManager.updateConnection(finRxPPacket);

          } else {

            // Client close (received FIN from Server)
            this.dgSocket.close();
            return null;
          }
        } else {

          // Make an ACK
          ackRxPPacket = new RxPPacket();
          ackRxPPacket.setACK(true);
          ackRxPPacket.setACKNum(receivedRxPPacket.getSeqNum());
          ackRxPPacket.setDestPort((short)dgPacket.getPort());
          ackRxPPacket.setSrcPort((short)dgSocket.getLocalPort());
          if (receivedRxPPacket.isPSH()) {
            ackRxPPacket.setPSH(true);
            PSH_ACKsent = true;
            // System.out.println("Sending PSH+ACK");
          }
          ackRxPPacket.setChecksum(ackRxPPacket.calculateChecksum());

          // Send ACK
          DatagramPacket dg = ackRxPPacket.asDatagramPacket();
          dg.setAddress(dgPacket.getAddress());
          dg.setPort(dgPacket.getPort());
          dgSocket.send(dg);

          // System.out.println("Sending ACK: " + ackRxPPacket.getACKNum());

          connectionManager.updateConnection(ackRxPPacket);

          // When Client sends ack or server sends FIN remove connection or you could timeout
          if (connectionManager.getConnection(ackRxPPacket).isClientSentACK()) {
            // connectionManager.removeConnection(ackRxPPacket.getSrcPort(), ackRxPPacket.getDestPort());
            return null;
          }
        }
      }
    }

    // If tempPackets pass all the tests (not dup, corrupt, out of order)
    // give the application a byte buffer
    List<Byte> receivedByteList = new ArrayList<>();
    for (RxPPacket tempPacket : tempRxPPacketList) {

      // getPayload returns an array of bytes, add each byte to the byteList
      for (int i = 0; i < tempPacket.getPacketData().length; i++) {
        receivedByteList.add(tempPacket.getPacketData()[i]);
      }
    }

    // Byte to byte ...
    byte[] receivedBytes = new byte[receivedByteList.size()];
    for (int i = 0; i < receivedBytes.length; i++) {
      receivedBytes[i] = receivedByteList.get(i);
    }

    return receivedBytes;
  }

  public byte[] receiveInBackground() throws IOException {
    int expectedSeqNum = 1; // Initial Number

    boolean PSH_ACKsent = false;
    int receiveAttempts = 0;

    int timeoutMillis = 50;
    try {
      dgSocket.setSoTimeout(timeoutMillis);
    } catch(SocketException se) {
      // TODO: Add error handling
    }

    List<RxPPacket> tempRxPPacketList = new ArrayList<>();
    DatagramPacket dgPacket = new DatagramPacket(new byte[MAX_PACKET_SIZE], MAX_PACKET_SIZE);
    // Receive until PSH flag is set or timeout occurs
    while ((this.isClient && !this.isClientSending) || (!this.isClient)) {
      // Receive Datagram from Datagram Socket
      try {
        dgSocket.receive(dgPacket);
      } catch (SocketTimeoutException ste) {

        if (this.isClient && this.isClientSending) {
          break;
        }

        receiveAttempts++;
        if (receiveAttempts > 20) {
          break;
        }
        else {
          continue;
        }
      }
      receiveAttempts = 0;

      // Create RxPPacket from received Datagram Buffer
      RxPPacket receivedRxPPacket = new RxPPacket(dgPacket.getData());

      // Check the checksum to make sure no corruption occurred
      long checksum = receivedRxPPacket.getChecksum();
      long currentChecksum = receivedRxPPacket.calculateChecksum();

      boolean updateConnection = connectionManager.updateConnection(receivedRxPPacket);
      // System.out.println("Expected: " + expectedSeqNum + "\tReceived: " + receivedRxPPacket.getSeqNum());
      // System.out.println("FIN: " + receivedRxPPacket.isFIN());
      // System.out.println("Update Connection: " + updateConnection);

      if (currentChecksum != checksum) {
        // System.out.println("Packet Corrupted");
        continue;
      } else if (connectionManager.isTerminatePacket(receivedRxPPacket)) {

        // System.out.println("Terminate");

        // ACK & call close()
        if (666 == receivedRxPPacket.getSeqNum() && !receivedRxPPacket.isACK()) {

          // Make an ACK
          RxPPacket ackRxPPacket = new RxPPacket();
          ackRxPPacket.setACK(true);
          ackRxPPacket.setACKNum(receivedRxPPacket.getSeqNum());
          ackRxPPacket.setDestPort((short)dgPacket.getPort());
          ackRxPPacket.setSrcPort((short)dgSocket.getLocalPort());
          ackRxPPacket.setChecksum(ackRxPPacket.calculateChecksum());

          // Send ACK
          DatagramPacket dg = ackRxPPacket.asDatagramPacket();
          dg.setAddress(dgPacket.getAddress());
          dg.setPort(dgPacket.getPort());
          dgSocket.send(dg);

          // System.out.println("Sending ACK: " + ackRxPPacket.getACKNum());

          this.close();
        }
      } else if (!connectionManager.isTerminatePacket(receivedRxPPacket)) {

        // if not terminate packet, break
        break;

      } else if (!connectionManager.getConnection(receivedRxPPacket).isAllowedToSendData() && updateConnection) {
        // System.out.println("Update Connection: " + true);
        // If the client is still waiting for the fifth handshake, process that instead.
        Connection connection = connectionManager.getConnection(receivedRxPPacket);
        // System.out.println("Received Handshake Request: " + connection.connectionStateToString());
        sendHandshakePacket(receivedRxPPacket.getDestPort(), receivedRxPPacket.getSrcPort(),
            dgPacket, false);
      }
      else if (expectedSeqNum < receivedRxPPacket.getSeqNum()) {
        // System.out.println("Packet Out of Order");
      }
      else if (receivedRxPPacket.isPSH() && receivedRxPPacket.isACK() && PSH_ACKsent) {
        // System.out.println("PSH+ACK Received");
        try {
          Thread.sleep(2000); // Let any remaining packets in the air die
        } catch (InterruptedException ie) {}
        break;
      }
      else {

        // Only add to the list if this is the right packet. ACK any other one.
        if (expectedSeqNum == receivedRxPPacket.getSeqNum() && !receivedRxPPacket.isACK()) {
          tempRxPPacketList.add(receivedRxPPacket);
          expectedSeqNum = receivedRxPPacket.getSeqNum() + 1;
        }
        else {
          //if (!receivedRxPPacket.isACK())
            // System.out.println("Packet Out of Order");
        }

        RxPPacket ackRxPPacket;
        if (receivedRxPPacket.isFIN() && !receivedRxPPacket.isSYN()) {

          // System.out.println("Received FIN: Close Connection");
          ackRxPPacket = connectionManager.getNextClosePacket(connectionManager.getConnection(receivedRxPPacket));

          // Send ACK
          DatagramPacket dg = ackRxPPacket.asDatagramPacket();
          dg.setAddress(dgPacket.getAddress());
          dg.setPort(dgPacket.getPort());
          dgSocket.send(dg);

          // System.out.println("Sending ACK in Response to FIN: " + ackRxPPacket.getACKNum());

          connectionManager.updateConnection(ackRxPPacket);

          // Send a FIN (if server, otherwise close)
          if (!connectionManager.getConnection(ackRxPPacket).isClient()) {

            // Server send FIN (Received FIN from Client and ACKd that FIN)
            RxPPacket finRxPPacket = connectionManager.getNextClosePacket(connectionManager.getConnection(receivedRxPPacket));

            // send FIN
            dg = finRxPPacket.asDatagramPacket();
            dg.setAddress(dgPacket.getAddress());
            dg.setPort(dgPacket.getPort());
            dgSocket.send(dg);

            // System.out.println("Sending FIN to Client");

            connectionManager.updateConnection(finRxPPacket);

          } else {

            // Client close (received FIN from Server)
            this.dgSocket.close();
            return null;
          }
        } else {

          // Make an ACK
          ackRxPPacket = new RxPPacket();
          ackRxPPacket.setACK(true);
          ackRxPPacket.setACKNum(receivedRxPPacket.getSeqNum());
          ackRxPPacket.setDestPort((short)dgPacket.getPort());
          ackRxPPacket.setSrcPort((short)dgSocket.getLocalPort());
          if (receivedRxPPacket.isPSH()) {
            ackRxPPacket.setPSH(true);
            PSH_ACKsent = true;
            // System.out.println("Sending PSH+ACK");
          }
          ackRxPPacket.setChecksum(ackRxPPacket.calculateChecksum());

          // Send ACK
          DatagramPacket dg = ackRxPPacket.asDatagramPacket();
          dg.setAddress(dgPacket.getAddress());
          dg.setPort(dgPacket.getPort());
          dgSocket.send(dg);

          // System.out.println("Sending ACK: " + ackRxPPacket.getACKNum());

          connectionManager.updateConnection(ackRxPPacket);

          // When Client sends ack or server sends FIN remove connection or you could timeout
          if (connectionManager.getConnection(ackRxPPacket).isClientSentACK()) {
            // connectionManager.removeConnection(ackRxPPacket.getSrcPort(), ackRxPPacket.getDestPort());
            return null;
          }
        }
      }
    }

    // If tempPackets pass all the tests (not dup, corrupt, out of order)
    // give the application a byte buffer
    List<Byte> receivedByteList = new ArrayList<>();
    for (RxPPacket tempPacket : tempRxPPacketList) {

      // getPayload returns an array of bytes, add each byte to the byteList
      for (int i = 0; i < tempPacket.getPacketData().length; i++) {
        receivedByteList.add(tempPacket.getPacketData()[i]);
      }
    }

    // Byte to byte ...
    byte[] receivedBytes = new byte[receivedByteList.size()];
    for (int i = 0; i < receivedBytes.length; i++) {
      receivedBytes[i] = receivedByteList.get(i);
    }

    return receivedBytes;
  }

  public void terminate() {

    Connection myConnection = connectionManager.getConnection(this.srcPort, this.destPort);
    RxPPacket terminateRxPPacket = connectionManager.getTerminatePacket(myConnection);
    sendRxPPackets(new RxPPacket[] { terminateRxPPacket });
  }

  public boolean sendRxPPackets(RxPPacket[] rxpPacketsToSend) {

    this.isClientSending = true;
    int oldestUnackedPointer = 0;
    boolean PSH_ACKsent = false;

    // Set a receive timeout so we can resend after a time, rather
    // than just having it block
    int timeoutMillis = 300;
    try {
      dgSocket.setSoTimeout(timeoutMillis);
    } catch(SocketException se) {
      // TODO: Add error handling
    }

    // The timer task is charged with resending all packets in the window
    // every time it is fired. It needs to be encapsulated in a class because
    // Async tasks need to work on constants, not variables.
    Timer timer = new Timer();

    // Resend the current data every 300ms there is not an ACKed packet.
    ResendTimerTask resendTask = new ResendTimerTask(oldestUnackedPointer, windowSize, rxpPacketsToSend);
    timer.scheduleAtFixedRate(resendTask, 0, timeoutMillis);

    // TODO: 2) Set Window Size
    byte[] packetPayload = new byte[RxPPacket.DEFAULT_PACKET_SIZE];
    DatagramPacket dgPacket = new DatagramPacket(packetPayload, packetPayload.length);

    while (true) {
      try {
        // Wait for the ACK
        dgSocket.receive(dgPacket);

        resendTask.resetTimesNoResponse();
        RxPPacket rxpPacket = new RxPPacket(dgPacket);

        if (!rxpPacket.isSYN() && rxpPacket.isACK()) {

          int ackNumber = rxpPacket.getACKNum();
          int expectedAckNumber = rxpPacketsToSend[oldestUnackedPointer].getSeqNum();
          // System.out.println("ACK Received: " + ackNumber);

          // If FIN ACK?
          if (connectionManager.updateConnection(rxpPacket)) {

            // System.out.println("Received FIN ACK");
            timer.cancel();
            timer.purge();
            resendTask.cancel();
            this.isClientSending = false;
            return true;
          }

          // if the ACK is responding to a terminate packet return true
          if (oldestUnackedPointer < rxpPacketsToSend.length && ackNumber == 666) {

            // System.out.println("Terminate ACK Received");
            oldestUnackedPointer++;
            // Reset timer
            timer.cancel();
            timer.purge();
            resendTask.cancel();
            this.isClientSending = false;
            return true;
          }

          // If the ACK is equal to the oldest unACKed packet, move the index by one
          // Otherwise, wait until it comes OR timeout occurs.
          if (oldestUnackedPointer < rxpPacketsToSend.length &&
              ackNumber == expectedAckNumber) {
            oldestUnackedPointer++;
            boolean success = resendTask.incrementOldestUnackedPointer();

            // Reset timer
            timer.cancel();
            timer.purge();
            timer = new Timer();
            resendTask = new ResendTimerTask(oldestUnackedPointer, windowSize, rxpPacketsToSend);
            timer.scheduleAtFixedRate(resendTask, timeoutMillis, timeoutMillis);
          }

          if (rxpPacket.isPSH() && ackNumber == expectedAckNumber) {
            // If the received packet is a PSH+ACK, then send a PSH+ACK and quit.
            RxPPacket ackRxPPacket = new RxPPacket();
            ackRxPPacket.setACK(true);
            ackRxPPacket.setACKNum(rxpPacket.getACKNum()+1);
            ackRxPPacket.setSeqNum(rxpPacket.getACKNum()+1);
            ackRxPPacket.setDestPort((short)dgPacket.getPort());
            ackRxPPacket.setSrcPort((short)dgSocket.getLocalPort());
            ackRxPPacket.setPSH(true);
            ackRxPPacket.setChecksum(ackRxPPacket.calculateChecksum());

            // Send ACK+PSH
            DatagramPacket dg = ackRxPPacket.asDatagramPacket();
            dg.setAddress(dgPacket.getAddress());
            dg.setPort(dgPacket.getPort());
            dgSocket.send(dg);
            // System.out.println("Sending PSH+ACK");
            timer.cancel();
            timer.purge();
            resendTask.cancel();
            this.isClientSending = false;
            return true;
          }
        }
      } catch(IOException e) {
        // System.err.println("Received Nothing " + resendTask.getNumTimesNoResponse() + " Times");
        if (resendTask.getNumTimesNoResponse() == 40) {
          timer.cancel();
          timer.purge();
          resendTask.cancel();
          this.isClientSending = false;
          return false;
        }
      }
    }
  }
}

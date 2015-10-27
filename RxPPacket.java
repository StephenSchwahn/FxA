import java.net.*;
import java.io.*;
import java.nio.ByteBuffer;

public class RxPPacket {

  private DatagramPacket packet;
  private ByteBuffer data;

  private RxPPacket(DatagramPacket packet) {
    this.packet = packet;
    this.data = ByteBuffer.wrap(packet.getData());
  }

  public static final RxPPacket initializeFromDatagramPacket(DatagramPacket dg) {
    return new RxPPacket(dg);
  }

  //---- Masks and information pertaining to the header
  public static final int FLAGS_BYTE_OFFSET = 14;
  // TODO: ...

  public static final byte FIN_MASK = 0b0000_0001;
  public static final byte SYN_MASK = 0b0000_0010;
  public static final byte ACK_MASK = 0b0000_0100;
  public static final byte PSH_MASK = 0b0000_1000;
  // TODO: ...

  public boolean isSYN() {
    byte result = (byte) ( this.data.get(FLAGS_BYTE_OFFSET) & SYN_MASK);
    return result == SYN_MASK;
  }

  public void setSYN(boolean set) {
    byte newByte = this.data.get(FLAGS_BYTE_OFFSET);
    if (set) {
      newByte = (byte)(newByte | SYN_MASK);
    } else {
      newByte = (byte)(newByte & ~SYN_MASK);
    }
  }

}

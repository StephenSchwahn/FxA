// java FxAClient 8080 localhost 5000
import java.util.concurrent.*;
import java.io.*;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Scanner;

public class FxAClient {

  private RxPSocket socket;
  private ExecutorService service;
  private CLILoop cliLoop;
  private ReceiveLoop receiveLoop;

  private boolean isConnected;
  public static boolean isProcessingAction;

  public FxAClient(int port, int netEmuPort, InetAddress netEmuInetAddress) throws IOException {

    // Creates Client's RxPSocket bound to Client's localhost and even port
    InetAddress localhost = InetAddress.getByName("127.0.0.1");
    socket = RxPSocket.newRxPClientSocket(port, localhost);

    this.cliLoop = new CLILoop(socket, this, netEmuPort, netEmuInetAddress);
    this.receiveLoop = new ReceiveLoop(socket, this);
    this.isConnected = false;
    isProcessingAction = false;
  }

  private void mainLoop() throws IOException, InterruptedException {
    service = Executors.newFixedThreadPool(3);

    ArrayList<Callable<Object>> tasks = new ArrayList<Callable<Object>>();
    tasks.add(this.cliLoop);
    tasks.add(this.receiveLoop);

    service.invokeAll(tasks);

    service.shutdown();
    service.awaitTermination(1, TimeUnit.DAYS);
  }

  public boolean isConnected() {
    return this.isConnected;
  }

  public void setConnected(boolean c) {
    this.isConnected = c;
  }

	public static void main(String args[]) throws Exception {
    int inPort;
    int inNetEmuPort;
		String inNetEmuIPString;

		// Test for correct # of args
    if (args.length != 3) {
      throw new IllegalArgumentException("Parameter(s): <port-evenNum>  <NetEmu-IP> <NetEmu-Port#>");
    }

    // Initialize Port and NetEmu Address/Port with command line arguments
    inPort = Integer.parseInt(args[0]);
    if (inPort % 2 != 0) {
      throw new IllegalArgumentException("Port number of localhost must be even");
    }
    inNetEmuIPString = args[1];
    inNetEmuPort = Integer.parseInt(args[2]);

    // Create NetEmu InetAddress Obj
    InetAddress inNetEmuInetAddress = InetAddress.getByName(inNetEmuIPString);

    (new FxAClient(inPort, inNetEmuPort, inNetEmuInetAddress)).mainLoop();
  }
}

class ReceiveLoop implements Callable<Object> {

  private RxPSocket socket;
  private FxAClient client;

  public ReceiveLoop(RxPSocket socket, FxAClient client) {
    this.socket = socket;
    this.client = client;
  }

  @Override
  public Object call() throws Exception {
    receiveLoop();
    return null;
  }

  public void receiveLoop() {
    if (!this.client.isConnected()) {
      System.out.println("Receiving data without being connected. Please Connect.");

      MsgCoder coder = new FileMsgTextCoder();
      FileService service = new FileService();

      boolean connectedOneTme = false;
      while (true) {
        if (this.socket.isConnected) {
          connectedOneTme = true;
        }

        //System.out.println("this.socket.isConnected: " +this.socket.isConnected);
      	// Make receive break when socket.isClientSending
      	// must make sure receive knows that it is a client socket

      	//System.out.println("isClientSending: " + this.socket.isClientSending);
        if (!this.socket.isClientSending) {

          // Exit if the socket is disconnected
          if (!this.socket.isConnected && connectedOneTme) {
            System.exit(0);
          }

      		try {
            if (!this.client.isProcessingAction) {
  		    	     this.socket.receiveInBackground();
            }

          } catch (Exception ioe) {
            System.err.println(ioe.getMessage());
          }
	       }
      }
    }
  }
}

class CLILoop implements Callable<Object> {

  private RxPSocket socket;
  private FxAClient client;
  private int netEmuPort;
  private InetAddress netEmuInetAddress;
  private MsgCoder coder;

  public CLILoop(RxPSocket socket, FxAClient client,
          int netEmuPort, InetAddress netEmuInetAddress) {
    this.socket = socket;
    this.client = client;
    this.netEmuPort = netEmuPort;
    this.netEmuInetAddress = netEmuInetAddress;

    this.coder = new FileMsgTextCoder();
  }

  @Override
  public Object call() throws Exception {
    cliLoop();
    return null;
  }

  private void connect() {
	  socket.connect(netEmuInetAddress, netEmuPort);
    this.client.setConnected(true);
  }

  private void listen() {
    socket.listen();
  }

  private void disconnect() {
    // TODO: Implement
    socket.close();
    System.exit(0);
  }

  private void setWindowSize(int windowSize) {
    socket.setWindowSize(windowSize);
  }

  private void getFile(String filename) throws IOException {
    synchronized(socket) {
      this.client.isProcessingAction = true;
      // Send the request to the server
      FileMsg request = new FileMsg(true, filename, null);
      byte[] encodedMsg = coder.toWire(request);
      while (!socket.send(encodedMsg));

      System.out.println("Query Sent. Waiting for response.");
      // Wait for the server to respond to the request with a byte[]
      byte[] fileBytes = null;
      while ((fileBytes = socket.receive()).length == 0);

      FileMsg response = coder.fromWire(fileBytes);

      if (response.isGet()) {
        System.err.println("The filename was wrong.");
        return;
      }

      // Output the received bytes to a file.
      FileOutputStream fs = null;
      try {
        File newFile = new File(filename);
        fs = new FileOutputStream(newFile);
        fs.write(response.getFile());
      } catch(Exception e) {
        System.err.println("The file could not be saved.");
        System.err.println(e.getMessage());
      }
      finally {
        if (fs != null) {
          fs.close();
          this.client.isProcessingAction = false;
        }
      }
      System.out.println("File was downloaded successfully.\nSaved as: " + filename);
    }
  }

  private void postFile(String filename) throws IOException {
    synchronized(this) {
      this.client.isProcessingAction = true;
      FileInputStream newFile = null;
      try {
        newFile = new FileInputStream(filename);
      } catch(FileNotFoundException fnfe) {
        System.err.println("The file to upload could not be found.");
        System.err.println(fnfe.getMessage());
        return;
      }

      // Read in the file
      byte[] file = new byte[newFile.available()];
      newFile.read(file);
      newFile.close();

      //System.out.println("File Read. Num Bytes="+file.length);

      // Send it.
      FileMsg request = new FileMsg(false, filename, file);
      byte[] encodeMsg = coder.toWire(request);
      //System.out.println("File Encoded. Num Bytes="+encodeMsg.length);
      //System.out.println("File Encoded. "+new String(encodeMsg));
      while (!socket.send(encodeMsg));

      System.out.println("\n\nFile Uploaded. Verifying success");
      // Wait for the response
      byte[] fileBytes = null;
      while ((fileBytes = socket.receive()).length == 0); // Loop until data comes back
      FileMsg response = coder.fromWire(fileBytes);

      if (new String(response.getFile()).trim().equals(new String(file).trim())) {
        System.out.println("File Upload success");
      }
      else {
        System.out.println("File upload failed");
        try {
          File errorFile = new File("upload_failure.txt");
          FileOutputStream fs = new FileOutputStream(errorFile);
          fs.write(response.getFile());
        } catch(Exception e) {
          System.err.println("The file could not be saved.");
          System.err.println(e.getMessage());
        }
        finally {
          this.client.isProcessingAction = true;
        }
      }
    }
  }

  public void cliLoop() {
    Scanner scanner = new Scanner(System.in);
    boolean connected = false;
    while(true) {
      try {
        System.out.print("> ");
        if (scanner.hasNextLine()) {
          String command = scanner.next();
          if (command.equals("connect")) {
            connect();
            connected = true;
          }
          else if (command.equals("get") && connected == true) {
            String filename = scanner.next();
            scanner.nextLine();
            getFile(filename);
          }
          else if (command.equals("post") && connected == true) {
            String filename = scanner.next();
            scanner.nextLine();
            postFile(filename);
          }
          else if (command.equals("window") && connected == true) {
            int windowSize = scanner.nextInt();
            scanner.nextLine();
            setWindowSize(windowSize);
          }
          else if (command.equals("disconnect")) {
            disconnect();
            connected = false;
          }
          else {
            if (!connected) {
              System.out.println("You need to connect to the server");
            } else {
              System.out.println("Not a valid command.");
            }
          }
        }
      }
      catch (Exception e) {
        System.err.println("There was an error that occurred. ");
        System.err.println(e.getMessage());
      }
    }
  }
}

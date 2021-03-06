import java.io.*;
import java.net.*;
import java.util.Date;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;


public class TestServer {
    public static void main(String[] args) {
        //make sure port number was passed
        if (args.length < 1) return;


        //get the passed port number
        int port = Integer.parseInt(args[0]);

        /*
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            
            System.out.println("Server is listening on port " + port);

            //listen for clients
            while (true) {

                try {
                    //re-enter wpa_cli using newly created p2p-wlp3s0-0 interface
                    //intitiate pushbutton connection event
                    p1 = Runtime.getRuntime().exec("sudo wpa_cli -i wlp3s0 p2p_connect 7a:4a:4b:cd:0c:62 pbc go_intent=0"); //for some reason reusing p was not working
                    p1.waitFor();

                    System.out.println("wps_pbc done");

                    p1 = Runtime.getRuntime().exec("sudo udhcpd /etc/udhcpd.conf");
                    p1.waitFor();

                    System.out.println("udhcpd started");
                }


                catch (IOException e) {

                    e.printStackTrace();
                } 

                
                catch (InterruptedException e) {

                    e.printStackTrace();
                }

                System.out.println("Waiting for client to join...");

                //this call blocks until client connects
                Socket client = serverSocket.accept();

                System.out.println("New client connected");

                //OutputStream output = socket.getOutputStream();

                byte[] in = new byte[10];

                //now a client has initialized and transferred/output data via stream
                //now we want to save the input stream from the client
                InputStream inputStream = client.getInputStream();

                int charsRead = inputStream.read(in);

                System.out.println("Got message from client: " + in[0]);

                serverSocket.close();

                //PrintWriter writer = new PrintWriter(output, true);

                //writer.println(new Date().toString());
            }

        } 
        
        
        catch (IOException ex) {
            System.out.println("Server exception: " + ex.getMessage());
            ex.printStackTrace();
        }*/
        

        /*
        try {
            
            p = Runtime.getRuntime().exec("sudo wpa_cli -i wlp3s0 p2p_connect 7a:4a:4b:cd:0c:62 pbc go_intent=0"); //for some reason reusing p was not working
            p.waitFor();
    
            TimeUnit.SECONDS.sleep(6); 
            p = Runtime.getRuntime().exec("sudo udhcpd /etc/udhcpd.conf");
            p.waitFor();

            TimeUnit.SECONDS.sleep(5); 
        }

        catch (IOException e) {

            e.printStackTrace();
        } 

        
        catch (InterruptedException e) {

            e.printStackTrace();
        }*/


        int timeout = 10000;
        int success = 0;

        //create packet of host and port information for the server, using IP of group owner
        InetSocketAddress socketAddress = new InetSocketAddress("192.168.49.1", 8988); //192.168.49.1

        //test data transfer using byte array of size one
        byte[] bytes = new byte[1];

        //put a '0' char into the array
        bytes[0] = 0x30;

        //create a new client socket
        Socket socket = new Socket();


        try {
            System.out.println("Calling socket.connect...");

            //connect the socket to the p2p host IP (the Android device). SocketAddress here should be the server address
            socket.connect(socketAddress, timeout);

            System.out.println("Client-server connection successful!!");

            //close the socket
            socket.close();



            //DATA TRANSFER TESTING


            //get resources to output stuff to the client's input stream
            //out = new PrintWriter(socket.getOutputStream(), true);

            //get the client's input stream (incoming data to client)
            //in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            /*
            outStream = socket.getOutputStream();
            inStream = socket.getInputStream();

            long start = System.currentTimeMillis();

            //Log.i(TAG, "Client: sending 48 to server...");

            //ping the server
            //out.print(48);
            outStream.write(48);

            //Log.i(TAG, "Client: data sent to server complete, now reading...");

            //int got = in.read();

            int got = inStream.read();

            //Log.i(TAG, "Client: readback complete");

            long end = System.currentTimeMillis();

            Log.i(TAG, String.format("Got %d back from server after %d ms", got, (end - start) ));

            
            //Create a byte stream from a JPEG file and pipe it to the output stream
            //of the socket. This data is retrieved by the server device.
            OutputStream outputStream = socket.getOutputStream();
            ContentResolver cr = MainActivity.this.getApplicationContext().getContentResolver();

            //write a 4 into the stream
            outputStream.write(bytes);

            //close the stream
            outputStream.close();
            */
            
        }

        catch (IOException e) {
            System.out.println("Client socket connection timed out");
            e.printStackTrace();
        }
        


        /*
        //Clean up any open sockets when done transferring or if an exception occurred.
        //executed no matter what, even if other exceptions occur
        finally {
            if (socket.isConnected()) {
                try {
                    socket.close();
                }

                catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        */
    }
}
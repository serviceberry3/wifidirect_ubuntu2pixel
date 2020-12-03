package weiner.noah.wifidirect;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.NetworkInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private ListView listView;
    private ArrayAdapter aa;
    private TextView tv;
    private Button buttonDiscover;

    IntentFilter peerfilter;
    IntentFilter connectionfilter;
    IntentFilter p2pEnabled;

    private Handler handler = new Handler();

    //This class provides API for managing Wi-Fi peer-to-peer (Wifi Direct) connectivity. This lets app discover available peers,
    //setup connection to peers and query for list of peers. When a p2p connection is formed over wifi, the device continues
    //to maintain the uplink connection over mobile or any other available network for internet connectivity on the device.
    private WifiP2pManager wifiP2pManager;
    private WifiP2pManager.Channel wifiDirectChannel;

    private void initializeWiFiDirect() {
        //initialize the peer-to-peer (Wifi Direct) connection manager
        wifiP2pManager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);

        //WifiP2pManager's initialize() fxn returns channel instance that is necessary for performing any further p2p operations
        wifiDirectChannel = wifiP2pManager.initialize(this, getMainLooper(),
                new WifiP2pManager.ChannelListener() {
                    public void onChannelDisconnected() {
                        //re-initialize the WifiDirect upon disconnection
                        initializeWiFiDirect();
                    }
                }
        );
    }

    //create WifiP2pManager ActionListener
    //Most application calls need ActionListener instance for receiving callbacks ActionListener.onSuccess() or ActionListener.onFailure(), which
    //indicate whether initiation of the action was a success or a failure. Reason of failure can be ERROR, P2P_UNSUPPORTED or BUSY
    private WifiP2pManager.ActionListener actionListener = new WifiP2pManager.ActionListener() {
        public void onFailure(int reason) {
            String errorMessage = "WiFi Direct Failed: ";


            switch (reason) {
                case WifiP2pManager.BUSY:
                    errorMessage += "Framework busy.";
                    break;
                case WifiP2pManager.ERROR:
                    errorMessage += "Internal error.";
                    break;
                case WifiP2pManager.P2P_UNSUPPORTED:
                    errorMessage += "Unsupported.";
                    break;
                default:
                    errorMessage += "Unknown error.";
                    break;
            }

            //print out the final error message to the log
            Log.d(TAG, errorMessage);
        }

        public void onSuccess() {
            //Success!
            //Return values will be returned using a Broadcast Intent
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);


        listView = (ListView) findViewById(R.id.listView);

        aa = new ArrayAdapter<WifiP2pDevice>(this, android.R.layout.simple_list_item_1, deviceList);

        listView.setAdapter(aa);

        initializeWiFiDirect();

        peerfilter = new IntentFilter(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);

        connectionfilter = new IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);

        p2pEnabled = new IntentFilter(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);

        //get the "DISCOVER PEERS" button
        buttonDiscover = (Button) findViewById(R.id.buttonDiscover);

        //run discoverPeers() upon click
        buttonDiscover.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                discoverPeers();
            }
        });

        //get the "ENABLE" button
        Button buttonEnable = (Button) findViewById(R.id.buttonEnable);

        //set it so that "ENABLE" button just opens device wireless settings upon click
        buttonEnable.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent intent = new Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS);

                //open wifi settings
                startActivity(intent);
            }
        });

        Button buttonServer = (Button) findViewById(R.id.buttonServer);

        buttonServer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new becomeServerForPC().start();
            }
        });

        //set list item (device) so that when clicked, connectTo() function is run on that device
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> arg0, View arg1, int index, long arg3) {
                connectTo(deviceList.get(index));
            }
        });
    }

    //receive a Wifi Direct status change
    BroadcastReceiver p2pStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, WifiP2pManager.WIFI_P2P_STATE_DISABLED);

            if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                buttonDiscover.setEnabled(true);
            }

            else {
                buttonDiscover.setEnabled(false);
            }
        }
    };

    //discover Wifi Direct peers
    //An initiated discovery request from an app stays active until device starts connecting to a peer, forms a p2p group, or there's an explicit
    //stopPeerDiscovery(). Apps can listen to WIFI_P2P_DISCOVERY_CHANGED_ACTION to know if a peer-to-peer discovery is running or stopped.
    //WIFI_P2P_PEERS_CHANGED_ACTION indicates if peer list has changed
    private void discoverPeers() {
        //make sure we have permission
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "discoverPeers(): ACCESS_FINE_LOCATION not granted");
            return;
        }

        Log.i(TAG, "Running discoverPeers()...");


        //run discoverPeers() method of WifiP2pManager
        wifiP2pManager.discoverPeers(wifiDirectChannel, actionListener);
    }


    BroadcastReceiver peerDiscoveryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.ACCESS_FINE_LOCATION) !=
                    PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "peerDiscoveryReceiver.onReceive(): ACCESS_FINE_LOCATION not granted");
                return;
            }

            Log.i(TAG, "Peers have changed");
            wifiP2pManager.requestPeers(wifiDirectChannel,
                    new WifiP2pManager.PeerListListener() {
                        public void onPeersAvailable(WifiP2pDeviceList peers) {
                            deviceList.clear();
                            deviceList.addAll(peers.getDeviceList());
                            aa.notifyDataSetChanged();
                        }
                    });
        }
    };

    //request connection to a wifi direct peer
    private void connectTo(WifiP2pDevice device) {
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = device.deviceAddress;

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        Log.i(TAG, "Running wifip2pman.connect...");
        wifiP2pManager.connect(wifiDirectChannel, config, actionListener);
    }

    //wifi direct peer connection callback
    BroadcastReceiver connectionChangedReceiver = new BroadcastReceiver() {
        //executes when the Wifi Direct connection status changes
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "Wifi Direct connection status change detected");

            //Extract the NetworkInfo
            String extraKey = WifiP2pManager.EXTRA_NETWORK_INFO;
            NetworkInfo networkInfo = (NetworkInfo) intent.getParcelableExtra(extraKey);

            //Check if we're connected
            assert networkInfo != null;
            if (networkInfo.isConnected()) {


                Log.i(TAG, "Network is connected to something...");
                wifiP2pManager.requestConnectionInfo(wifiDirectChannel,
                        new WifiP2pManager.ConnectionInfoListener() {
                            public void onConnectionInfoAvailable(WifiP2pInfo info) {
                                //If the connection is established
                                if (info.groupFormed) {
                                    Log.i(TAG, "Connection has been established!");
                                    Toast.makeText(MainActivity.this, "Connection has been established!", Toast.LENGTH_SHORT).show();


                                    //If we're the server
                                    if (info.isGroupOwner) {

                                        Log.i(TAG, "We're the server, creating ServerSocket in background and waiting for client...");
                                        //initiateServerSocket();

                                        //create ServerSocket in background and wait for client to connect
                                        FileServerAsyncTask asyncServerSockInit = new FileServerAsyncTask();
                                        asyncServerSockInit.execute();
                                    }


                                    //If we're the client
                                    else if (info.groupFormed) {
                                        Log.i(TAG, "We're the client");

                                        initiateClientSocket(info.groupOwnerAddress.getHostAddress());
                                    }
                                }
                            }
                        });
            }

            else {
                Log.d(TAG, "Wi-Fi Direct Disconnected");
            }
        }
    };


    private void initiateServerSocket() {
        ServerSocket serverSocket;
        try {
            //instantiate a ServerSocket
            serverSocket = new ServerSocket(8988);
            Socket serverClient = serverSocket.accept();
        }

        catch (IOException e) {
            Log.e(TAG, "I/O Exception", e);
        }
    }


    public static class becomeServerForPC extends Thread {
        @Override
        public void run() {
            Log.d(TAG, "Start becomeServerForPC thread...");

            ServerSocket serverSocket = null;

            try {
                //create server socket and wait for client connections.
                serverSocket = new ServerSocket(8988);

                Log.d(TAG, "Socket waiting " + serverSocket.getLocalSocketAddress().toString() );

                Socket client = serverSocket.accept();

                InputStream inputStream = client.getInputStream();

                Log.d(TAG, "InputStream is available: " + String.valueOf(inputStream.available()));

                //shut down server
                serverSocket.close();
            }

            catch (IOException e) {
                Log.e(TAG, "becomeServerForPC received exception " + e.getMessage());

                e.printStackTrace();

                //make sure server is closed
                if (serverSocket != null) {
                    try {
                        serverSocket.close();
                    }
                    catch (IOException ex) {
                        Log.e(TAG, "Failed to close socket exception " + ex.getMessage());
                    }
                }
            }

        }
    }


    private BufferedReader in;
    private PrintWriter out;
    private InputStream inStream;
    private OutputStream outStream;


//----------------------------------------------------------------CLIENT CODE-------------------------------------------------------------------------------

    //create a client socket on a background thread
    private void initiateClientSocket(final String hostAddress) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                Log.i(TAG, "Address :" + hostAddress);

                int timeout = 10000;
                int port = 8988;
                int success = 0;

                //create packet of host and port information
                InetSocketAddress socketAddress = new InetSocketAddress(hostAddress, port);

                byte[] bytes = new byte[1];

                bytes[0] = 0x30;

                //create a client socket and connect it to the server
                Socket socket = new Socket();

                try {
                    Log.i(TAG, "initiateClientSocket(): calling bind");

                    socket.bind(null);

                    socket.connect(socketAddress, timeout);

                    Log.i(TAG, "Client-server connection successful!!");

                    //get input and output streams for the socket
                    outStream = socket.getOutputStream();
                    inStream = socket.getInputStream();

                    Log.i(TAG, "Client: sending 48 to server...");

                    //ping the server test
                    outStream.write(48);

                    Log.i(TAG, "Client: data sent to server complete, now reading...");
                }

                catch (IOException e) {
                    Log.e(TAG, "IO Exception from trying to bind socket:", e);
                }


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
            }
        }).start();

    }

//-----------------------------------------------------------------CLIENT CODE-------------------------------------------------------------------------------

    @Override
    protected void onPause() {
        unregisterReceiver(peerDiscoveryReceiver);
        unregisterReceiver(connectionChangedReceiver);
        unregisterReceiver(p2pStatusReceiver);
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();

        //register the broadcast receivers to listen
        registerReceiver(peerDiscoveryReceiver, peerfilter);
        registerReceiver(connectionChangedReceiver, connectionfilter);
        registerReceiver(p2pStatusReceiver, p2pEnabled);
    }

    //the list of p2p devices found
    private List<WifiP2pDevice> deviceList = new ArrayList<>();


//---------------------------------------------------------------------SERVER CODE---------------------------------------------------------------------------

    //Server socket that initializes in background and accepts connection and reads data from client (use of AsyncTask here is probly stupid)
    public static class FileServerAsyncTask extends AsyncTask<Void, Void, Void> { //params passed, progress update returned, final returned
        private Context context;
        private TextView statusText;

        private OutputStream outStream;
        private InputStream inStream;

        private ServerSocket serverSocket;

        @Override
        protected Void doInBackground(Void... params) {
            try {
                 //Create a server socket and wait for client connections. This
                 //call blocks until a connection is accepted from a client
                serverSocket = new ServerSocket(8988);

                Log.d(TAG, "Server: Socket opened port 8988, waiting for client");
                Log.i(TAG, "Address: " + serverSocket.getLocalSocketAddress());

                //block until connection from client comes through
                Socket client = serverSocket.accept();

                Log.d(TAG, "Server: connection from client came through");

                //get input and output streams for the client
                outStream = client.getOutputStream();
                inStream = client.getInputStream();

                Log.d(TAG, "Server: reading in data...");

                byte[] in = new byte[10];

                //this call BLOCKS until data is detected and read in
                int dataIn = inStream.read(in);

                Log.d(TAG, "Server: reading in data complete");
                Log.d(TAG, String.format("Got message from client: " + in[0]));
            }

            catch (IOException e) {
                e.printStackTrace();
            }

            finally {
                try {
                    //close up the socket
                    serverSocket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            return null;
        }

//--------------------------------------------------------------------SERVER CODE------------------------------------------------------------------------


        @Override
        protected void onPostExecute (Void result) {
        }


        @Override
        protected void onPreExecute() {
        }
    }
}
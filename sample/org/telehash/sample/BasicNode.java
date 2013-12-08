package org.telehash.sample;

import java.io.FileNotFoundException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.telehash.core.Channel;
import org.telehash.core.ChannelHandler;
import org.telehash.core.ChannelPacket;
import org.telehash.core.CompletionHandler;
import org.telehash.core.Identity;
import org.telehash.core.Line;
import org.telehash.core.Node;
import org.telehash.core.Switch;
import org.telehash.core.Telehash;
import org.telehash.core.TelehashException;
import org.telehash.core.Util;
import org.telehash.crypto.RSAPublicKey;
import org.telehash.network.impl.InetEndpoint;

public class BasicNode {
    
    private static final String IDENTITY_BASE_FILENAME = "telehash-node";
    private static final int PORT = 5002;
    private static final String SEED_PUBLIC_KEY_FILENAME = "telehash-seed.pub";
    private static final int SEED_PORT = 5001;

    public static final void main(String[] args) {

        // load or create an identity
        Identity identity;
        try {
            identity = Util.getStorageInstance().readIdentity(IDENTITY_BASE_FILENAME);
        } catch (TelehashException e) {
            if (e.getCause() instanceof FileNotFoundException) {
                // no identity found -- create a new one.
                try {
                    identity = Util.getCryptoInstance().generateIdentity();
                    Util.getStorageInstance().writeIdentity(identity, IDENTITY_BASE_FILENAME);
                } catch (TelehashException e1) {
                    e1.printStackTrace();
                    return;
                }
            } else {
                e.printStackTrace();
                return;
            }
        }
        
        // read the public key of the seed
        RSAPublicKey seedPublicKey;
        try {
            seedPublicKey =
                    Util.getCryptoInstance().readRSAPublicKeyFromFile(SEED_PUBLIC_KEY_FILENAME);
        } catch (TelehashException e) {
            throw new RuntimeException(e);
        }
        
        // formulate a node object to represent the seed
        InetAddress localhost;
        try {
            localhost = InetAddress.getLocalHost();
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }
        Node seed;
        try {
            seed = new Node(seedPublicKey, new InetEndpoint(localhost, SEED_PORT));
        } catch (TelehashException e) {
            throw new RuntimeException(e);
        }
        Set<Node> seeds = new HashSet<Node>();
        seeds.add(seed);

        // launch the switch
        final Telehash telehash = new Telehash(identity);
        final Switch telehashSwitch = new Switch(telehash, seeds, PORT);
        telehash.setSwitch(telehashSwitch);
        try {
            telehashSwitch.start();
        } catch (TelehashException e) {
            e.printStackTrace();
            return;
        }

        // send packet
        System.out.println("node sending packet to seed.");
        
        try {
            telehashSwitch.openLine(seed, new CompletionHandler<Line>() {
                @Override
                public void failed(Throwable exc, Object attachment) {
                    System.out.println("line open failed.");
                }
                
                @Override
                public void completed(Line line, Object attachment) {
                    System.out.println("line established: "+line);
                    
                    Channel channel = line.openChannel("seek", new ChannelHandler() {
                        @Override
                        public void handleError(Throwable error) {
                            System.out.println("channel error: "+error);
                        }
                        @Override
                        public void handleIncoming(ChannelPacket channelPacket) {
                            System.out.println("channel incoming data: "+channelPacket);
                            Util.hexdump(channelPacket.getBody());
                        }
                    });
                    
                    Map<String,Object> fields = new HashMap<String,Object>();;
                    fields.put("seek", Util.bytesToHex(telehash.getIdentity().getHashName()));
                    try {
                        channel.send(null, fields);
                    } catch (TelehashException e) {
                        // TODO Auto-generated catch block
                        e.printStackTrace();
                    }
                }
            }, null);
        } catch (TelehashException e) {
            e.printStackTrace();
        }
        
        // pause 5 seconds
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        
        // stop the switch
        telehashSwitch.stop();
    }
}

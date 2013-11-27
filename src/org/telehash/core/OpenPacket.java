package org.telehash.core;

import java.io.UnsupportedEncodingException;
import java.util.Arrays;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONStringer;
import org.telehash.crypto.Crypto;
import org.telehash.crypto.ECKeyPair;
import org.telehash.crypto.ECPrivateKey;
import org.telehash.crypto.ECPublicKey;
import org.telehash.crypto.RSAPublicKey;

/**
 * A Telehash "open" packet is used to establish a line between two Telehash
 * nodes.
 * 
 * <p>
 * The open packet consists of the following components, in roughly the order in
 * which they should be unpacked:
 * </p>
 * 
 * <ol>
 * <li>The public key from an elliptic curve key pair uniquely generated for
 * this open. This key is RSA-OAEP encrypted using the destination's RSA public
 * key.</li>
 * <li>A random initialization vector (IV) used for the AES encryption of other
 * components within this packet.</li>
 * <li>An embedded "inner packet" containing the time, destination hash name,
 * line identifier, and the RSA public key of the initiator. This inner packet
 * is AES-CTR encrypted using the SHA-256 hash of the generated elliptic curve
 * public key, and attached to the outer packet as the body.</li>
 * <li>An RSA signature of the encrypted inner packet, proving the authenticity
 * of the sender. The signature itself is AES-CTR encrypted using the SHA-256
 * hash of the elliptic curve public key and the line identifier.</li>
 * </ol>
 */
public class OpenPacket extends Packet {
    
    private static final String OPEN_TYPE = "open";
    
    private static final String IV_KEY = "iv";
    private static final String SIG_KEY = "sig";
    private static final String OPEN_KEY = "open";
    private static final String OPEN_TIME_KEY = "at";
    private static final String DESTINATION_KEY = "to";
    private static final String LINE_IDENTIFIER_KEY = "line";
    
    private static final int IV_SIZE = 16;
    private static final int LINE_IDENTIFIER_SIZE = 16;
    private static final int HASHNAME_SIZE = 32;
    
    private static final long MAXIMUM_TIME_DIFFERENCE = 24*3600*1000; // 1 day
    
    static {
        Packet.registerPacketType(OPEN_TYPE, OpenPacket.class);
    }
    
    private Identity mIdentity;
    private Node mSourceNode;
    private Node mDestinationNode;
    private RSAPublicKey mSenderRSAPublicKey;
    private ECPublicKey mEllipticCurvePublicKey;
    private ECPrivateKey mEllipticCurvePrivateKey;
    private long mOpenTime;
    private byte[] mLineIdentifier;

    public OpenPacket(Identity identity, Node destinationNode) {
        mIdentity = identity;
        mDestinationNode = destinationNode;
        mSenderRSAPublicKey = identity.getPublicKey();
    }
    
    public OpenPacket(
            Node sourceNode,
            ECPublicKey ellipticCurvePublicKey,
            long openTime,
            byte[] lineIdentifier
    ) {
        mSourceNode = sourceNode;
        mEllipticCurvePublicKey = ellipticCurvePublicKey;
        mOpenTime = openTime;
        mLineIdentifier = lineIdentifier;
    }
    
    // accessor methods
    
    public void setSourceNode(Node sourceNode) {
        mSourceNode = sourceNode;
    }
    public Node getSourceNode() {
        return mSourceNode;
    }

    public void setDestinationNode(Node destinationNode) {
        mDestinationNode = destinationNode;
    }
    public Node getDestinationNode() {
        return mDestinationNode;
    }
    
    public void setSenderRSAPublicKey(RSAPublicKey senderRSAPublicKey) {
        mSenderRSAPublicKey = senderRSAPublicKey;
    }
    public RSAPublicKey getSenderRSAPublicKey() {
        return mSenderRSAPublicKey;
    }
    
    public void setEllipticCurvePublicKey(ECPublicKey publicKey) {
        mEllipticCurvePublicKey = publicKey;
    }
    public ECPublicKey getEllipticCurvePublicKey() {
        return mEllipticCurvePublicKey;
    }
    
    public void setEllipticCurvePrivateKey(ECPrivateKey privateKey) {
        mEllipticCurvePrivateKey = privateKey;
    }
    public ECPrivateKey getEllipticCurvePrivateKey() {
        return mEllipticCurvePrivateKey;
    }
    
    public void setOpenTime(long openTime) {
        mOpenTime = openTime;
    }
    public long getOpenTime() {
        return mOpenTime;
    }
    
    public void setLineIdentifier(byte[] lineIdentifier) {
        mLineIdentifier = lineIdentifier;
    }
    public byte[] getLineIdentifier() {
        return mLineIdentifier;
    }
    
    /**
     * Render the open packet into its final form.
     * 
     * @return The rendered open packet as a byte array.
     */
    public byte[] render() throws TelehashException {
        Crypto crypto = Util.getCryptoInstance();
        
        // generate a random IV
        byte[] iv = crypto.getRandomBytes(IV_SIZE);
        
        // generate a random line identifier
        mLineIdentifier = crypto.getRandomBytes(LINE_IDENTIFIER_SIZE);
        
        // note the current time
        mOpenTime = System.currentTimeMillis();
        
        // generate the elliptic curve key pair, based on the "nistp256" curve
        ECKeyPair ellipticCurveKeyPair = crypto.generateECKeyPair();
        mEllipticCurvePublicKey = ellipticCurveKeyPair.getPublicKey();
        mEllipticCurvePrivateKey = ellipticCurveKeyPair.getPrivateKey();
        
        // generate the "open" parameter by encrypting the public elliptic curve
        // key (in ANSI X9.63 format) with the recipient's
        // RSA public key and OAEP padding.
        byte[] openParameter = crypto.encryptRSAOAEP(
                mDestinationNode.getPublicKey(),
                ellipticCurveKeyPair.getPublicKey().getEncoded()
        );

        // perform further packet creation.
        return render(iv, openParameter);
    }
    
    /**
     * Render the open packet into its final form.
     * 
     * This version of the method allows the caller to pass in values for
     * certain otherwise calculated fields, allowing for deterministic open
     * packet creation suitable for unit tests.
     * 
     * @param iv
     *            The initialization vector to use for this open packet.
     * @param openParameter
     *            The "open" parameter -- the public EC key (in ANSI X9.63
     *            format) encrypted with the recipient's RSA public key with
     *            OAEP padding.
     * @return The rendered open packet as a byte array.
     * @throws TelehashException
     */
    public byte[] render(
            byte[] iv,
            byte[] openParameter
    ) throws TelehashException {
        Crypto crypto = Util.getCryptoInstance();

        byte[] encodedECPublicKey = mEllipticCurvePublicKey.getEncoded();
        
        // SHA-256 hash the public elliptic key to form the encryption
        // key for the inner packet
        byte[] innerPacketAESKey = crypto.sha256Digest(encodedECPublicKey);
        
        // Form the inner packet containing a current timestamp at, line
        // identifier, recipient hashname, and family (if you have such a
        // value). Your own RSA public key is the packet BODY in the binary DER
        // format.
        byte[] innerPacket;
        try {
            innerPacket = new JSONStringer()
                .object()
                .key("at")
                .value(mOpenTime)
                .key("to")
                .value(Util.bytesToHex(mDestinationNode.getHashName()))
                .key("line")
                .value(Util.bytesToHex(mLineIdentifier))
                .endObject()
                .toString()
                .getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new TelehashException(e);
        } catch (JSONException e) {
            throw new TelehashException(e);
        }
        innerPacket = Util.concatenateByteArrays(
                new byte[] {
                        (byte)((innerPacket.length >> 8) & 0xFF),
                        (byte)(innerPacket.length & 0xFF)
                },
                innerPacket,
                mIdentity.getPublicKey().getDEREncoded()
        );

        // Encrypt the inner packet using the hashed public elliptic key from #4
        // and the IV you generated at #2 using AES-256-CTR.
        byte[] encryptedInnerPacket = crypto.encryptAES256CTR(innerPacket, iv, innerPacketAESKey);

        // Create a signature from the encrypted inner packet using your own RSA
        // keypair, a SHA 256 digest, and PKCSv1.5 padding
        byte[] signature = crypto.signRSA(mIdentity.getPrivateKey(), encryptedInnerPacket);
        
        // Encrypt the signature using a new AES-256-CTR cipher with the same IV
        // and a new SHA-256 key hashed from the public elliptic curve key + the
        // line value (16 bytes from #5), then base64 encode the result as the
        // value for the sig param.
        byte[] signatureKey = crypto.sha256Digest(
                Util.concatenateByteArrays(encodedECPublicKey, mLineIdentifier)
        ); 
        byte[] encryptedSignature =
                crypto.encryptAES256CTR(signature, iv, signatureKey);

        // Form the outer packet containing the open type, open param, the
        // generated iv, and the sig value.
        byte[] outerPacket;
        try {
            outerPacket = new JSONStringer()
                .object()
                .key(TYPE_KEY)
                .value(OPEN_TYPE)
                .key(IV_KEY)
                .value(Util.bytesToHex(iv))
                .key(SIG_KEY)
                .value(Util.base64Encode(encryptedSignature))
                .key(OPEN_KEY)
                .value(Util.base64Encode(openParameter))
                .endObject()
                .toString()
                .getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new TelehashException(e);
        } catch (JSONException e) {
            throw new TelehashException(e);
        }
        
        byte[] lengthPrefix = new byte[LENGTH_PREFIX_SIZE];
        lengthPrefix[0] = (byte)((outerPacket.length >> 8) & 0xFF);
        lengthPrefix[1] = (byte)(outerPacket.length & 0xFF);
        byte[] packet = Util.concatenateByteArrays(
                lengthPrefix,
                outerPacket,
                encryptedInnerPacket
        );

        return packet;
    }
    
    public static OpenPacket parse(
            Telehash telehash,
            JSONObject json,
            byte[] body
    ) throws TelehashException {
        Crypto crypto = telehash.getCrypto();
        
        // extract required JSON values
        String ivString = json.getString(IV_KEY);
        assertNotNull(ivString);
        byte[] iv = Util.hexToBytes(ivString);
        assertBufferSize(iv, IV_SIZE);
        String sigString = json.getString(SIG_KEY);
        assertNotNull(sigString);
        byte[] encryptedSignature = Util.base64Decode(sigString);
        assertNotNull(encryptedSignature);
        String openString = json.getString(OPEN_KEY);
        assertNotNull(openString);
        byte[] openParameter = Util.base64Decode(openString);
        assertNotNull(openParameter);
        
        // Using your private key and OAEP padding, decrypt the open param,
        // extracting the ECC public key (in uncompressed form) of the sender
        byte[] ellipticCurvePublicKeyBuffer =
                crypto.decryptRSAOAEP(telehash.getIdentity().getPrivateKey(), openParameter);
        ECPublicKey ellipticCurvePublicKey = crypto.decodeECPublicKey(ellipticCurvePublicKeyBuffer);
        
        // Hash the ECC public key with SHA-256 to generate the AES key
        byte[] innerPacketKey = crypto.sha256Digest(ellipticCurvePublicKeyBuffer);
        
        // Decrypt the inner packet using the generated key and IV value with
        // the AES-256-CTR algorithm.
        byte[] innerPacketBuffer = crypto.decryptAES256CTR(body, iv, innerPacketKey);
        JsonAndBody innerPacket = splitPacket(innerPacketBuffer);
        
        // extract required JSON values from the inner packet
        long openTime = innerPacket.json.getLong(OPEN_TIME_KEY);
        String destinationString = innerPacket.json.getString(DESTINATION_KEY);
        assertNotNull(destinationString);
        byte[] destination = Util.hexToBytes(destinationString);
        assertBufferSize(destination, HASHNAME_SIZE);
        String lineIdentifierString = innerPacket.json.getString(LINE_IDENTIFIER_KEY);
        assertNotNull(lineIdentifierString);
        byte[] lineIdentifier = Util.hexToBytes(lineIdentifierString);
        assertBufferSize(lineIdentifier, LINE_IDENTIFIER_SIZE);
        
        // Verify the to value of the inner packet matches your hashname
        if (! Arrays.equals(destination, telehash.getIdentity().getHashName())) {
            throw new TelehashException("received packet not destined for this identity.");
        }
        
        // Extract the RSA public key of the sender from the inner packet BODY
        // (binary DER format)
        RSAPublicKey senderRSAPublicKey = crypto.decodeRSAPublicKey(innerPacket.body);
        
        // SHA-256 hash the RSA public key to derive the sender's hashname
        // TODO: endpoint
        Node sourceNode = new Node(senderRSAPublicKey, null);
        
        // Verify the at timestamp is both within a reasonable amount of time to
        // account for network delays and clock skew, and is newer than any
        // other 'open' requests received from the sender.
        long elapsedTime = System.currentTimeMillis() - openTime;
        if (elapsedTime > MAXIMUM_TIME_DIFFERENCE) {
            throw new TelehashException("received open packet is too old.");
        }
        // TODO: "newer than any other open..." <-- should be handled at higher level
        
        // SHA-256 hash the ECC public key with the 16 bytes derived from the
        // inner line hex value to generate a new AES key
        byte[] signatureKey = crypto.sha256Digest(
                Util.concatenateByteArrays(ellipticCurvePublicKeyBuffer, lineIdentifier)
        );
        
        // Decrypt the outer packet sig value using AES-256-CTR with the key
        // from #8 and the same IV value as #3.
        byte[] signature = crypto.decryptAES256CTR(encryptedSignature, iv, signatureKey);
        
        // Using the RSA public key of the sender, verify the signature
        // (decrypted in #9) of the original (encrypted) form of the inner
        // packet
        if (! crypto.verifyRSA(senderRSAPublicKey, body, signature)) {
            throw new TelehashException("signature verification failed.");
        }
        
        // If an open packet has not already been sent to this hashname, do so
        // by creating one following the steps above
        // TODO: handle at higher level
        
        // After sending your own open packet in response, you may now generate
        // a line shared secret using the received and sent ECC public keys and
        // Elliptic Curve Diffie-Hellman (ECDH).
        // TODO: handle at higher level

        return new OpenPacket(sourceNode, ellipticCurvePublicKey, openTime, lineIdentifier);
    }
}
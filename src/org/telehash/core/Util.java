package org.telehash.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

import org.telehash.crypto.Crypto;
import org.telehash.crypto.impl.CryptoImpl;
import org.telehash.network.Network;
import org.telehash.network.impl.NetworkImpl;
import org.telehash.storage.Storage;
import org.telehash.storage.impl.StorageImpl;

/**
 * This class contains various static utility methods used throughout
 * the library.
 */
public class Util {
    
    private static int IO_BUFFER_SIZE = 4096;
    
    // poor man's dependency injection: static fields.
    // TODO: come up with a better way
    private static Crypto sCrypto;
    private static Storage sStorage;
    private static Network sNetwork;

    /**
     * Return a usable implementation of the Crypto interface.
     * 
     * @return the Crypto implementation.
     */
    public static Crypto getCryptoInstance() {
        if (sCrypto == null) {
            sCrypto = new CryptoImpl();
        }
        return sCrypto;
    };

    /**
     * Return a usable implementation of the Storage interface.
     *
     * @return the Storage implementation.
     */
    public static Storage getStorageInstance() {
        if (sStorage == null) {
            sStorage = new StorageImpl();
        }
        return sStorage;
    }

    /**
     * Return a usable implementation of the Network interface.
     *
     * @return the Network implementation.
     */
    public static Network getNetworkInstance() {
        if (sNetwork == null) {
            sNetwork = new NetworkImpl();
        }
        return sNetwork;
    }

    /**
     * Encode the provided byte array as a string of hex digits.
     * 
     * @param buffer The byte array to encode.
     * @return The string of hex digits.
     */
    public static String bytesToHex(byte[] buffer) {
        StringBuilder sb = new StringBuilder();
        for (byte b : buffer) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
    
    /**
     * Decode the provided hex string into a byte array.
     * 
     * @param hex The hex string to decode.
     * @return The decoded byte array.
     */
    public static byte[] hexToBytes(String hex) {
        int hexLength = hex.length(); 
        if (hexLength % 2 != 0) {
            return null;
        }
        byte[] buffer = new byte[hexLength/2];
        int accumulator = 0;
        for (int i=0, j=0; i<hexLength; i++) {
            char c = hex.charAt(i);
            int v = 0;
            if (c >= '0' && c <= '9') {
                v = c - '0';
            } else if (c >= 'a' && c <= 'f') {
                v = c - 'a' + 10;
            } else if (c >= 'A' && c <= 'F') {
                v = c - 'A' + 10;
            } else {
                // illegal character
                return null;
            }
            if (i%2==0) {
                accumulator = v << 4;
            } else {
                buffer[j] = (byte)(accumulator | v); 
                j++;
            }
        }
        return buffer;
    }
    
    private static final char[] BASE64_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
    
    /**
     * Encode the provided byte array as Base64.
     * 
     * @param buffer The byte array to encode.
     * @return The Base64 encoding.
     */
    public static String base64Encode(byte[] buffer) {
        StringBuilder sb = new StringBuilder();
        
        int nchars = 0;
        for (int i=0; i<buffer.length; i+=3) {
            if ((buffer.length - i) < 3) {
                nchars = buffer.length - i;
            } else {
                nchars = 3;
            }
            int word = 0;
            for (int j=0; j<nchars; j++) {
                word = word | ((buffer[i+j]&0xFF) << (8*(3-j-1)));
            }
            for (int j=0; j<(nchars+1); j++) {
                sb.append(BASE64_ALPHABET[((word>>(6*(3-j))))&0x3F]);
            }
        }
        if (nchars == 1) {
            sb.append("==");
        } else if (nchars == 2) {
            sb.append("=");
        }
        return sb.toString();
    }
    
    /**
     * Decode the provided Base64 into a byte array.
     * 
     * @param base64 The Base64 string to decode.
     * @return The decoded byte array.
     */
    public static byte[] base64Decode(String base64) {
        int base64Length = base64.length();
        
        // add padding if absent.
        int remainder = base64Length % 4;
        if (remainder > 0) {
            for (int i=0; i<(4-remainder); i++) {
                base64 += "=";
                base64Length++;
            }
        }
        
        // count padding chars
        int padding = 0;
        for (int i=(base64Length-1); i>=(base64Length-3); i--) {
            if (i<0 || base64.charAt(i) != '=') {
                break;
            } else {
                padding++;
            }
        }
        
        int bufferOffset = 0;
        byte[] buffer = new byte[base64Length*3/4 - padding];
        int accumulator = 0;
        for (int i=0, j=0; i<base64Length; i++) {
            char c = base64.charAt(i);
            int v = 0;
            if (c >= '0' && c <= '9') {
                v = c - '0' + 52;
            } else if (c >= 'a' && c <= 'z') {
                v = c - 'a' + 26;
            } else if (c >= 'A' && c <= 'Z') {
                v = c - 'A';
            } else if (c == '+') {
                v = 62;
            } else if (c == '/') {
                v = 63;
            } else if (c == '=') {
                v = 0;
            } else {
                // illegal character
                return null;
            }
            
            accumulator = (accumulator<<6) | v;
            if (i%4==3) {
                for (j=0; j<3; j++) {
                    if (bufferOffset == buffer.length) {
                        break;
                    }
                    buffer[bufferOffset++] = (byte)((accumulator>>16) & 0xFF);
                    accumulator <<= 8;
                }
            }
        }
        return buffer;
    }
    
    /**
     * Read an entire file at once and return the contents.  This should
     * only be used for very small files.
     * 
     * @param filename The filename of the file to read.
     * @return A byte array of the file's contents.
     * @throws IOException If a problem happened while reading the file.
     */
    public static byte[] slurpFile(String filename) throws IOException {
        File file = new File(filename);
        FileInputStream fis = new FileInputStream(file);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        
        byte[] buffer = new byte[IO_BUFFER_SIZE];
        int nbytes;
        while ((nbytes = fis.read(buffer)) != -1) {
            baos.write(buffer, 0, nbytes);
        }
        
        fis.close();
        baos.close();
        return baos.toByteArray();
    }
    
    /**
     * Read an entire UTF-8 text file at once and return the contents
     * as a string.  This should only be used for very small files.
     * 
     * @param filename The filename of the file to read.
     * @return The contents of the file as a string.
     * @throws IOException If a problem happened while reading the file.
     */
    public static String slurpTextFile(String filename) throws IOException {
        return new String(slurpFile(filename), "UTF-8");
    }
    
    /**
     * Concatenate two byte arrays.
     * 
     * @param a The first byte array.
     * @param b The second byte array.
     * @return A new byte array containing the concatenation.
     */
    public static byte[] concatenateByteArrays(byte[] a, byte[] b) {
        byte[] z = new byte[a.length + b.length];
        System.arraycopy(a, 0, z, 0, a.length);
        System.arraycopy(b, 0, z, a.length, b.length);
        return z;        
    }
    
    /**
     * Concatenate three byte arrays.
     * 
     * @param a The first byte array.
     * @param b The second byte array.
     * @param c The third byte array.
     * @return A new byte array containing the concatenation.
     */
    public static byte[] concatenateByteArrays(byte[] a, byte[] b, byte[] c) {
        byte[] z = new byte[a.length + b.length + c.length];
        System.arraycopy(a, 0, z, 0, a.length);
        System.arraycopy(b, 0, z, a.length, b.length);
        System.arraycopy(c, 0, z, a.length+b.length, c.length);
        return z;
    }
    
    /**
     * Dump a hexadecimal representation of the specified byte buffer to
     * standard output.
     * 
     * @param buffer The byte buffer.
     */
    public static void hexdump(byte[] buffer) {
        if (buffer == null) {
            System.out.println("<null>");
            return;
        }
        for (int i=0; i<buffer.length; i+=16) {
            System.out.printf("%04X: ", i);
            for (int j=i; j<(i+16); j++) {
                if (j < buffer.length) {
                    System.out.printf("%02X ", buffer[j]);
                } else {
                    System.out.print("   ");
                }
            }
            for (int j=i; j<(i+16); j++) {
                if (j < buffer.length) {
                    int c = buffer[j];
                    if (c >= 0x20 && c < 0x7F) {
                        System.out.print((char)c);                        
                    } else {
                        System.out.print(".");
                    }
                }
            }
            System.out.println();
        }
    }
}

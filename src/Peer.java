import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.HashMap;

public class Peer implements Runnable {
    private final String PROTOCOL_HEADER = "BitTorrent protocol";

    HashMap<String, Object> peerInfo;
    ByteBuffer infoHash;
    ByteBuffer peerId;
    ByteBuffer handshake;

    public Peer(HashMap<String, Object> peerInfo, ByteBuffer infoHash, ByteBuffer peerId) {
        this.peerInfo = peerInfo;
        this.infoHash = infoHash.duplicate();
        this.peerId = peerId.duplicate();
        this.handshake = createHandshake();
    }

    @Override
    public void run() {
        // TODO: Loop dis
        Socket sock;
        try {
            sock = new Socket((String)peerInfo.get("ip"), (Integer)peerInfo.get("port"));
            OutputStream outStream = sock.getOutputStream();
            BufferedReader inputReader = new BufferedReader(new InputStreamReader(sock.getInputStream()));
            outStream.write(this.handshake.array());
            inputReader.readLine();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void stop() {
        // TODO:
    }

    /**
     * Creates the peer handshake
     *
     * @return The handshake byte buffer
     */
    public ByteBuffer createHandshake() {
        ByteBuffer handshakeBuffer = ByteBuffer.allocate(68); // 49 bytes + sizeof PROTOCOL_HEADER

        this.infoHash.position(0);
        this.peerId.position(0);

        handshakeBuffer.put((byte) 19);
        handshakeBuffer.put(PROTOCOL_HEADER.getBytes());
        handshakeBuffer.putInt(0);
        handshakeBuffer.putInt(0);
        handshakeBuffer.put(this.infoHash);
        handshakeBuffer.put(this.peerId);

        this.infoHash.position(0);
        this.peerId.position(0);

        return handshakeBuffer;
    }
}


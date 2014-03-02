import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.HashMap;

/**
 * A connection to a peer that is responsible for downloading
 * a piece of a piece. Is run in a new thread.
 *
 * @author eddiezane
 * @author wlangford
 */
public class Peer implements Runnable {
    private final String PROTOCOL_HEADER = "BitTorrent protocol";
    private final byte[] KEEP_ALIVE = new byte[]{0,0,0,0};
    private final byte[] CHOKE = new byte[]{0,0,0,1,0};
    private final byte[] UNCHOKE = new byte[]{0,0,0,1,1};
    private final byte[] INTERESTED = new byte[]{0,0,0,1,2};
    private final byte[] NOT_INTERESTED = new byte[]{0,0,0,1,3};
    private final byte[] HAVE = new byte[]{0,0,0,5,4};
    private final byte[] REQUEST = new byte[]{0,0,0,13,6};

    private final byte[] PIECE = new byte[]{0,0,0,0}; // TODO: PIECE

    private HashMap<String, Object> peerInfo;
    private ByteBuffer infoHash;
    private ByteBuffer peerId;
    private ByteBuffer handshake;
    private String state;

	private boolean running = true;

	private Piece currentPiece = null;
    private Socket sock;

    public Peer(HashMap<String, Object> peerInfo, ByteBuffer infoHash, ByteBuffer peerId) {
        this.peerInfo = peerInfo;
        this.infoHash = infoHash.duplicate();
        this.peerId = peerId.duplicate();
        this.handshake = createHandshake();
    }

    /**
     * TODO: Document this
     */
    @Override
    public void run() {
        // TODO: Loop dis
        // TODO: Keep alives
        // TODO: Verify talking to right client
        // TODO: Set state
        byte[] buffer = new byte[1024];
        try {
            System.out.println("Connecting to peer: " + peerInfo.get("ip") + " : " + peerInfo.get("port"));
            sock = new Socket((String)peerInfo.get("ip"), (Integer)peerInfo.get("port"));
            OutputStream outStream = sock.getOutputStream();
            InputStream inputStream = sock.getInputStream();
            outStream.write(this.handshake.array());
            inputStream.read(buffer);
            outStream.write(INTERESTED);
	        while (running) {
		        if (currentPiece != null) {
			        byte[] bytes = new byte[currentPiece.getSize()];
			        for (int j = 0; j < bytes.length; ++j) {
				        bytes[j] = (byte)(j & 0xff);
			        }
			        ByteBuffer bb = ByteBuffer.wrap(bytes);
			        currentPiece.getOwner().putPiece(bb, currentPiece);
			        currentPiece = null;
		        }
		        try {
			        Thread.sleep(10);
		        } catch (InterruptedException e) {
			        e.printStackTrace();
		        }
	        }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void stop() {
	    running = false;
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

        handshakeBuffer.put((byte)19);
        handshakeBuffer.put(PROTOCOL_HEADER.getBytes());
        handshakeBuffer.putInt(0); // Two ints are 8 bytes
        handshakeBuffer.putInt(0);
        handshakeBuffer.put(this.infoHash);
        handshakeBuffer.put(this.peerId);

        this.infoHash.position(0);
        this.peerId.position(0);

        return handshakeBuffer;
    }

    /**
     * Used to know if a peer can provide a certain piece
     *
     * @param index Index of a piece relative to a torrent
     * @return Whether or not the peer has the piece
     */
    public boolean canGetPiece(int index) {
        // TODO: Return if peer has piece
        return true;
    }

	public void getPiece(Piece piece) {
		// TODO: Download piece.
		currentPiece = piece;
	}

    /**
     * Constructs a request message for a peer
     *
     * @param index
     * @param begin
     * @param length
     * @return
     */
    public ByteBuffer getRequestMessage(int index, int begin, int length) {
        ByteBuffer bb = ByteBuffer.allocate(17);
        bb.put(REQUEST);
        bb.put((byte)index);
        bb.put((byte)begin);
        bb.put((byte)length);
        return bb;
    }

    /**
     * Constructs a gave message for a peer
     *
     * @param index
     * @return
     */
    public ByteBuffer getHaveMessage(int index) {
        ByteBuffer bb = ByteBuffer.allocate(9);
        bb.put(HAVE);
        bb.put((byte)index);
        return bb;
    }
}


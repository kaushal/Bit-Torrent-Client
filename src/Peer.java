import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.BitSet;
import java.util.HashMap;

public class Peer implements Runnable {
    private final String PROTOCOL_HEADER = "BitTorrent protocol";
    private final byte[] KEEP_ALIVE = new byte[]{0,0,0,0};
    private final byte[] CHOKE = new byte[]{0,0,0,1,0};
    private final byte[] UNCHOKE = new byte[]{0,0,0,1,1};
    private final byte[] INTERESTED = new byte[]{0,0,0,1,2};
    private final byte[] NOT_INTERESTED = new byte[]{0,0,0,1,3};
    private final byte[] HAVE = new byte[]{0,0,0,5,4};
    private final byte[] REQUEST = new byte[]{0,0,1,3,6};

    private final byte[] PIECE = new byte[]{0,0,0,0}; // TODO: PIECE

    private HashMap<String, Object> peerInfo;
    private ByteBuffer infoHash;
    private ByteBuffer peerId;
    private ByteBuffer handshake;
    private String state;
	private BitSet availablePieces;
	private Torrent owner;

	private boolean running = true;

	private Piece currentPiece = null;
    private Socket sock;

    public Peer(HashMap<String, Object> peerInfo, Torrent owner, ByteBuffer infoHash, ByteBuffer peerId) {
        this.peerInfo = peerInfo;
        this.infoHash = infoHash.duplicate();
        this.peerId = peerId.duplicate();
        this.handshake = createHandshake();
	    this.owner = owner;
    }

    @Override
    public void run() {
        // TODO: Loop dis
        // TODO: Keep alives
        // TODO: Verify talking to right client
        byte[] buffer = new byte[1024];
        try {
            System.out.println("Connecting to peer: " + peerInfo.get("ip") + " : " + peerInfo.get("port"));
            sock = new Socket((String)peerInfo.get("ip"), (Integer)peerInfo.get("port"));
            OutputStream outStream = sock.getOutputStream();
            InputStream inputStream = sock.getInputStream();
            outStream.write(this.handshake.array());
            int len = inputStream.read(buffer);
	        boolean success = processHandshakeResponse(ByteBuffer.wrap(buffer,0,len));
	        if (!success) {
		        owner.peerDying(this);
		        return;
	        }

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


	public boolean processHandshakeResponse(ByteBuffer resp) {

		if (resp.get() != 19) return false; // Not BT
		if (((ByteBuffer)resp.slice().limit(19)).compareTo(ByteBuffer.wrap(PROTOCOL_HEADER.getBytes())) != 0) return false; // Not BT
		resp.position(resp.position()+19+8);
		if (((ByteBuffer)resp.slice().limit(20)).compareTo(infoHash) != 0) return false; // Wrong infohash
		resp.position(resp.position()+20);
		if (((ByteBuffer)resp.slice().limit(20)).compareTo(ByteBuffer.wrap(((String)peerInfo.get("peer id")).getBytes())) != 0) return false; // Wrong peerId
		resp.position(resp.position()+20);
		if (resp.hasRemaining()) {
			resp.compact().flip();
			int len = resp.getInt() - 1;
			if (resp.get() == 5) { // We have a pieces bitfield
				availablePieces = new BitSet(len*8);
				byte b = 0;
				for (int j = 0; j < len * 8; ++j) {
					if (j % 8 == 0) b = resp.get();
					availablePieces.set(j,((b << (j % 8)) & 0xf0) != 0);
				}
			}
		}
		if (resp.compareTo(ByteBuffer.wrap(PROTOCOL_HEADER.getBytes())) != 0) return false;


		return true;
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

    public boolean canGetPiece(int index) {
        // TODO: Return if peer has piece
	    try {
	        return availablePieces.get(index);
	    } catch (IndexOutOfBoundsException e) {
		    e.printStackTrace();
	    }
	    return false;
    }

	public void getPiece(Piece piece) {
		// TODO: Download piece.
		currentPiece = piece;
	}
}


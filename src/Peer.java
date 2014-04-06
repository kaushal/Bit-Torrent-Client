import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.BitSet;
import java.util.HashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * A connection to a peer that is responsible for downloading
 * a piece of a piece. Is run in a new thread.
 *
 * @author eddiezane
 * @author wlangford
 * @author kaushal 
 */
public class Peer extends Messager implements Runnable, NetworkClient {

	public enum PeerState {
		CONNECTING, BEGIN, HANDSHAKE, CHOKED, UNCHOKED, DOWNLOADING
	}
	private final Object debugLock = new Object();
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
	private PeerState state = PeerState.BEGIN;
	private String pieceState = null; // TODO: Change this to an enum.  Dirty dirty string comparison...
	private BitSet availablePieces = new BitSet();
	private Torrent owner;
	private long lastMessage;

	private boolean running = true;
	private boolean die = false;

	private Piece currentPiece = null;


	private SocketChannel channel;

	public Peer(HashMap<String, Object> peerInfo, Torrent owner, ByteBuffer infoHash, ByteBuffer peerId) throws IOException {
		this.peerInfo = peerInfo;
		this.infoHash = infoHash.duplicate();
		this.peerId = peerId.duplicate();
		this.handshake = createHandshake();
		this.owner = owner;
		this.channel = SocketChannel.open();
		this.channel.configureBlocking(false);
		if (!this.channel.connect(new InetSocketAddress(InetAddress.getByName((String)peerInfo.get("ip")),(Integer)peerInfo.get("port")))) {
			this.state = PeerState.CONNECTING;
		}
		NetworkOperator.getSharedOperator().register(channel, this);

	}

	@Override
	public void connectable() {
		try {
			if (!channel.finishConnect()) {
				return;
			}
		} catch (IOException e) {
			e.printStackTrace();
			return;
		}
		System.out.println("Connected...");
		if (this.state == PeerState.CONNECTING)
			this.state = PeerState.HANDSHAKE;

	}

	private ByteBuffer readBuffer = ByteBuffer.allocate(Piece.SLICE_SIZE * 2);
	@Override
	public void readable() throws IOException {
		System.out.println("Read");
		channel.read(readBuffer);
		if (readBuffer.position() <= 4) return;
		if (state == PeerState.HANDSHAKE) {
			// Handshake is a bit different
			if (readBuffer.position() >= 68) { // Handshake is complete.
				int ol = readBuffer.position();
				readBuffer.position(68).flip(); // Grab the first 68 bytes.
				ByteBuffer msgBuf = ByteBuffer.allocate(68);
				msgBuf.put(readBuffer);
				msgBuf.flip();
				msgBuf.position(0);
				readBuffer.limit(ol);
				readBuffer.compact();

				recv(msgBuf);
			}
		} else {
			int len;
			readBuffer.getInt(0);
			while (readBuffer.position() >= 4 && readBuffer.position() >= (len = ((ByteBuffer)readBuffer.duplicate().position(0)).getInt()+4)) { // We have a full message now!
				ByteBuffer msgBuf;

				int ol = readBuffer.position();
				readBuffer.position(len).flip();
				msgBuf = ByteBuffer.allocate(len);
				msgBuf.put(readBuffer);
				msgBuf.flip();

				readBuffer.limit(ol);
				readBuffer.compact();

				recv(msgBuf);
			}
		}
	}

	private ByteBuffer writeBuffer = null;
	@Override
	public void writable() {
		if (writeBuffer == null) {
			writeBuffer = outMessages.poll();
		}
		if (writeBuffer != null) {
			System.out.println("W:"+writeBuffer);
			try {
				channel.write(writeBuffer);
				this.lastMessage = System.currentTimeMillis();
				if (!writeBuffer.hasRemaining())
					writeBuffer = null;
			} catch (IOException e) {
				e.printStackTrace();
			}
		} else if (System.currentTimeMillis() - this.lastMessage > 105000) {
			this.send(ByteBuffer.wrap(KEEP_ALIVE));
		}
	}

	@Override
	public void acceptable() {
		System.out.println("Accepted...");

	}

	/**
	 * Run loop for the peer
	 */
	@Override
	public void run() {
		System.out.println("Connecting to peer: " + peerInfo.get("ip") + " : " + peerInfo.get("port"));
		this.send(this.handshake);

		while (running && !die) {

			ByteBuffer msg = inMessages.poll();
			if (msg != null) {
				handleMessage(msg);
			}
			if (currentPiece != null) {
				handlePiece();
			}
		}

		if (die) {
			owner.peerDying(this);
		}
	}

	/**
	 * Used to kill the peer
	 */
	public void stop() {
		running = false;
	}

	/**
	 * Handles the piece of the torrent. Responsible for getting slices
	 * to download.
	 *
	 */
	public void handlePiece() {
		if (pieceState == null) { // We haven't worked with this piece yet.
			if (state == PeerState.CHOKED) {
				send(ByteBuffer.wrap(INTERESTED));
				pieceState = "Interested";
			} else if (state == PeerState.UNCHOKED) {
				int slice = currentPiece.getNextSlice();
				if (slice == -1) { // We've gotten all of the slices already.  So, we're done! Yay.
					System.out.println("Starting a piece and there are no slices... Wtf?");
					currentPiece = null;
					return;
				}
				ByteBuffer buf = getRequestMessage(currentPiece.getIndex(), slice * (Piece.SLICE_SIZE), Math.min(Piece.SLICE_SIZE, currentPiece.getSize() - (slice * Piece.SLICE_SIZE)));
				send(buf);
				state = PeerState.DOWNLOADING;
				pieceState = "Downloading";
				System.out.println(peerInfo.get("peer id") + " Unchoke and interested");
			}
		}
	}

	/**
	 * Receives a message from the queue and acts with it. Is used
	 * to communicate with peers.
	 *
	 * @param message A message from this peer's message queue
	 */
	public void handleMessage(ByteBuffer message) {
//		synchronized (debugLock) {
		if (state == PeerState.HANDSHAKE) {
			System.out.println("Hand shaken.");
			if (message.get() != 19 || ((ByteBuffer)message.slice().limit(19)).compareTo(ByteBuffer.wrap(PROTOCOL_HEADER.getBytes())) != 0) { // Not BT
				die = true;
				return;
			}
			message.position(message.position()+19+8);
			if (((ByteBuffer)message.slice().limit(20)).compareTo(infoHash) != 0) { // Wrong infohash
				die = true;
				return;
			}
			message.position(message.position() + 20);
			if (((ByteBuffer)message.slice().limit(20)).compareTo(ByteBuffer.wrap(((String)peerInfo.get("peer id")).getBytes())) != 0) { // Wrong peerId
				die = true;
				return;
			}
			message.position(message.position() + 20);
			state = PeerState.CHOKED;
			if (message.hasRemaining()) {
				handleMessage(message);
			}
			return;
		}
//		System.out.print("Message: " + message.position() + " : " + message.limit() + " : ");
//		for (int jj = 0; jj < message.limit(); jj++) {
//			System.out.print(String.format("%02X",message.array()[jj]));
//		}
//		System.out.println("");
		int len = message.getInt();
		if (len == 0) return; // Keepalive
		byte type = 0;
		try {
			type = message.get();
		} catch (BufferUnderflowException e) {
			e.printStackTrace();
		}
		switch (type) {
			// TODO: Switch to constants
			case 0: // Choke
				System.out.println("Choke.");
				state = PeerState.CHOKED;
				break;
			case 1: // Unchoke
				System.out.println(peerInfo.get("peer id") + " Unchoke.");
				state = PeerState.UNCHOKED;
//				if (pieceState == "Interested") { // Always should...
				{
					int slice = currentPiece.getNextSlice();
					if (slice == -1) {
// TODO: Raise an exception, maybe?
						System.out.println(peerInfo.get("peer id") + " Starting a piece and there are no slices... Error?");
						currentPiece = null;
						break;
					}
					ByteBuffer buf = getRequestMessage(currentPiece.getIndex(), slice * (Piece.SLICE_SIZE), Math.min(Piece.SLICE_SIZE, currentPiece.getSize() - (slice * Piece.SLICE_SIZE)));
					send(buf);
					state = PeerState.DOWNLOADING;
					pieceState = "Downloading";
				}
				break;
			case 2: // Interested
				System.out.println(peerInfo.get("peer id") + " They want our body.");
				break;
			case 3: // Uninterested
				System.out.println(peerInfo.get("peer id") + " We need the gym.");
				break;
			case 4: // Have
                int msg = message.getInt();
                System.out.println(peerInfo.get("peer id") + " They have: " + msg);
                availablePieces.set(msg);
				break;
			case 5: // Bitfield
				len -= 1;
				availablePieces = new BitSet(len*8);
				byte b = 0;

				// Turn the bitfield into a BitSet
				for (int j = 0; j < len * 8; ++j) {
					if (j % 8 == 0) b = message.get();
					availablePieces.set(j, ((b << (j % 8)) & 0x80) != 0);
				}
				break;
			case 6: // Request
				System.out.println(peerInfo.get("peer id") + " They want our data.  We don't want to give it.  Because negative ratios are pro.");
				break;
			case 7: // Piece
				System.out.println(peerInfo.get("peer id") + " Incoming data.");
				ByteBuffer pieceBuffer = currentPiece.getByteBuffer();
				int idx = message.getInt();
				int begin = message.getInt();
				((ByteBuffer)pieceBuffer.position(begin)).put(message);
				currentPiece.putSlice(begin/(Piece.SLICE_SIZE));

				int slice = currentPiece.getNextSlice();
				if (slice == -1) { // We've gotten all of the slices already.  So, we're done! Yay.
					System.out.println(peerInfo.get("peer id") + " All done with this piece.");
					currentPiece.getOwner().putPiece(currentPiece);
					currentPiece = null;
					state = PeerState.UNCHOKED;
				} else {
					ByteBuffer buf = getRequestMessage(currentPiece.getIndex(), slice * (Piece.SLICE_SIZE), Math.min(Piece.SLICE_SIZE, currentPiece.getSize() - (slice * Piece.SLICE_SIZE)));
					send(buf);
				}
				break;

			default:
				// Shouldn't happen...
//		}
		}
	}

	/**
	 * Add a message to the queue of this peer
	 *
	 * @param msg message to add
	 */
	public void recvMessage(ByteBuffer msg) {
		inMessages.add(msg);
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
		try {
			return availablePieces.get(index);
		} catch (IndexOutOfBoundsException e) {
			e.printStackTrace();
		}
		return false;
	}

	/**
	 * Set a piece to be downloaded
	 *
	 * @param piece piece of the torrent to download
	 */
	public void getPiece(Piece piece) {
		pieceState = null;
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
//		System.out.println("Req: " + index + " " + begin + " " + length);
		ByteBuffer bb = ByteBuffer.allocate(17);
		bb.put(REQUEST);
		bb.putInt(index);
		bb.putInt(begin);
		bb.putInt(length);
		return bb;
	}

	/**
	 * Constructs a have message for a peer
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


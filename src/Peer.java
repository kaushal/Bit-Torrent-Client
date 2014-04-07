import edu.rutgers.cs.cs352.bt.exceptions.BencodingException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
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
public class Peer implements Runnable {
	public enum PeerState {
		BEGIN, HANDSHAKE, CHOKED, UNCHOKED, DOWNLOADING
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


	private ConcurrentLinkedQueue<ByteBuffer> messages = new ConcurrentLinkedQueue<ByteBuffer>();


	private class PeerSocketRunnable implements Runnable {
		private Peer p;
		private Socket sock;
		private String ip;
		private int port;

		private ConcurrentLinkedQueue<ByteBuffer> messages = new ConcurrentLinkedQueue<ByteBuffer>();
		private byte[] buffer = new byte[2<<14];


        /**
         *
         * @param p
         * @param ip
         * @param port
         */

		public PeerSocketRunnable(Peer p, String ip, int port) {
			this.p = p;
			this.ip = ip;
			this.port = port;
		}

        /**
         *
         * Main run loop for Peer thread.
         *
         */

		@Override
			public void run() {
				try {
					sock = new Socket(ip,port);
					OutputStream outputStream = sock.getOutputStream();
					InputStream inputStream = sock.getInputStream();
					int len = 0;
					ByteBuffer writingBuffer = ByteBuffer.wrap(buffer);
					writingBuffer.position(0);


					// Since TCP data comes in as a byte stream and not discrete datagrams, we need to reassemble
					// coherent messages.  This loop handles that.  buffer is responsible for data storage and is sufficiently
					// large for any messages it may receive.  Since the handshake doesn't match the rest of the protocol,
					// it is handled separately.
					while (running) {
						Thread.sleep(10);
						ByteBuffer msg = messages.poll();
						if (msg != null) { // We have a message to send.
                                outputStream.write(msg.array());
						}
						if (inputStream.available() > 0) { // We have bytes to read...
							byte[] tbuf = new byte[1024];
							len = inputStream.read(tbuf);
							writingBuffer.put(tbuf, 0, len);
						}
						if (writingBuffer.position() <= 4) continue; // Not enough to do anything yet...
						if (p.state == PeerState.HANDSHAKE) {
							// Handshake is a bit different
							if (writingBuffer.position() >= 68) { // Handshake is complete.
								int ol = writingBuffer.position();
								writingBuffer.position(68).flip(); // Grab the first 68 bytes.
								ByteBuffer msgBuf = ByteBuffer.allocate(68);
								msgBuf.put(writingBuffer);
								msgBuf.flip();
								msgBuf.position(0);
								writingBuffer.limit(ol);
								writingBuffer.compact();

								// Pass the message to the Peer object.
								p.recvMessage(msgBuf);
							}
						} else {
							while (writingBuffer.position() >= 4 && writingBuffer.position() >= (len = ByteBuffer.wrap(buffer).getInt()+4)) { // We have a full message now!
								ByteBuffer msgBuf;
								int ol = writingBuffer.position();
								writingBuffer.position(len).flip();
								msgBuf = ByteBuffer.allocate(len);
								msgBuf.put(writingBuffer);
								msgBuf.flip();

								writingBuffer.limit(ol);
								writingBuffer.compact();
								p.recvMessage(msgBuf);

							}
						}
					}
				} catch (IOException e) {
					e.printStackTrace();
				} catch (InterruptedException e) {
					e.printStackTrace();
				} finally {
					try { // Thanks, Java.
						sock.close();
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			}

		/**
		 * Used to add a message to the peer socket runnables queue.
		 * Also updates the time of last message sent.
		 *
		 * @param msg message to add to the queue
		 */
		public void sendMessage(ByteBuffer msg) {
			p.lastMessage = System.currentTimeMillis();
			messages.add(msg);
		}

		/**
		 * Used to kill the runnable
		 */
		public void shutdown() {
			running = false;
		}
	}

	private PeerSocketRunnable socketRunner;

	public Peer(HashMap<String, Object> peerInfo, Torrent owner, ByteBuffer infoHash, ByteBuffer peerId) {
		this.peerInfo = peerInfo;
		this.infoHash = infoHash.duplicate();
		this.peerId = peerId.duplicate();
		this.handshake = createHandshake();
		this.owner = owner;
	}

	/**
	 * Run loop for the peer
	 */
	@Override
		public void run() {
			state = PeerState.HANDSHAKE;
			try {
				System.out.println("Connecting to peer: " + peerInfo.get("ip") + " : " + peerInfo.get("port"));
				this.socketRunner = new PeerSocketRunnable(this, (String)peerInfo.get("ip"), (Integer)peerInfo.get("port"));
				(new Thread(this.socketRunner)).start();
				this.socketRunner.sendMessage(this.handshake);
				this.lastMessage = System.currentTimeMillis();

				while (running && !die) {
					// TODO: Check this time to see if this is already the timeout.
					// Since this can be delayed due to asynchronous message sending.
					if ((System.currentTimeMillis() - this.lastMessage) > 120000) {
						this.socketRunner.sendMessage(ByteBuffer.wrap(KEEP_ALIVE));
					}

					// TODO: See if you can just yield execution?
					Thread.sleep(10);

					ByteBuffer msg = messages.poll();
					if (msg != null) {
						handleMessage(msg);
					}
					if (currentPiece != null) {
						handlePiece();
					}
				}

				this.socketRunner.shutdown();
				if (die) {
					owner.peerDying(this);
					return;
				}
			} catch (InterruptedException e) {
				e.printStackTrace();
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
				socketRunner.sendMessage(ByteBuffer.wrap(INTERESTED));
				pieceState = "Interested";
			} else if (state == PeerState.UNCHOKED) {
				int slice = currentPiece.getNextSlice();
				if (slice == -1) { // We've gotten all of the slices already.  So, we're done! Yay.
					System.out.println("Starting a piece and there are no slices... Wtf?");
					currentPiece = null;
					return;
				}
				ByteBuffer buf = getRequestMessage(currentPiece.getIndex(), slice * (Piece.SLICE_SIZE), Math.min(Piece.SLICE_SIZE, currentPiece.getSize() - (slice * Piece.SLICE_SIZE)));
				socketRunner.sendMessage(buf);
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
		int len = message.getInt();
		if (len == 0) return; // Keepalive
		byte type = 0;
		try {
			type = message.get();
		} catch (BufferUnderflowException e) {
			e.printStackTrace();
		}
		switch (type) {
			case 0: // Choke
				System.out.println(peerInfo.get("peer id") + "Choke.");
				state = PeerState.CHOKED;
				break;
			case 1: // Unchoke
				System.out.println(peerInfo.get("peer id") + " Unchoke.");
				state = PeerState.UNCHOKED;

                {   // TODO: See if we need this: if (pieceState == "Interested") { // Always should...
					int slice = currentPiece.getNextSlice();
					if (slice == -1) {
						System.out.println(peerInfo.get("peer id") + " Starting a piece and there are no slices... Error?");
						currentPiece = null;
						break;
					}
					ByteBuffer buf = getRequestMessage(currentPiece.getIndex(), slice * (Piece.SLICE_SIZE), Math.min(Piece.SLICE_SIZE, currentPiece.getSize() - (slice * Piece.SLICE_SIZE)));
					socketRunner.sendMessage(buf);
					state = PeerState.DOWNLOADING;
					pieceState = "Downloading";
				}
				break;
			case 2: // Interested
                //step 1
                //unchoke or nothing
				System.out.println(peerInfo.get("peer id") + " They want our body.");
                System.out.println(state);
                socketRunner.sendMessage(getUnChokeMessage());

				break;
			case 3: // Not Interested
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
                //request message
                System.out.println("--------------------------------------------------------------------------------");
                System.out.println(peerInfo.get("peer id") + " They want our data, and we gon' give it to them");

                int ind = message.getInt();
                int start = message.getInt();
                int size = message.getInt();

                if (availablePieces.get(ind)) {
                    ByteBuffer pieceMessage = getPieceMessage(ind, start, size);
                    socketRunner.sendMessage(pieceMessage);
                    socketRunner.sendMessage(getChokeMessage()); // check this

                }

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
					socketRunner.sendMessage(buf);
				}
				break;

			default:
				// Shouldn't happen...
		}
	}


	public void sendHaveMessage(int index) {
		ByteBuffer bb = ByteBuffer.allocate(9);
		bb.put(HAVE);
		bb.putInt(index);
		System.out.println("---------------------------------------" + index + "-------------------------------------------");
		socketRunner.sendMessage(bb);
	}


	/**
	 * Add a message to the queue of this peer
	 *
	 * @param msg message to add
	 */
	public void recvMessage(ByteBuffer msg) {
		messages.add(msg);
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
     *
     * @param index
     * @param begin
     * @param length
     * @return
     */

    public ByteBuffer getPieceMessage(int index, int begin, int length) {
        ByteBuffer bb = ByteBuffer.allocate(9 + length);
        bb.put(PIECE);
        bb.putInt(begin);
	    Piece pc = owner.getPiece(index);
        System.out.println("---------------------------------------sending piece" + index + "-------------------------------------------");
	    bb.put(pc.getByteBuffer().array(),begin,length);
        return bb;
    }

    /**
     *
     * @return
     */

    public ByteBuffer getUnChokeMessage() {
        ByteBuffer bb = ByteBuffer.allocate(5);
        bb.put(UNCHOKE);
        return bb;
    }

    /**
     *
     * @return
     */

    public ByteBuffer getChokeMessage() {
        ByteBuffer bb = ByteBuffer.allocate(5);
        bb.put(CHOKE);
        return bb;
    }

    /**
     *
     * @return
     */

	public Piece getCurrentPiece() {
		return this.currentPiece;
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
		bb.putInt(index);
		return bb;
	}
}


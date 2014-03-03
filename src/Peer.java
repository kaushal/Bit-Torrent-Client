import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Queue;
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
	private String pieceState = null; // Dirty dirty
	private BitSet availablePieces = new BitSet();
	private Torrent owner;

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

		public PeerSocketRunnable(Peer p, String ip, int port) {
			this.p = p;
			this.ip = ip;
			this.port = port;
		}

		@Override
		public void run() {
			try {
				sock = new Socket(ip,port);
				OutputStream outputStream = sock.getOutputStream();
				InputStream inputStream = sock.getInputStream();
				int len = 0;
				byte[] buffer = new byte[(2<<13)*2];
				ByteBuffer writingBuffer = ByteBuffer.wrap(buffer);
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
					if (writingBuffer.position() <= 4) continue; // Not enough to know yet...
					if (p.state == PeerState.HANDSHAKE) {
						// Handshake is a bit different
						if (writingBuffer.position() >= 68) { // Handshake is complete.
							int ol = writingBuffer.position();
							writingBuffer.position(68).flip(); // Grab the first 68 bytes.
							ByteBuffer msgBuf = ByteBuffer.allocate(68);
							msgBuf.put(writingBuffer);
							msgBuf.flip();
							writingBuffer.limit(ol);
							writingBuffer.compact();
							p.recvMessage(msgBuf);
						}
					} else {
						len = ByteBuffer.wrap(buffer).getInt()+4;
						while (writingBuffer.position() >= len) { // We have a full message now!
							int ol = writingBuffer.position();
							writingBuffer.position(len).flip();
							ByteBuffer msgBuf = ByteBuffer.allocate(len);
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

		public void sendMessage(ByteBuffer msg) {
			messages.add(msg);
		}
		public void shutdown() {
			running = false;
		}
	}

	private PeerSocketRunnable socketRunner;

	private Socket sock;

    public Peer(HashMap<String, Object> peerInfo, Torrent owner, ByteBuffer infoHash, ByteBuffer peerId) {
        this.peerInfo = peerInfo;
        this.infoHash = infoHash.duplicate();
        this.peerId = peerId.duplicate();
        this.handshake = createHandshake();
	    this.owner = owner;

    }

    /**
     * TODO: Document this
     */
    @Override
    public void run() {
        // TODO: Keep alives
        // TODO: Set state
        byte[] buffer = new byte[2<<14]; // 2^15 bytes.
	    state = PeerState.HANDSHAKE;
        try {
            System.out.println("Connecting to peer: " + peerInfo.get("ip") + " : " + peerInfo.get("port"));
	        this.socketRunner = new PeerSocketRunnable(this, (String)peerInfo.get("ip"), (Integer)peerInfo.get("port"));
	        (new Thread(this.socketRunner)).start();
	        this.socketRunner.sendMessage(this.createHandshake());

	        while (running && !die) {
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

//	        while (running) {
//		        try {
//			        Thread.sleep(10);
//		        } catch (InterruptedException e) {
//			        e.printStackTrace();
//		        }
//		        if (currentPiece != null) {
//			        if (state == PeerState.CHOKED) {
//			            outStream.write(INTERESTED);
//			        } else if (state == PeerState.UNCHOKED) {
//				        int slice = currentPiece.getNextSlice();
//				        if (slice == -1) { // We've gotten all of the slices already.  So, we're done! Yay.
//					        currentPiece.getOwner().putPiece(currentPiece);
//					        currentPiece = null;
//					        continue;
//				        }
//				        ByteBuffer buf = getRequestMessage(currentPiece.getIndex(), slice * (2<<13), Math.min(2<<13, currentPiece.getSize() - (slice * 2<<13)));
//				        outStream.write(buf.array());
//				        state = PeerState.DOWNLOADING;
//			        } else if (state == PeerState.DOWNLOADING) {
//				        // Shouldn't happen? Question mark?
//				        continue;
//			        }
//
//			        len = inputStream.read(buffer);
//			        parseResponse(ByteBuffer.wrap(buffer,0,len));
//		        }
//          }
    }

    public void stop() {
	    running = false;
    }

	public void handlePiece  () {
		if (pieceState == null) { // We haven't worked with this piece yet.
			if (state == PeerState.CHOKED) {
			socketRunner.sendMessage(ByteBuffer.wrap(INTERESTED));
			pieceState = "Interested";
			} else if (state == PeerState.UNCHOKED) {
				int slice = currentPiece.getNextSlice();
				if (slice == -1) { // We've gotten all of the slices already.  So, we're done! Yay.
					System.out.println("Starting a piece and there are no slices... Wtf?");
//					        currentPiece.getOwner().putPiece(currentPiece);
					currentPiece = null;
					return;
				}
				ByteBuffer buf = getRequestMessage(currentPiece.getIndex(), slice * (2<<13), Math.min(2<<13, currentPiece.getSize() - (slice * 2<<13)));
				socketRunner.sendMessage(buf);
				state = PeerState.DOWNLOADING;
				pieceState = "Downloading";
				System.out.println("Unchoke and interested");
			}
		}
	}

	public void handleMessage(ByteBuffer message) {
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
		System.out.print("Message: " + message.position() + " : " + message.limit() + " : ");
		for (int jj = 0; jj < message.limit(); jj++) {
				System.out.print(String.format("%02X",message.array()[jj]));
		}
		System.out.println("");
		int len = message.getInt();
		if (len == 0) return; // Keepalive
		byte type = message.get();
		switch (type) {
			// TODO: Switch to constants
			case 0: // Choke
				System.out.println("AEA");
				state = PeerState.CHOKED;
				break;
			case 1: // Unchoke
				System.out.println("Unchoke.");
				state = PeerState.UNCHOKED;
				if (pieceState == "Interested") { // Always should...
					int slice = currentPiece.getNextSlice();
			        if (slice == -1) { // We've gotten all of the slices already.  So, we're done! Yay.
				        System.out.println("Starting a piece and there are no slices... Wtf?");
//					        currentPiece.getOwner().putPiece(currentPiece);
				        currentPiece = null;
				        break;
			        }
			        ByteBuffer buf = getRequestMessage(currentPiece.getIndex(), slice * (2<<13), Math.min(2<<13, currentPiece.getSize() - (slice * 2<<13)));
					socketRunner.sendMessage(buf);
				    state = PeerState.DOWNLOADING;
					pieceState = "Downloading";
					System.out.println("Unchoke and interested");
				}
				break;
			case 2: // Interested
				System.out.println("They want our body.");
				break;
			case 3: // Uninterested
				System.out.println("We need the gym.");
				break;
			case 4: // Have
				System.out.println("They got something.");
				break;
			case 5: // Bitfield
				len -= 1;
				availablePieces = new BitSet(len*8);
				byte b = 0;
				for (int j = 0; j < len * 8; ++j) {
					if (j % 8 == 0) b = message.get();
					availablePieces.set(j, ((b << (j % 8)) & 0xf0) != 0);
				}
				break;
			case 6: // Request
				System.out.println("They want our data.  We don't want to give it.  Because negative ratios are pro. Also, fuck you.");
				break;
			case 7: // Piece
				System.out.println("Incoming data.");
				ByteBuffer pieceBuffer = currentPiece.getByteBuffer();
				int idx = message.getInt();
				int begin = message.getInt();
				((ByteBuffer)pieceBuffer.position(begin)).put(message);
				currentPiece.putSlice(begin/(2<<13));

				int slice = currentPiece.getNextSlice();
				if (slice == -1) { // We've gotten all of the slices already.  So, we're done! Yay.
					System.out.println("All done with this piece.");
					currentPiece.getOwner().putPiece(currentPiece);
					currentPiece = null;
					state = PeerState.UNCHOKED;
//					socketRunner.sendMessage(ByteBuffer.wrap(NOT_INTERESTED));
//					state = PeerState.CHOKED;
				} else {
					ByteBuffer buf = getRequestMessage(currentPiece.getIndex(), slice * (2<<13), Math.min(2<<13, currentPiece.getSize() - (slice * 2<<13)));
					socketRunner.sendMessage(buf);
				}
				break;

			default:
				// BLERGH FACE
				return;
		}
	}

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
        // TODO: Return if peer has piece
	    try {
	        return true; //availablePieces.get(index);
	    } catch (IndexOutOfBoundsException e) {
		    e.printStackTrace();
	    }
	    return false;
    }

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
	    System.out.println("Req: " + index + " " + begin + " " + length);
        ByteBuffer bb = ByteBuffer.allocate(17);
        bb.put(REQUEST);
        bb.putInt(index);
        bb.putInt(begin);
        bb.putInt(length);
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


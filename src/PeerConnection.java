import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.BitSet;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Responsible for abstracting network communications with a Peer from
 * the rest of the application.  One is created for each Peer.  Messages
 * received are passed back to the main Torrent object.
 *
 * @author eddiezane
 * @author kaushal
 * @author wlangford
 */
public class PeerConnection implements Runnable {


	public static final byte[] PROTOCOL_HEADER = new byte[]{'B','i','t','T','o','r','r','e','n','t',' ','p','r','o','t','o','c','o','l'};
	private final byte[] KEEP_ALIVE = new byte[]{0,0,0,0};
	private final byte[] CHOKE = new byte[]{0,0,0,1,0};
	private final byte[] UNCHOKE = new byte[]{0,0,0,1,1};
	private final byte[] INTERESTED = new byte[]{0,0,0,1,2};
	private final byte[] NOT_INTERESTED = new byte[]{0,0,0,1,3};
	private final byte[] HAVE = new byte[]{0,0,0,5,4};
	private final byte[] BITFIELD = new byte[]{0,0,0,0,5};
	private final byte[] REQUEST = new byte[]{0,0,0,13,6};
	private final byte[] PIECE = new byte[]{0,0,0,0,7};

	private Torrent torrent;
	private Socket sock;
	private String ip;
	private int port;
	private boolean running = true;
	private Timer keepaliveTimer;
	private boolean handshakeDone = false;
	private ByteBuffer peerId = null;

	private ConcurrentLinkedQueue<ByteBuffer> messages = new ConcurrentLinkedQueue<ByteBuffer>();
	private byte[] buffer = new byte[2<<14];


	/**
	 * Creates a new PeerConnection, but does not start it.
	 *
	 * @param t The torrent object that messages are to be passed to
	 * @param ip The ip address of the peer to connect to
	 * @param port The port of the peer to connect to
	 * @param peerId The peerId of the peer to connect to
	 */

	public PeerConnection(Torrent t, String ip, int port, ByteBuffer peerId) {
		this.torrent = t;
		this.ip = ip;
		this.peerId = peerId.duplicate();
		this.port = port;
		this.keepaliveTimer = new Timer(ip + " keepaliveTimer", true);

	}

	/**
	 * Peer thread run loop.  Responsible for sending messages when they are
	 * ready to be sent and receiving and reassembling messages.
	 */

	@Override
	public void run() {
		try {
			sock = new Socket(ip,port);
			OutputStream outputStream = sock.getOutputStream();
			InputStream inputStream = sock.getInputStream();
			int len;
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
				if (!handshakeDone) {
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
						PeerMessage peerMessage = processHandshake(msgBuf);
						if (peerMessage != null) {
							torrent.recvMessage(peerMessage);
						}
						handshakeDone = true;
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

						PeerMessage peerMessage = processMessage(msgBuf);
						if (peerMessage != null) {
							torrent.recvMessage(peerMessage);
						}
					}
				}
			}
		} catch (InterruptedException e) {
			e.printStackTrace();
		} catch (UnknownHostException e) {
			e.printStackTrace();
		} catch (IOException e) {
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
	 * Convert a handshake network message into a PeerMessage
	 * @param msg A ByteBuffer containing the handshake message
	 * @return A PeerMessage representing a handshake.
	 */
	private PeerMessage processHandshake(ByteBuffer msg) {
		return PeerMessage.Handshake(peerId,msg);
	}

	/**
	 * Convert a network message into a PeerMessage
	 * @param msg A ByteBuffer containing the message
	 * @return A PeerMessage representing whatever message was passed in.  Keep-Alive messages are discarded.
	 */
	private PeerMessage processMessage(ByteBuffer msg) {
		System.out.println("PROCESS MESSAGE");
		int len = msg.getInt();
		if (len == 0) // We don't actually emit a message when we get keepalives...
			return null;
		byte type = msg.get();
		switch (type) {
			case 0: // Choke
				return PeerMessage.Choke(peerId);
			case 1: // Unchoke
				return PeerMessage.Unchoke(peerId);
			case 2: // Interested
				return PeerMessage.Interested(peerId);
			case 3: // Not Interested
				return PeerMessage.NotInterested(peerId);
			case 4: // Have
				return PeerMessage.Have(peerId,msg.getInt());
			case 5: // Bitfield
				len -= 1;
				BitSet pcs = new BitSet(len*8);
				byte b = 0;
				// Turn the bitfield into a BitSet
				for (int j = 0; j < len * 8; ++j) {
					if (j % 8 == 0) b = msg.get();
					pcs.set(j, ((b << (j % 8)) & 0x80) != 0);
				}
				return PeerMessage.Bitfield(peerId,pcs);
			case 6: // Request
				int idx = msg.getInt();
				int begin = msg.getInt();
				int length = msg.getInt();
				return PeerMessage.Request(peerId,idx,begin,length);
			case 7: // Piece
				idx = msg.getInt();
				begin = msg.getInt();
				return PeerMessage.Piece(peerId,idx,begin,msg.compact());
			default:
				return null;
		}
	}


	private TimerTask tsk = null;
	/**
	 * Updates the keepalive timer.  Called every time a message is queued.
	 */
	private void resetKeepAlive() {
		if (tsk != null) {
			tsk.cancel();
			keepaliveTimer.purge();
		}
		tsk = new TimerTask() {
			@Override
			public void run() {
				messages.add(ByteBuffer.wrap(KEEP_ALIVE));
				resetKeepAlive();
			}
		};
		keepaliveTimer.schedule(tsk,120000);
	}

	/**
	 * All send* methods call back to this method, which adds the message
	 * to the messages queue and updates the keepalive timer.
	 *
	 * @param msg message to add to the queue
	 * @return Whether or not the message was successfully added.
	 */
	private boolean sendMessage(ByteBuffer msg) {
		resetKeepAlive();
		return messages.add(msg);
	}

	/* Convenience methods */

	/**
	 * Sends Choke message
	 * @return whether or not the message was queued to be sent
	 */
	public boolean sendChoke() {
		return sendMessage(ByteBuffer.wrap(CHOKE));
	}

	/**
	 * Sends Unchoke message
	 * @return whether or not the message was queued to be sent
	 */
	public boolean sendUnchoke() {
		return sendMessage(ByteBuffer.wrap(UNCHOKE));
	}

	/**
	 * Sends Have message
	 * @param index index of the piece that i
	 * @return whether or not the message was queued to be sent
	 */
	public boolean sendHave(int index) {
		ByteBuffer bb = ByteBuffer.allocate(9);
		bb.put(HAVE);
		bb.putInt(index);
		bb.flip();
		return sendMessage(bb);
	}

	/**
	 * Sends Piece message
	 * @param index index of the piece that is being sent
	 * @param begin beginning index of the piece data
	 * @param length number of bytes of the piece data being sent
	 * @param pieceData ByteBuffer containing piece data.  The bytes from begin to begin+length will be sent.
	 * @return whether or not the message was queued to be sent
	 */
	public boolean sendPiece(int index, int begin, int length, ByteBuffer pieceData) {
		ByteBuffer bb = ByteBuffer.allocate(13+length);
		bb.put(PIECE);
		bb.putInt(9+length,0);
		bb.putInt(index);
		bb.putInt(begin);
		bb.put(pieceData.array(), begin, length);
		bb.flip();
		return sendMessage(bb);
	}

	/**
	 * Sends Request message
	 * @param index index of the piece being requested
	 * @param begin beginning index of the piece data
	 * @param length number of bytes of the piece requested
	 * @return whether or not the message was queued to be sent
	 */
	public boolean sendRequest(int index, int begin, int length) {
		System.out.println("Request: " + index + " " + begin + " " + length);
		ByteBuffer bb = ByteBuffer.allocate(17);
		bb.put(REQUEST);
		bb.putInt(index);
		bb.putInt(begin);
		bb.putInt(length);
		bb.flip();
		return sendMessage(bb);
	}

	/**
	 * Sends Interested message
	 * @return whether or not the message was queued to be sent
	 */
	public boolean sendInterested() {
		return sendMessage(ByteBuffer.wrap(INTERESTED));
	}

	/**
	 * Sends Not Interested message
	 * @return whether or not the message was queued to be sent
	 */
	public boolean sendNotInterested() {
		return sendMessage(ByteBuffer.wrap(NOT_INTERESTED));
	}

	/**
	 * Sends Bitfield message
	 * @param bitfield Bytebuffer containing bitfield data
	 * @return whether or not the message was queued to be sent
	 */
	public boolean sendBitfield(ByteBuffer bitfield) {
		ByteBuffer bb = ByteBuffer.allocate(bitfield.limit()+5);
		bb.put(BITFIELD);
		bb.putInt(bitfield.limit() + 1, 0);
		bb.put(bitfield);
		bb.flip();
		return sendMessage(bb);
	}

	/**
	 * Sends Handshake message
	 * @param infoHash Infohash of the torrent for which we are connecting
	 * @param peerId Our peerId
	 * @return whether or not the message was queued to be sent
	 */
	public boolean sendHandshake(ByteBuffer infoHash, ByteBuffer peerId) {
		ByteBuffer handshakeBuffer = ByteBuffer.allocate(68); // 49 bytes + sizeof PROTOCOL_HEADER
		infoHash.position(0);
		peerId.position(0);

		handshakeBuffer.put((byte) 19);
		handshakeBuffer.put(PROTOCOL_HEADER);
		handshakeBuffer.putInt(0); // Two ints are 8 bytes
		handshakeBuffer.putInt(0);
		handshakeBuffer.put(infoHash);
		handshakeBuffer.put(peerId);
		
		return sendMessage(handshakeBuffer);
	}

	/**
	 * Used to kill the runnable
	 */
	public void shutdown() {
		running = false;
	}
}

import edu.rutgers.cs.cs352.bt.TorrentInfo;
import edu.rutgers.cs.cs352.bt.exceptions.BencodingException;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Represents a torrent object responsible for talking to peers
 * and downloading its own pieces in threads
 *
 * @author eddiezane
 * @author wlangford
 * @author kaushal
 */
@SuppressWarnings("SpellCheckingInspection")

// TODO: get rid of unnecessary print statements
public class Torrent implements Runnable {

	private TrackerConnection tracker;
	private TorrentInfo torrentInfo;
	private ArrayList<Piece> pieces;
	private String encodedInfoHash;
	private RandomAccessFile dataFile;
	private MappedByteBuffer fileByteBuffer;

	private ConcurrentLinkedQueue<PeerMessage> messages = new ConcurrentLinkedQueue<PeerMessage>();
	private String peerId;
	private HashMap<ByteBuffer,Peer> peers = new HashMap<ByteBuffer, Peer>();
	private BitSet piecesHad = null;

	private final Object fileLock = new Object();
	private final Object peerLock = new Object();
	private boolean running = true;

	private int port = 6881;

	private int uploaded = 0;
	private int downloaded = 0;
	private int left = 0;
	private int minInterval = 0;
	private int interval = 0;
	private long lastAnnounce = 0;
	private String fileName;
	private boolean sentComplete = false;

	private Timer chokeTimer;

	public Torrent(TorrentInfo ti, String fileName) {
		this.torrentInfo = ti;
		this.fileName = fileName;
		this.encodedInfoHash = encodeInfoHash(this.torrentInfo.info_hash.array());
		this.peerId = generateId();
		this.pieces = generatePieces();
		this.left = ti.file_length;
		this.tracker = new TrackerConnection(this.torrentInfo.announce_url);
		try {
			dataFile = new RandomAccessFile(this.fileName, "rw");
			fileByteBuffer = dataFile.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, (Integer)torrentInfo.info_map.get(TorrentInfo.KEY_LENGTH));
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		verify();
	}

	/**
	 * Verifies the file and updates what pieces we have.
	 */
	private void verify() {
		int offset = 0;
		MessageDigest md;
		byte[] sha1;
		try {
			for (Piece pc : pieces) {
				md = MessageDigest.getInstance("SHA-1");
				ByteBuffer bb = ByteBuffer.allocate(pc.getSize());
				bb.put((ByteBuffer) fileByteBuffer.duplicate().position(offset).limit(offset + pc.getSize())).flip();
				offset += pc.getSize();
				sha1 = md.digest(bb.array());
				if (Arrays.equals(sha1, pc.getHash())) {
					left -= pc.getSize();
					pc.setData(bb);
					pc.setState(Piece.PieceState.COMPLETE);
					piecesHad.set(pc.getIndex());
				}
			}
		} catch (NoSuchAlgorithmException e) {
			e.printStackTrace();
		}
	}
	/**
	 * Used by the RUBTClient to stop the torrent run loop
	 */
	public void stop() {
		running = false;
	}


	private void updateChokedPeers() {
		Peer choked = null;
		for (Peer p : peers.values()) {
			if (!p.choking) {
				System.out.println("Choke " + p);
				p.getPeerConnection().sendChoke();
				p.choking = true;
				choked = p;
				break;
			}
		}
		// There are no unchoked peers...
		if (choked == null) {
			int i = 3;
			for (Peer p : peers.values()) {
				System.out.println("Unchoke " + p);
				p.choking = false;
				p.getPeerConnection().sendUnchoke();
				if (--i == 0) break;
			}
		} else {
			// We choked an unchoked peer, so now unchoke a random peer.
			for (Peer p : peers.values()) {
				if (p.choking && p != choked) {
					System.out.println("Unchoke " + p);
					p.choking = false;
					p.getPeerConnection().sendUnchoke();
					return;
				}
			}
			// Since we couldn't find anyone else to unchoke, just unchoke
			// the choked peer again.
			System.out.println("Unchoke " + choked);
			choked.choking = false;
			choked.getPeerConnection().sendUnchoke();
		}

	}
	/**
	 *
	 * Main runnable for the thread.
	 * First finds list of peers with supplied ip addressed.
	 * Then enters run loop and ends once we get all the pieces
	 *
	 */
	@SuppressWarnings("unchecked")
	@Override
	public void run() {
		try {
			chokeTimer = new Timer("Choke Timer", true);
			chokeTimer.schedule(new TimerTask() {
				@Override
				public void run() {
					Torrent.this.updateChokedPeers();
				}
			}, 0, 30000);
			HashMap<ByteBuffer, Object> trackerResponse = tracker.start(peerId, port, uploaded, downloaded, left, encodedInfoHash);
			ArrayList<HashMap<ByteBuffer,Object>> tmp_peers = (ArrayList<HashMap<ByteBuffer, Object>>) trackerResponse.get(TrackerConnection.PEERS);
			int i = 99;
			for (HashMap<ByteBuffer,Object> p : tmp_peers) {
				if ((new String(((ByteBuffer)p.get(TrackerConnection.PEER_IP)).array())).equals("128.6.171.130") || (new String(((ByteBuffer)p.get(TrackerConnection.PEER_IP)).array())).equals("128.6.171.131")) {
					PeerConnection pc = new PeerConnection(this, new String(((ByteBuffer)p.get(TrackerConnection.PEER_IP)).array()), (Integer) p.get(TrackerConnection.PEER_PORT), (ByteBuffer)p.get(TrackerConnection.PEER_ID));
					pc.sendHandshake(this.torrentInfo.info_hash, ByteBuffer.wrap(this.peerId.getBytes()));
					Peer pr = new Peer((ByteBuffer) p.get(TrackerConnection.PEER_ID), pc);
					pr.handshook = false;
					peers.put(pr.getPeerId(), pr);
					(new Thread(pc)).start();
					if (--i == 0) break;
				}
			}
			minInterval = (Integer)trackerResponse.get(TrackerConnection.MIN_INTERVAL) * 1000;
			interval = (Integer)trackerResponse.get(TrackerConnection.INTERVAL) * 1000;

			// If there's no minimum interval...
			if (minInterval == 0)
				minInterval = interval / 2;

			lastAnnounce = System.currentTimeMillis();

			while (running) {
				if ((System.currentTimeMillis() - lastAnnounce) >= (minInterval - 5000)) {
					tracker.announce(peerId, port, uploaded, downloaded, left, encodedInfoHash);
					lastAnnounce = System.currentTimeMillis();
				}

				// Process all messages that have come in since the last time we looped.
				processMessages();

				// At this point, all peers that are no longer busy (in a multi-part communication)
				// are marked as not busy.  So, let's decide what we want each of them to do.
				processFreePeers();

				try {
					Thread.sleep(500);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		} catch (IOException e) {
			e.printStackTrace();
		} catch (BencodingException e) {
			e.printStackTrace();
		} finally {
			synchronized (fileLock) {
				try {
					if (dataFile != null) dataFile.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
				fileByteBuffer = null;
			}
			try {
				// send stopped message
				tracker.stop(peerId, port, uploaded, downloaded, left, encodedInfoHash);
			} catch (IOException e) {
				e.printStackTrace();
			} catch (BencodingException e) {
				e.printStackTrace();
			}
			for (Peer pr: peers.values()) { // shutdown all the peers
				pr.getPeerConnection().shutdown();
			}
		}
	}

	public void processMessages() {
		PeerMessage msg;
		while ((msg = messages.poll()) != null) {
			handleMessage(peers.get(msg.getPeerId()), msg);
		}
	}

	private void handleMessage(Peer pr, PeerMessage msg) {
		System.out.println("HandleMessage: " + msg.getType());
		if (!pr.handshook) {
			if (msg.getType() == PeerMessage.PeerMessageType.Handshake) {
				ByteBuffer message = msg.getBytes();
				System.out.println(pr.getPeerId() + "Hand shaken.");
				if (message.get() != 19 || ((ByteBuffer)message.slice().limit(19)).compareTo(ByteBuffer.wrap(PeerConnection.PROTOCOL_HEADER)) != 0) { // Not BT
					pr.getPeerConnection().shutdown();
					return;
				}
				if (((ByteBuffer)message.slice().position(19+8).limit(20)).compareTo(torrentInfo.info_hash) != 0) { // Wrong infohash
					pr.getPeerConnection().shutdown();
					return;
				}
				if (((ByteBuffer)message.slice().position(19+8+20)).compareTo(pr.getPeerId()) != 0) { // Wrong peerId
					pr.getPeerConnection().shutdown();
					return;
				}
				ByteBuffer bf = getBitField();
				if (bf != null) {
					System.out.println(pr + " Send:Bitfield");
					pr.getPeerConnection().sendBitfield(bf);
				}
				pr.handshook = true;
				return;
			}

		}
		switch (msg.getType()) {
			case Handshake:
				break;
			case Choke: // Choke
				System.out.println(pr + " Choke.");
				pr.choked = true;
				pr.outstandingRequests = 0;
				break;
			case Unchoke: // Unchoke
				System.out.println(pr + " Unchoke.");
				pr.choked = false;
				break;
			case Interested: // Interested
				pr.interested = true;
				System.out.println(pr + " Interested.");
				break;
			case NotInterested: // Not Interested
				pr.interested = false;
				System.out.println(pr + " Not interested.");
				break;
			case Have: // Have
				pr.setPieceAvailable(msg.getIndex());
				if (!pr.weHaveInterest && !piecesHad.get(msg.getIndex())) {
					System.out.println(pr + " We are interested...");
					pr.weHaveInterest = true;
					pr.getPeerConnection().sendInterested();
				}
				System.out.println(pr + " Have: " + msg.getIndex());
				break;
			case Bitfield: // Bitfield
				pr.setAvailablePieces(msg.getBitfield());
				BitSet tmp = ((BitSet)piecesHad.clone());
				tmp.flip(0, piecesHad.size());
				if (!tmp.intersects(pr.getAvailablePieces())) {
					pr.weHaveInterest = false;
					pr.getPeerConnection().sendNotInterested();
				} else {
					pr.weHaveInterest = true;
					pr.getPeerConnection().sendInterested();
				}
				break;

			case Request: // Request
				if (!pr.choking) {
					System.out.println(pr + " Send:Piece");
					pr.getPeerConnection().sendPiece(msg.getIndex(), msg.getBegin(), msg.getLength(), pieces.get(msg.getIndex()).getByteBuffer());
					uploaded += msg.getLength();
				}

				break;
			case Piece: // Piece
				System.out.println(pr + " Incoming data.");
				Piece pc = pieces.get(msg.getIndex());
				pr.outstandingRequests--;

				System.out.println("Obtain:  " + msg.getIndex() + " " + msg.getBegin() + " " + msg.getLength());
				((ByteBuffer)pc.getByteBuffer().position(msg.getBegin())).put(msg.getBytes());
				pc.putSlice(msg.getBegin() / Piece.SLICE_SIZE);

				int slice = pc.getNextSlice();
				if (slice == -1) {
					if (!pc.isLoadingSlices()) {
						System.out.println(pr + " No more slices to grab. " + pc.getIndex() + ".");
						putPiece(pc);
					} else {
						System.out.println(pr + " Waiting on remaining slices. " + pc.getIndex() + ".");
					}
				} else {
					System.out.println(pr + " Send:Request");
					pr.getPeerConnection().sendRequest(pc.getIndex(), pc.getBeginOfSlice(slice), pc.getLengthOfSlice(slice));
				}

				break;

			case Cancel:
				break;
			default:
				// Shouldn't happen...
		}
	}

	private void processFreePeers() {
		for (Peer p : peers.values()) {
			if (!p.handshook)
				continue;
			if (!p.choked && p.outstandingRequests < 5) {
				Piece pc = choosePiece(p);
				if (pc == null) { // There's no piece to download from this peer...
					continue;
				}
				int slice = pc.getNextSlice();
				if (slice != -1) {
					p.outstandingRequests++;
					System.out.println(p + " Send:Request");
					p.getPeerConnection().sendRequest(pc.getIndex(), pc.getBeginOfSlice(slice), pc.getLengthOfSlice(slice));
				}
			} else {
//				System.out.println(p + " Waiting.");
			}
		}
	}

	private Piece choosePiece(Peer pr) {
		// TODO: Implement rarest-piece algorithms...
//		int[] pieceRanks = new int[pieces.size()];
//
//		for(Piece piece : pieces) {
//			if (piece.getState() == Piece.PieceState.INCOMPLETE && pr.canGetPiece(piece.getIndex())) {
//				pieceRanks[piece.getIndex()] = 0;
//			}
//			else {
//				pieceRanks[piece.getIndex()] = -1;
//			}
//		}
//
//		for (Peer peer : peers.values()) {
//			for (Piece piece : pieces) {
//				if(peer.canGetPiece(piece.getIndex()) && pieceRanks[piece.getIndex()] != -1) {
//					pieceRanks[piece.getIndex()]++;
//				}
//			}
//		}
//
//		int leastPieceIndex = -1, leastPieceValue = -1;
//
//		for (int i = 0; i < pieceRanks.length; i++) {
//			if (leastPieceIndex == -1 && pieceRanks[i] != -1) {
//				leastPieceIndex = i;
//				leastPieceValue = pieceRanks[i];
//			}
//			else if (leastPieceValue != -1 && leastPieceValue > pieceRanks[i]) {
//				leastPieceIndex = i;
//				leastPieceValue = pieceRanks[i];
//			}
//		}
//
//		return pieces.get(leastPieceIndex);


		for (Piece pc : pieces) {
			if (pc.getState() == Piece.PieceState.INCOMPLETE && pr.canGetPiece(pc.getIndex())) {
				return pc;
			}
		}
		System.out.println("Choose failed...");
		return null;

	}

	/**
	 * Callback of sorts when peers die unexpectedly
	 *
	 * @param p
	 */
	/*
	TODO: Fix peerDying
	public void peerDying(Peer p) {
		System.out.println("Peer " + p + " died. Sadface.");
		synchronized (peerLock) {
			if (freePeers.contains(p)) {
				freePeers.remove(p);
			} else if (busyPeers.values().contains(p)) {
				for (Piece pc : busyPeers.keySet()) {
					if (busyPeers.get(pc) == p) {
						pc.clearSlices();
						pc.setState(Piece.PieceState.INCOMPLETE);
					}
				}
				busyPeers.values().remove(p);
			}
		}
	}
*/

	/**
	 * Write the piece data to the piece buffer
	 *
	 * @param piece A piece object representation to be added
	 * @return whether or not this piece validated.
	 */
	public boolean putPiece(Piece piece) {
		MessageDigest md;
		byte[] sha1 = null;
		try {
			md = MessageDigest.getInstance("SHA-1");
			sha1 = md.digest(piece.getByteBuffer().array());
		}
		catch(NoSuchAlgorithmException e) {
			e.printStackTrace();
		}

		synchronized (peerLock) {
			synchronized (fileLock) {
				if (Arrays.equals(sha1, piece.getHash())) {
					fileByteBuffer.position(piece.getIndex() * torrentInfo.piece_length);
					fileByteBuffer.put(piece.getByteBuffer());
					piece.setState(Piece.PieceState.COMPLETE);
					piecesHad.set(piece.getIndex());
					for (Peer p : peers.values()) {
						p.getPeerConnection().sendHave(piece.getIndex());
						BitSet tmp = ((BitSet)piecesHad.clone());
						tmp.flip(0, piecesHad.size());
						if (!tmp.intersects(p.getAvailablePieces())) {
							p.weHaveInterest = false;
							p.getPeerConnection().sendNotInterested();
						}
					}
					// Update stats
					downloaded += piece.getSize();
					left -= piece.getSize();
					System.out.println("DOWNLOADED: " + downloaded + " LEFT: " + left + " UPLOADED: " + uploaded);
				} else {
					System.out.println("Piece " + piece.getIndex() + " failed.");
					piece.clearSlices();
					piece.setState(Piece.PieceState.INCOMPLETE);
					return false;
				}
			}
		}
		if (piecesHad.nextClearBit(0) == pieces.size() && !sentComplete) {
			sentComplete = true;
			try {
				tracker.complete(peerId, port, uploaded, downloaded, left, encodedInfoHash);
			} catch (IOException e) {
				e.printStackTrace();
			} catch (BencodingException e) {
				e.printStackTrace();
			}
		}
		return true;
	}

	/**
	 * Calculates and creates an arraylist of pieces to be downloaded
	 * for a given torrent
	 *
	 * @return An arraylist of pieces
	 */
	private ArrayList<Piece> generatePieces() {
		ArrayList<Piece> al = new ArrayList<Piece>();
		int total = torrentInfo.file_length;
		for (int i = 0; i < torrentInfo.piece_hashes.length; ++i, total -= torrentInfo.piece_length) {
			al.add(new Piece(i, Math.min(total, torrentInfo.piece_length), torrentInfo.piece_hashes[i]));
		}
		this.piecesHad = new BitSet(al.size());
		return al;
	}

	/**
	 * Generates a peer id with prefix EWOK
	 *
	 * @return A peer id as a string
	 */
	private String generateId() {
		StringBuilder finalString = new StringBuilder(20).append("EWOK");
		Random rng = new Random(System.currentTimeMillis());
		for (int i = 0; i < 16; ++i) {
			finalString.append((char)(rng.nextInt(26) + 65));
		}
		return finalString.toString();
	}

	/**
	 * URL encodes the infohash byte array
	 *
	 * @param infoHashByteArray Byte array from torrent file
	 * @return The encoded infohash as a string
	 */
	private String encodeInfoHash(byte[] infoHashByteArray) {
		StringBuilder sb = new StringBuilder();
		for (byte b : infoHashByteArray) {
			sb.append(String.format("%%%02X", b));
		}
		return sb.toString();
	}

	/**
	 * Calculates a bitfield for the torrent's current state.
	 * @return A byte buffer containing the torrent's current bitfield.  This is suitable to be sent across the network
	 */
	public ByteBuffer getBitField() {
		synchronized (fileLock) {
			byte[] bf = new byte[(pieces.size() + 8 - 1) / 8]; // Ceiling(pieces.size() / 8)
			for (int i = 0; i < pieces.size(); ++i) {
				bf[i/8] |= (pieces.get(i).getState() == Piece.PieceState.COMPLETE) ? 0x80 >> (i % 8) : 0;
			}
			boolean fail = false;
			for (int i = 0; i < pieces.size()/8 && !fail; ++i) {
				fail = (bf[i] != 0);
			}
			if (fail)
				return ByteBuffer.wrap(bf);
			return null;
		}
	}


	public void recvMessage(PeerMessage message) {
		messages.add(message);
	}

}

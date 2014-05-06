import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.BitSet;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * A connection to a peer that is responsible for downloading
 * a piece of a piece. Is run in a new thread.
 *
 * @author eddiezane
 * @author wlangford
 * @author kaushal 
 */
public class Peer {

	/*
	 * These are public because there's no reason for them not to be.
	 * All that ever gets done is get and set on them and they're primitives.
	 */
	public boolean handshook = false;
	public boolean interested = false;
	public boolean choked = true;
	public boolean choking = true;
	public int outstandingRequests = 0;

	private ByteBuffer peerId;
	private BitSet availablePieces = new BitSet();

	public Peer(ByteBuffer peerId) {
		this.peerId = peerId.duplicate();
	}

	public void setAvailablePieces(BitSet availablePieces) {
		this.availablePieces = availablePieces;
	}

	public ByteBuffer getPeerId() {
		return peerId;
	}

	public BitSet getAvailablePieces() {
		return availablePieces;
	}

	public void setPieceAvailable(int index) {
		this.availablePieces.set(index);
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
			return false;
		}
	}
}


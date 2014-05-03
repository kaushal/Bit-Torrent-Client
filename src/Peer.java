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

	public Piece getCurrentPiece() {
		return currentPiece;
	}

	public int getPieceBegin() {
		return pieceBegin;
	}

	public int getPieceLength() {
		return pieceLength;
	}

	public void setAvailablePieces(BitSet availablePieces) {
		this.availablePieces = availablePieces;
	}

	public void configurePiece(Piece pc) {
		if (pc == null) {
			currentPiece = null;
			pieceBegin = 0;
			pieceLength = 0;
		} else {
			int slice = pc.getNextSlice();
			if (slice == -1) {
				pieceBegin = 0;
				pieceLength = 0;
				currentPiece = null;
				return;
			}
			currentPiece = pc;
			pieceBegin = slice * Piece.SLICE_SIZE;
			pieceLength = Math.min(Piece.SLICE_SIZE, pc.getSize() - (slice * Piece.SLICE_SIZE));
		}
	}

	public enum PeerState {
		BEGIN, HANDSHAKE, CHOKED, UNCHOKED, DOWNLOADING, INTERESTED
	}

	private ByteBuffer peerId;
	private PeerState state = PeerState.BEGIN;
	private BitSet availablePieces = new BitSet();
	private int pieceBegin;
	private int pieceLength;

	private Piece currentPiece = null;

	public Peer(ByteBuffer peerId) {
		this.peerId = peerId.duplicate();
	}

	public ByteBuffer getPeerId() {
		return peerId;
	}

	public PeerState getState() {
		return state;
	}

	public void setState(PeerState state) {
		this.state = state;
	}

	public BitSet getAvailablePieces() {
		return availablePieces;
	}

	public void setPieceAvailable(int index) {
		this.availablePieces.set(index);
	}

	/**
	 * Handles the piece of the torrent. Responsible for getting slices
	 * to download.
	 */
/*
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
				System.out.println(peerId + " Unchoke and interested");
			}
		}
	}
*/

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


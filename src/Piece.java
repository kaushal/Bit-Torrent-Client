import java.nio.ByteBuffer;
import java.util.BitSet;

/**
 * A representation of a piece which is owned by a torrent
 *
 * @author eddiezane
 * @author wlangford
 * @author kaushall
 */
public class Piece {
	public enum PieceState { INCOMPLETE, DOWNLOADING, COMPLETE};

	public static final int SLICE_SIZE = 2<<13;
	private int index;
	private int size;
	private byte[] hash;
	private Torrent owner;
	private BitSet slices;
	private byte[] data;
	private PieceState state = PieceState.INCOMPLETE;

    /**
     *
     * @param index
     * @param size
     * @param hash
     * @param ownerTorrent
     */

	public Piece(int index, int size, ByteBuffer hash, Torrent ownerTorrent) {
		this.hash = hash.array();
		this.owner = ownerTorrent;
		this.index = index;
		this.size = size;
		this.data = new byte[size];
		this.slices = new BitSet((size + (SLICE_SIZE) - 1)/(SLICE_SIZE)); // Ceiling(size/sliceSize)
		slices.clear();
	}

	public int getIndex() {
		return index;
	}

	public int getSize() {
		return size;
	}

	public byte[] getHash() {
		return hash;
	}

	public Torrent getOwner() {
		return owner;
	}

	public PieceState getState() {
		return state;
	}

	public void setState(PieceState st) {
		state = st;
	}

    /**
     *
     * @return
     */

	public ByteBuffer getByteBuffer() {
		return ByteBuffer.wrap(data);
	}

	public void putSlice(int idx) {
		slices.set(idx, true);
	}

	public void clearSlices() {
		this.slices.clear();
	}

	public int getNextSlice() {
		int slice = slices.nextClearBit(0);

		// If we've gotten all the pieces, return -1
		if (slice >= (size + (SLICE_SIZE) - 1)/(SLICE_SIZE)) // Ceiling(size/sliceSize)
			return -1;

		return slice;
	}
}

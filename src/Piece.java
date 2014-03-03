import java.nio.ByteBuffer;
import java.util.BitSet;

/**
 * A representation of a piece which is owned by a torrent
 *
 * @author eddiezane
 * @author wlangford
 */
public class Piece {
    private int index;
    private int size;
	private byte[] hash;
	private Torrent owner;
	private BitSet slices;
	private byte[] data;

	public Piece(int index, int size, ByteBuffer hash, Torrent ownerTorrent) {
		this.hash = hash.array();
		this.owner = ownerTorrent;
		this.index = index;
		this.size = size;
		this.data = new byte[size];
		this.slices = new BitSet((size + (2<<13) - 1)/(2<<13)); // Round up.
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

	public ByteBuffer getByteBuffer() {
		return ByteBuffer.wrap(data);
	}
	public void putSlice(int idx) {
		slices.set(idx, true);
	}

	public void clearSlices() {
		this.slices.clear();
		this.data = new byte[size];
	}
	public int getNextSlice() {
		int slice = slices.nextClearBit(0);
		if (slice >= (size + (2<<13) - 1)/(2<<13)) return -1; // Round up.
		return slice;
	}
}

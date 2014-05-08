import java.nio.ByteBuffer;
import java.util.BitSet;
import java.util.Timer;
import java.util.TimerTask;

/**
 * A representation of a piece which is owned by a torrent
 *
 * @author eddiezane
 * @author wlangford
 * @author kaushall
 */
public class Piece {
	public int getBeginOfSlice(int slice) {
		if (slice < 0 || slice >= maxSlices)
			return -1;
		return slice * SLICE_SIZE;
	}

	public int getLengthOfSlice(int slice) {
		if (slice < 0 || slice >= maxSlices)
			return -1;
		return Math.min(SLICE_SIZE,size - (slice * SLICE_SIZE));
	}

	public enum PieceState { INCOMPLETE, COMPLETE};

	public static final int SLICE_SIZE = 2<<13;
	private int index;
	private int size;
	private byte[] hash;
	private BitSet slices;
	private BitSet loadingSlices;
	private int maxSlices;
	private byte[] data;
	private PieceState state = PieceState.INCOMPLETE;
	private Timer loadingTimer;
	private TimerTask[] loadingTasks;

    /**
     *
     * @param index piece index
     * @param size size of piece
     * @param hash SHA hash of piece
     */

	public Piece(int index, int size, ByteBuffer hash) {
		System.out.println(index + ":" + size);
		this.hash = hash.array();
		this.index = index;
		this.size = size;
		this.data = new byte[size];
		this.maxSlices = (size + (SLICE_SIZE) - 1)/(SLICE_SIZE); // Ceiling(size/sliceSize)
		this.slices = new BitSet(maxSlices);
		this.loadingSlices = new BitSet(maxSlices);
		this.loadingTimer = new Timer("Piece " + this.index + " timer", true);
		this.loadingTasks = new TimerTask[this.maxSlices];
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

	public PieceState getState() {
		return state;
	}

	public void setData(ByteBuffer bb) {
		bb.get(data);
	}

	public void setState(PieceState st) {
		state = st;
	}

	public ByteBuffer getByteBuffer() {
		return ByteBuffer.wrap(data);
	}

	public void putSlice(int idx) {
		slices.set(idx, true);
		loadingSlices.set(idx, false);
		loadingTasks[idx].cancel();
	}

	public void clearSlices() {
		this.slices.clear();
		this.loadingSlices.clear();
	}

	public int getNextSlice() {
		return getNextSlice(false);
	}

	public int getNextSlice(boolean repeats) {
		int slice = slices.nextClearBit(0);
		if (!repeats) {
			while (loadingSlices.get(slice)) {
				slice = slices.nextClearBit(slice+1);
			}
		}
		// If we've gotten all the pieces, return -1
		if (slice >= maxSlices)
			return -1;

		loadingSlices.set(slice);
		final int sl2 = slice;
		loadingTasks[slice] = new TimerTask() {
			@Override
			public void run() {
				loadingSlices.clear(sl2);
			}
		};
		loadingTimer.schedule(loadingTasks[slice],30000);
		return slice;
	}
	public boolean isLoadingSlices() {
		return !loadingSlices.isEmpty();
	}
}

import java.nio.ByteBuffer;

/**
 * A representation of a piece which is owned by a torrent
 *
 * @author eddiezane
 * @author wlangford
 */
public class Piece {
    private int index;
    private int size;
	private String hash;
	private Torrent owner;

	public int getIndex() {
		return index;
	}

	public int getSize() {
		return size;
	}

	public String getHash() {
		return hash;
	}

	public Torrent getOwner() {
		return owner;
	}

	public Piece(int index, int size, ByteBuffer hash, Torrent ownerTorrent) {
	    this.hash = new String(hash.array());
	    this.owner = ownerTorrent;
	    this.index = index;
	    this.size = size;
    }
}

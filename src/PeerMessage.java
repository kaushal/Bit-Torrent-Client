import java.nio.ByteBuffer;
import java.util.BitSet;
import java.util.HashMap;

/**
 * Created by wlangford on 4/28/14.
 */
public class PeerMessage implements Comparable<PeerMessage> {

	@Override
	public int compareTo(PeerMessage peerMessage) {
		return peerId.compareTo(peerMessage.peerId);
	}



	public enum PeerMessageType {
		Handshake,
		Choke,
		Unchoke,
		Interested,
		NotInterested,
		Have,
		Bitfield,
		Request,
		Piece,
		Cancel,
	}

	private ByteBuffer peerId;
	private PeerMessageType type;
	private HashMap<String,Object> data;

	public static PeerMessage Handshake(ByteBuffer peerId, ByteBuffer msg) {
		PeerMessage m = new PeerMessage(peerId);
		m.type = PeerMessageType.Handshake;
		m.data.put("bytes",msg);
		return m;
	}

	public static PeerMessage Choke(ByteBuffer ip) {
		PeerMessage m = new PeerMessage(ip);
		m.type = PeerMessageType.Choke;
		return m;
	}

	public static PeerMessage Unchoke(ByteBuffer ip) {
		PeerMessage m = new PeerMessage(ip);
		m.type = PeerMessageType.Unchoke;
		return m;
	}

	public static PeerMessage Interested(ByteBuffer ip) {
		PeerMessage m = new PeerMessage(ip);
		m.type = PeerMessageType.Interested;
		return m;
	}

	public static PeerMessage NotInterested(ByteBuffer ip) {
		PeerMessage m = new PeerMessage(ip);
		m.type = PeerMessageType.NotInterested;
		return m;
	}

	public static PeerMessage Have(ByteBuffer ip,int index) {
		PeerMessage m = new PeerMessage(ip);
		m.type = PeerMessageType.Have;
		m.data.put("index",index);
		return m;
	}

	public static PeerMessage Bitfield(ByteBuffer ip, BitSet bitfield) {
		PeerMessage m = new PeerMessage(ip);
		m.type = PeerMessageType.Bitfield;
		m.data.put("bitfield",bitfield);
		return m;
	}

	public static PeerMessage Request(ByteBuffer ip,int index, int begin, int length) {
		PeerMessage m = new PeerMessage(ip);
		m.type = PeerMessageType.Request;
		m.data.put("index",index);
		m.data.put("begin",begin);
		m.data.put("length",length);
		return m;
	}

	public static PeerMessage Piece(ByteBuffer ip, int index, int begin, ByteBuffer bytes) {
		PeerMessage m = new PeerMessage(ip);
		m.type = PeerMessageType.Piece;
		m.data.put("index",index);
		m.data.put("begin",begin);
		m.data.put("bytes",bytes);
		return m;
	}

	public static PeerMessage Cancel(ByteBuffer ip, int index, int begin, int length) {
		PeerMessage m = new PeerMessage(ip);
		m.type = PeerMessageType.Cancel;
		m.data.put("index",index);
		m.data.put("begin",begin);
		m.data.put("length",length);
		return m;
	}

	public ByteBuffer getPeerId() {
		return peerId;
	}

	public PeerMessageType getType() {
		return type;
	}

	public int getIndex() {
		if (type == PeerMessageType.Have || type == PeerMessageType.Request ||
			type == PeerMessageType.Piece || type == PeerMessageType.Cancel)
			return (Integer) data.get("index");
		return -1;
	}

	public long getBegin() {
		if (type == PeerMessageType.Request || type == PeerMessageType.Piece ||
			type == PeerMessageType.Cancel)
			return (Integer) data.get("begin");
		return -1;
	}

	public long getLength() {
		if (type == PeerMessageType.Request || type == PeerMessageType.Cancel)
			return (Integer) data.get("length");
		return -1;
	}

	public BitSet getBitfield() {
		if (type == PeerMessageType.Bitfield)
			return (BitSet)data.get("bitfield");
		return null;
	}

	public ByteBuffer getBytes() {
		if (type == PeerMessageType.Piece || type == PeerMessageType.Handshake)
			return (ByteBuffer)data.get("bytes");
		return null;
	}

	private PeerMessage(ByteBuffer peerId) {
		this.peerId = peerId;
		data = new HashMap<String, Object>();

	}
}

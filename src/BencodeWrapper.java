import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;

import edu.rutgers.cs.cs352.bt.exceptions.BencodingException;
import edu.rutgers.cs.cs352.bt.util.Bencoder2;

/**
 * Wrapper around bencode2 class does most of the type casting for us.
 * 
 * @author wlangford
 */

public class BencodeWrapper {
	@SuppressWarnings("unchecked")
	public static Object decode(byte[] input) throws BencodingException {
		Object res = Bencoder2.decode(input);
		if (res instanceof ByteBuffer)
			return fixStupidByteBuffers((ByteBuffer)res);
		else if (res instanceof HashMap)
			return fixStupidHashMaps((HashMap<ByteBuffer,Object>)res);
		else if (res instanceof ArrayList)
			return fixStupidArrayLists((ArrayList<Object>)res);
		else
			return res;
	}
	@SuppressWarnings("unchecked")
	private static String fixStupidByteBuffers(ByteBuffer inbb) {
		return new String(inbb.array());
	}
	@SuppressWarnings("unchecked")
	private static HashMap<String,Object> fixStupidHashMaps(HashMap<ByteBuffer,Object>inhm) {
		HashMap<String, Object> outhm = new HashMap<String, Object>();
		for (ByteBuffer k : inhm.keySet()) {
			Object v = inhm.get(k);
			if (v instanceof ByteBuffer)
				outhm.put(fixStupidByteBuffers(k),fixStupidByteBuffers((ByteBuffer)v));
			else if (v instanceof HashMap)
				outhm.put(fixStupidByteBuffers(k),fixStupidHashMaps((HashMap<ByteBuffer,Object>)v));
			else if (v instanceof ArrayList) {
				outhm.put(fixStupidByteBuffers(k),fixStupidArrayLists((ArrayList<Object>)v));
			} else
				outhm.put(fixStupidByteBuffers(k), inhm.get(k));
		}
		return outhm;
	}
	@SuppressWarnings("unchecked")
	private static ArrayList<Object> fixStupidArrayLists(ArrayList<Object>inal) {
		ArrayList<Object> outal = new ArrayList<Object>();
		for (Object o: inal) {
			if (o instanceof ByteBuffer) {
				outal.add(fixStupidByteBuffers((ByteBuffer)o));
			} else if (o instanceof ArrayList) {
				outal.add(fixStupidArrayLists((ArrayList<Object>)o));
			} else if (o instanceof HashMap) {
				outal.add(fixStupidHashMaps((HashMap<ByteBuffer, Object>) o));
			} else
				outal.add(o);
		}
		return outal;
	}
}

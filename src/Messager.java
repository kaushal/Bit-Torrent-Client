import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Created by wlangford on 4/5/14.
 */
abstract public class Messager {
	ConcurrentLinkedQueue<ByteBuffer> inMessages = new ConcurrentLinkedQueue<ByteBuffer>();
	ConcurrentLinkedQueue<ByteBuffer> outMessages = new ConcurrentLinkedQueue<ByteBuffer>();

	public void recv(ByteBuffer msg)  {
		inMessages.add(msg);
	}
	public void send(ByteBuffer msg) {
		outMessages.add(msg);
	}

}

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;
import java.util.Set;

/**
 * Created by wlangford on 4/5/14.
 */
public class NetworkOperator implements Runnable {
	private static NetworkOperator sharedOperator = null;
	private Selector selector;
	private boolean loop = true;

	static public NetworkOperator getSharedOperator() {
		return sharedOperator != null ? sharedOperator : (sharedOperator = new NetworkOperator());
	}
	protected NetworkOperator () {
		try {
			selector = Selector.open();
		} catch (IOException e) {
			e.printStackTrace();
		}
		(new Thread(this)).start();
	}

	public boolean register (SelectableChannel chan, NetworkClient cli) {
		try {
			System.out.println(chan.validOps());
			chan.register(selector,chan.validOps(),cli);
			return true;
		} catch (ClosedChannelException e) {
			return false;
		}
	}

	@Override
	public void run() {
		while (loop) {
			try {
				if (selector.select(100) == 0) continue;
			} catch (IOException e) {
				e.printStackTrace();
			}
			Set<SelectionKey> selectedKeys = selector.selectedKeys();
			Iterator<SelectionKey> keyIterator = selectedKeys.iterator();
			while (keyIterator.hasNext()) {
				SelectionKey key = keyIterator.next();
				keyIterator.remove();
				NetworkClient cli = (NetworkClient) key.attachment();
				try {
					if (!key.isValid()) continue;
					if (key.isConnectable()) cli.connectable();
					if (key.isAcceptable()) cli.acceptable();
					if (key.isReadable()) cli.readable();
					if (key.isWritable()) cli.writable();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
		try {
			selector.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public void stop() {
		loop = false;
	}
}

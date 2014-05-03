import edu.rutgers.cs.cs352.bt.exceptions.BencodingException;
import edu.rutgers.cs.cs352.bt.util.Bencoder2;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.HashMap;

/**
 * Created by wlangford on 4/28/14.
 */
public class TrackerConnection {


	public static final ByteBuffer INCOMPLETE = ByteBuffer.wrap(new byte[] {'i','n','c','o','m','p','l','e','t','e'});
	public static final ByteBuffer PEERS = ByteBuffer.wrap(new byte[] {'p','e','e','r','s'});
	public static final ByteBuffer DOWNLOADED = ByteBuffer.wrap(new byte[] {'d','o','w','n','l','o','a','d','e','d'});
	public static final ByteBuffer COMPLETE = ByteBuffer.wrap(new byte[] {'c','o','m','p','l','e','t','e'});
	public static final ByteBuffer MIN_INTERVAL = ByteBuffer.wrap(new byte[] {'m','i','n',' ','i','n','t','e','r','v','a','l'});
	public static final ByteBuffer INTERVAL = ByteBuffer.wrap(new byte[] {'i','n','t','e','r','v','a','l'});

	public static final ByteBuffer PEER_IP = ByteBuffer.wrap(new byte[] {'i','p'});
	public static final ByteBuffer PEER_ID = ByteBuffer.wrap(new byte[] {'p','e','e','r',' ','i','d'});
	public static final ByteBuffer PEER_PORT = ByteBuffer.wrap(new byte[] {'p','o','r','t'});

	private URL trackerURL;

	public TrackerConnection(URL trackerURL) {
		this.trackerURL = trackerURL;
	}

	/**
	 * Send Started announce to tracker
	 * @param peerId Our peerId
	 * @param port Port on which we are listening.
	 * @param uploaded Amount we have uploaded
	 * @param downloaded Amount we have downloaded
	 * @param left Amount we have left to download
	 * @param infoHash Info hash of the torrent we want.
	 * @return Tracker response dictionary.
	 * @throws IOException
	 * @throws BencodingException
	 */
	public HashMap<ByteBuffer,Object> start(String peerId, int port, int uploaded, int downloaded, int left, String infoHash) throws IOException, BencodingException {
		return announce("started",peerId,port,uploaded,downloaded,left,infoHash);
	}

	/**
	 * Send Stopped announce to tracker
	 * @param peerId Our peerId
	 * @param port Port on which we are listening.
	 * @param uploaded Amount we have uploaded
	 * @param downloaded Amount we have downloaded
	 * @param left Amount we have left to download
	 * @param infoHash Info hash of the torrent we want.
	 * @return Tracker response dictionary.
	 * @throws IOException
	 * @throws BencodingException
	 */
	public HashMap<ByteBuffer,Object> stop(String peerId, int port, int uploaded, int downloaded, int left, String infoHash) throws IOException, BencodingException {
		return announce("stopped",peerId,port,uploaded,downloaded,left,infoHash);
	}

	/**
	 * Send Completed announce to tracker
	 * @param peerId Our peerId
	 * @param port Port on which we are listening.
	 * @param uploaded Amount we have uploaded
	 * @param downloaded Amount we have downloaded
	 * @param left Amount we have left to download
	 * @param infoHash Info hash of the torrent we want.
	 * @return Tracker response dictionary.
	 * @throws IOException
	 * @throws BencodingException
	 */
	public HashMap<ByteBuffer,Object> complete(String peerId, int port, int uploaded, int downloaded, int left, String infoHash) throws IOException, BencodingException {
		return announce("completed",peerId,port,uploaded,downloaded,left,infoHash);
	}

	/**
	 * Send regular announce to tracker
	 * @param peerId Our peerId
	 * @param port Port on which we are listening.
	 * @param uploaded Amount we have uploaded
	 * @param downloaded Amount we have downloaded
	 * @param left Amount we have left to download
	 * @param infoHash Info hash of the torrent we want.
	 * @return Tracker response dictionary.
	 * @throws IOException
	 * @throws BencodingException
	 */
	public HashMap<ByteBuffer,Object> announce(String peerId, int port, int uploaded, int downloaded, int left, String infoHash) throws IOException, BencodingException {
		return announce(null,peerId,port,uploaded,downloaded,left,infoHash);
	}

	@SuppressWarnings("unchecked")
	private HashMap<ByteBuffer,Object> announce(String event, String peerId, int port, int uploaded, int downloaded, int left, String infoHash) throws IOException, BencodingException {
		URL url = new URL(this.trackerURL.toString() +
				"?info_hash=" + infoHash +
				"&peer_id=" + peerId +
				"&port=" + port +
				"&uploaded=" + uploaded +
				"&downloaded=" + downloaded +
				"&left=" + left +
				"&event=" + (event != null ? event : "")
		);
		HttpURLConnection con = (HttpURLConnection) url.openConnection();
		DataInputStream dis = new DataInputStream(con.getInputStream());
		ByteArrayOutputStream baos = new ByteArrayOutputStream(); // Like a baos

		int reads;
		while ((reads = dis.read()) != -1) {
			baos.write(reads);
		}
		dis.close();
		HashMap<ByteBuffer, Object> res = (HashMap<ByteBuffer, Object>) Bencoder2.decode(baos.toByteArray());
		baos.close();
		return res;
	}

}

import java.io.BufferedReader;
import java.util.HashMap;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

import edu.rutgers.cs.cs352.bt.TorrentInfo;
import edu.rutgers.cs.cs352.bt.util.Bencoder2;
import edu.rutgers.cs.cs352.bt.util.ToolKit;
import edu.rutgers.cs.cs352.bt.exceptions.BencodingException;

public class RUBTClient {
	public static void main(String[] args) throws IOException, BencodingException {

		if (args.length != 2) {
			System.out.println("incorrect number of command line arguments");
			return;
		}

		/*
		 * Check if we can open the torrent file
		 */
		File tf = new File(args[0]);
		if (!tf.canRead()) {
			System.out.println("Can't read torrent file");
		}

		/*
		 * Read the torrent file into a byte array and create
		 * a TorrentInfo object with it
		 */
		byte[] byteFile = new byte[(int) tf.length()];
		DataInputStream file = new DataInputStream(new FileInputStream(tf));
		file.readFully(byteFile);
		file.close();
		TorrentInfo ti = new TorrentInfo(byteFile);


		/*
		 * URL Encode the infoHash
		 */
		byte[] infoHash = ti.info_hash.array();
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < infoHash.length; i++) {
			sb.append(String.format("%%%02X", infoHash[i]));
		}
		
		/*
		 * TODO: Pull this out to tracker.java
		 */
		URL url = new URL(ti.announce_url.toString() + "?info_hash=" + sb + "&peer_id=" + "EKW45678901234567890" + "&port=6881&uploaded=0&downloaded=0&left=1024");
		HttpURLConnection con = (HttpURLConnection) url.openConnection();
		int responseCode = con.getResponseCode();
		System.out.println("\nSending 'GET' request to URL : " + url);
		System.out.println("Response Code : " + responseCode);
 
		BufferedReader is = new BufferedReader(new InputStreamReader(con.getInputStream()));

		ByteArrayOutputStream baos = new ByteArrayOutputStream(); // Like a baos
		int reads = is.read();
		while (reads != -1) {
			baos.write(reads);
			reads = is.read();
		}
		is.close();
		Object res = Bencoder2.decode(baos.toByteArray());
		ToolKit.print(res);

 
		// Client client = new Client(args[1], ti);
		// client.download();

	}

	/**
	 * Converts a byte array to a hex string
	 * 
	 * @param ba a byte array
	 * @return the byte array converted to a hex string
	 */
	public static String byteArrayToHexString(byte[] ba) {
		StringBuilder sb = new StringBuilder();
		for (byte i : ba) {
			sb.append(Integer.toHexString((i >> 4) & 0xf));
			sb.append(Integer.toHexString(i & 0xf));
		}
		return sb.toString();
	}
}

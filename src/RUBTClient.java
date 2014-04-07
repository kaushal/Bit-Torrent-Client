import edu.rutgers.cs.cs352.bt.TorrentInfo;

import edu.rutgers.cs.cs352.bt.exceptions.BencodingException;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * Start of the program contains main method and spawns a new torrent object. 
 * is responsible for parsing information out of torrent file and storing result in ti
 *
 * @author eddiezane
 * @author wlangford
 * @author kaushal
 */
public class RUBTClient {

	@SuppressWarnings("unchecked")
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
		Torrent tt = new Torrent(ti, args[1]);
		(new Thread(tt)).start();
	}
}

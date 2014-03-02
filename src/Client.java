import java.io.FileNotFoundException;
import java.io.RandomAccessFile;
import java.net.URLEncoder;
import java.util.Random;

import edu.rutgers.cs.cs352.bt.TorrentInfo;


public class Client {
	private String name; 

	private TorrentInfo ti;

	public Client(String name, TorrentInfo ti) {
		this.name = name;
		this.ti = ti;
	}




	public void download() throws FileNotFoundException {
		RandomAccessFile file = new RandomAccessFile(this.name, "rw");
		StringBuilder sb = new StringBuilder(60);
		byte[] infoHash = ti.info_hash.array();


		System.out.println(ti.info_hash.array());

	}

}

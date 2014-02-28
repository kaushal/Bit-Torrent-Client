import java.io.FileNotFoundException;
import java.io.RandomAccessFile;
import java.net.URLEncoder;
import java.util.Random;

import edu.rutgers.cs.cs352.bt.TorrentInfo;


public class Client {
	private String name; 

	private TorrentInfo ti;

	public Client(String name, TorrentInfo ti){
		this.name = name;
		this.ti = ti;
	}

	private String generateID(){
		String finalString = "";
		Random rg = new Random(System.currentTimeMillis() );
		for(int i = 0; i < 20; i++){
			finalString = finalString + (char)(rg.nextInt(26) + 65);
		}
		if(finalString.contains("RUBT")){
			return generateID();
		}
		else
			return finalString;
	}


	public void download() throws FileNotFoundException{

		RandomAccessFile file = new RandomAccessFile(this.name, "rw");
		String peerId = generateID();
		StringBuilder sb = new StringBuilder(60);
		byte[] infoHash = ti.info_hash.array();


		System.out.println(ti.info_hash.array());

	}

}

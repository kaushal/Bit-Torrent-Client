import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

import edu.rutgers.cs.cs352.bt.TorrentInfo;
import edu.rutgers.cs.cs352.bt.exceptions.BencodingException;



public class RUBTClient {
	public static void main(String[] args) throws IOException, BencodingException{
		System.out.println(args.length);
		if(args.length != 2){
			System.out.println("incorrect number of command line arguments");
			return;
		}
		
		File tf = new File(args[0]);
		if(!tf.canRead()){
			System.out.println("Can't read torrent file");
		}
		
		byte[] byteFile = new byte[(int) tf.length()];
		DataInputStream file = new DataInputStream(new FileInputStream(tf));          
		file.readFully(byteFile);
		file.close();
		
		TorrentInfo ti = new TorrentInfo(byteFile);
		
		
		System.out.println(ti.file_length);
		
		
	}
}

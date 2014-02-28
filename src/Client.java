import java.io.FileNotFoundException;
import java.io.RandomAccessFile;

import edu.rutgers.cs.cs352.bt.TorrentInfo;


public class Client {
	private String name; 
	
	public Client(String name, TorrentInfo ti){
		this.name = name;
		
	}
	
	private void generateID(){
		for(int i = 0; i < 20; i++){
			
		}
	}
	
	public void download() throws FileNotFoundException{
		
		RandomAccessFile file = new RandomAccessFile(this.name, "rw");
		
		
	}
	
}

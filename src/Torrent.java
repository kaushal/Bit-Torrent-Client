import edu.rutgers.cs.cs352.bt.TorrentInfo;
import edu.rutgers.cs.cs352.bt.exceptions.BencodingException;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ServerSocketChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Random;

public class Torrent implements Runnable {

    private TorrentInfo torrentInfo;
    private ArrayList<Piece> pieces;
    private String encodedInfoHash;
	private RandomAccessFile dataFile;
	private MappedByteBuffer fileByteBuffer;
	private HashMap<String,Object> infoMap;
	private String peerId;
	private ArrayList<Peer> peers = new ArrayList<Peer>();
	private ServerSocketChannel socketChannel;

	private int port = 6881;
	private int uploaded = 0;
	private int downloaded = 0;
	private int left = 1024;

    public Torrent(TorrentInfo ti) {
        this.torrentInfo = ti;
        this.encodedInfoHash = encodeInfoHash(this.torrentInfo.info_hash.array());
	    this.peerId = generateId();

    }


    @Override
    public void run() {
	    try {
		    ArrayList<HashMap<String,Object>> tmp_peers = getPeers();
		    for (HashMap<String,Object> p : tmp_peers) {
			    peers.add(new Peer(p,this.torrentInfo.info_hash,ByteBuffer.wrap(this.peerId.getBytes())));
		    }
		    for (Peer p : peers) {
			    (new Thread(p)).start();
		    }
		    dataFile = new RandomAccessFile("/Users/wlangford/Desktop/blerghfile","rw");
		    fileByteBuffer = dataFile.getChannel().map(FileChannel.MapMode.READ_WRITE,0, (Integer)torrentInfo.info_map.get(TorrentInfo.KEY_LENGTH));

	    } catch (FileNotFoundException e) {
		    e.printStackTrace();
	    } catch (IOException e) {
		    e.printStackTrace();
	    } catch (BencodingException e) {
		    e.printStackTrace();
	    }
    }


	public void putPiece(ByteBuffer pieceData, int piece) {
		fileByteBuffer.position(piece* (Integer)torrentInfo.info_map.get(TorrentInfo.KEY_PIECE_LENGTH));
		fileByteBuffer.put(pieceData);
	}


	private String generateId() {
		StringBuilder finalString = new StringBuilder(20).append("EWOK");
		Random rng = new Random(System.currentTimeMillis());
		for (int i = 0; i < 16; ++i) {
			finalString.append((char)(rng.nextInt(26) + 65));
		}
		return finalString.toString();
	}


    /**
     * Talks to the tracker and gets peers
     *
     * @return ArrayList of peers from tracker
     */
    public ArrayList<HashMap<String,Object>> getPeers() throws IOException, BencodingException {
		/*
		 * URL Encode the infoHash
		 */

	    //TODO: Actually handle port, uploaded, downloaded, left

        URL url = new URL(this.torrentInfo.announce_url.toString() +
		        "?info_hash=" + this.encodedInfoHash +
		        "&peer_id=" + peerId +
		        "&port="+port+
		        "&uploaded="+uploaded+
		        "&downloaded="+downloaded+
		        "&left="+left);
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
        HashMap<String,Object> res = (HashMap<String,Object>)BencodeWrapper.decode(baos.toByteArray());
    }

    /**
     * URL encodes the infohash byte array
     *
     * @param infoHashByteArray Byte array from torrent file
     * @return The encoded infohash as a string
     */
    private String encodeInfoHash(byte[] infoHashByteArray) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < infoHashByteArray.length; i++) {
            sb.append(String.format("%%%02X", infoHashByteArray[i]));
        }
        return sb.toString();
    }
}

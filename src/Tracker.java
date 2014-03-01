import edu.rutgers.cs.cs352.bt.TorrentInfo;
import edu.rutgers.cs.cs352.bt.exceptions.BencodingException;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;


public class Tracker {
    private TorrentInfo ti;
    private String encodedInfoHash;

    public Tracker(TorrentInfo ti) {
        this.ti = ti;
        this.encodedInfoHash = encodeInfoHash(ti.info_hash.array());
    }

    /**
     * Talks to the tracker and gets peers
     *
     * @return ArrayList of peers from tracker
     */
    public Object getPeers() throws IOException, BencodingException {
		/*
		 * URL Encode the infoHash
		 */

        URL url = new URL(this.ti.announce_url.toString() + "?info_hash=" + this.encodedInfoHash + "&peer_id=" + "EKW45678901234567890" + "&port=6881&uploaded=0&downloaded=0&left=1024");
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

        return res.get("peers");
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

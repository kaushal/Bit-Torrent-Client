import edu.rutgers.cs.cs352.bt.TorrentInfo;
import edu.rutgers.cs.cs352.bt.exceptions.BencodingException;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Random;

public class Torrent implements Runnable {

    private TorrentInfo torrentInfo;
    private ArrayList<Piece> pieces;
	private ArrayList<Piece> busyPieces = new ArrayList<Piece>();
    private String encodedInfoHash;
	private RandomAccessFile dataFile;
	private MappedByteBuffer fileByteBuffer;
	private HashMap<String,Object> infoMap;
	private String peerId;
	private ArrayList<Peer> freePeers = new ArrayList<Peer>();
	private HashMap<Piece,Peer> busyPeers = new HashMap<Piece, Peer>();

	private final Object runLock = new Object();
	private boolean running = true;

	private int port = 6881;
	private int uploaded = 0;
	private int downloaded = 0;
	private int left = 0;
	private String fileName;

    public Torrent(TorrentInfo ti, String fileName) {
        this.torrentInfo = ti;
        this.fileName = fileName;
        this.encodedInfoHash = encodeInfoHash(this.torrentInfo.info_hash.array());
	    this.peerId = generateId();
	    this.pieces = generatePieces();
	    this.left = ti.file_length;
    }

	public void stop() {
		synchronized (runLock) {
			running = false;
		}
	}

    @Override
    public void run() {
	    try {
		    ArrayList<HashMap<String,Object>> tmp_peers = getPeers();
		    for (HashMap<String,Object> p : tmp_peers) {
			    if (((String)p.get("peer id")).startsWith("RUBT") && p.get("ip").equals("128.6.171.130")) {
				    Peer pr = new Peer(p,this,this.torrentInfo.info_hash,ByteBuffer.wrap(this.peerId.getBytes()));
				    freePeers.add(pr);
			        (new Thread(pr)).start();
			    }
		    }
		    dataFile = new RandomAccessFile(this.fileName,"rw");
		    fileByteBuffer = dataFile.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, (Integer)torrentInfo.info_map.get(TorrentInfo.KEY_LENGTH));

		    while (true) {
			    synchronized (runLock) {
				    if (!running) {
					    break;
				    }
			    }
			    synchronized (fileLock) {
				  if (busyPieces.size() == 0 && pieces.size() == 0)
					  break;
			    }
			    ArrayList<Peer> tmpPeers = new ArrayList<Peer>();
			    synchronized (freePeers) {
				    for (Peer p : freePeers) {
					    Piece piece = null;
					    for (Piece pc: pieces) {
						    if (p.canGetPiece(pc.getIndex())) {
							    piece = pc;
							    break;
						    }
					    }
					    if (piece == null)
						    continue;
					    synchronized (fileLock) {
						    pieces.remove(piece);
						    busyPieces.add(piece);
						    p.getPiece(piece);
						    tmpPeers.add(p);
						    busyPeers.put(piece, p);
					    }
				    }
				    for (Peer p : tmpPeers) {
					    freePeers.remove(p);
				    }
			    }


		    }


	    } catch (FileNotFoundException e) {
		    e.printStackTrace();
	    } catch (IOException e) {
		    e.printStackTrace();
	    } catch (BencodingException e) {
		    e.printStackTrace();
	    } finally {
            synchronized (fileLock) {
                try {
                    dataFile.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                fileByteBuffer = null;
            }
            for (Peer p : freePeers) {
                p.stop();
            }
            for (Peer p : busyPeers.values()) {
                p.stop();
            }
        }
    }


	public void peerDying(Peer p) {
		System.out.println("Peer "+p+" died. Sadface.");
		synchronized (freePeers) {
			if (freePeers.contains(p)) {
				freePeers.remove(p);
			} else if (busyPeers.values().contains(p)) {
				busyPeers.values().remove(p);
			}
		}
	}
	private final Object fileLock = new Object();
	public void putPiece(ByteBuffer pieceData, Piece piece) {
		synchronized (fileLock) {
			fileByteBuffer.position(piece.getIndex() * torrentInfo.piece_length);
			fileByteBuffer.put(pieceData);
        }
        synchronized (freePeers) {
            freePeers.add(busyPeers.get(piece));
            busyPeers.remove(piece);
            busyPieces.remove(piece);
        }
	}



	private ArrayList<Piece> generatePieces() {
		ArrayList<Piece> al = new ArrayList<Piece>();
		int total = torrentInfo.file_length;
		for (int i = 0; i < torrentInfo.piece_hashes.length; ++i, total -= torrentInfo.piece_length) {
			al.add(new Piece(i, Math.min(total, torrentInfo.piece_length), torrentInfo.piece_hashes[i], this));
		}
		return al;
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
    @SuppressWarnings("unchecked")
    public ArrayList<HashMap<String,Object>> getPeers() throws IOException, BencodingException {
		/*
		 * URL Encode the infoHash
		 */
        URL url = new URL(this.torrentInfo.announce_url.toString() +
		        "?info_hash=" + this.encodedInfoHash +
		        "&peer_id=" + peerId +
		        "&port="+port+
		        "&uploaded="+uploaded+
		        "&downloaded="+downloaded+
		        "&left="+left);
        HttpURLConnection con = (HttpURLConnection) url.openConnection();

        BufferedReader is = new BufferedReader(new InputStreamReader(con.getInputStream()));

        ByteArrayOutputStream baos = new ByteArrayOutputStream(); // Like a baos
        int reads = is.read();
        while (reads != -1) {
            baos.write(reads);
            reads = is.read();
        }
        is.close();
        HashMap<String,Object> res = (HashMap<String,Object>)BencodeWrapper.decode(baos.toByteArray());
	    return (ArrayList<HashMap<String,Object>>)res.get("peers");
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

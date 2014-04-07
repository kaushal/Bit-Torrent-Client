import edu.rutgers.cs.cs352.bt.TorrentInfo;
import edu.rutgers.cs.cs352.bt.exceptions.BencodingException;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Represents a torrent object responsible for talking to peers
 * and downloading its own pieces in threads
 *
 * @author eddiezane
 * @author wlangford
 * @author kaushal
 */
public class Torrent implements Runnable {

	private TorrentInfo torrentInfo;
	private ArrayList<Piece> pieces;
	private String encodedInfoHash;
	private RandomAccessFile dataFile;
	private MappedByteBuffer fileByteBuffer;
	private HashMap<String,Object> infoMap;
	private String peerId;
	private ArrayList<Peer> freePeers = new ArrayList<Peer>();
	private HashMap<Piece,Peer> busyPeers = new HashMap<Piece, Peer>();

	private final Object fileLock = new Object();
	private final Object runLock = new Object();
	private final Object peerLock = new Object();
	private boolean running = true;

	private int port = 6881;

    private int uploaded = 0;
	private int downloaded = 0;
	private int left = 0;
    private int minInterval = 0;
    private long lastAnnounce = 0;
	private String fileName;
	private boolean sentComplete;

	public Torrent(TorrentInfo ti, String fileName) {
		this.torrentInfo = ti;
		this.fileName = fileName;
		this.encodedInfoHash = encodeInfoHash(this.torrentInfo.info_hash.array());
		this.peerId = generateId();
		this.pieces = generatePieces();
		this.left = ti.file_length;
		try {
			dataFile = new RandomAccessFile(this.fileName,"rw");
			fileByteBuffer = dataFile.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, (Integer)torrentInfo.info_map.get(TorrentInfo.KEY_LENGTH));
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		verify();
		getBitField();
	}

	/**
	 * Verifies the file and updates what pieces we have.
	 */
	private void verify() {
		int offset = 0;
		MessageDigest md = null;
		byte[] sha1 = null;
		try {
			for (Piece pc : pieces) {
				md = MessageDigest.getInstance("SHA-1");
				ByteBuffer bb = ByteBuffer.allocate(pc.getSize());
				bb.put((ByteBuffer) fileByteBuffer.duplicate().position(offset).limit(offset + pc.getSize())).flip();
				offset += pc.getSize();
				sha1 = md.digest(bb.array());
				if (Arrays.equals(sha1,pc.getHash())) {
                    left -= pc.getSize();
					pc.setData(bb);
					pc.setState(Piece.PieceState.COMPLETE);
				}
			}
		}
		catch(NoSuchAlgorithmException e) {
			e.printStackTrace();
		}
	}
	/**
	 * Used by the RUBTClient to stop the torrent run loop
	 */
	public void stop() {
		synchronized (runLock) {
			running = false;
		}
	}

    /**
     *
     * Main runnable for the thread.
     * First finds list of peers with supplied ip addressed.
     * Then enters run loop and ends once we get all the pieces
     *
     */
	@Override
	public void run() {
		try {
			ArrayList<HashMap<String,Object>> tmp_peers = getPeers();
			for (HashMap<String,Object> p : tmp_peers) {
				if (p.get("ip").equals("128.6.171.130") || p.get("ip").equals("128.6.171.131")) {
					Peer pr = new Peer(p, this, this.torrentInfo.info_hash, ByteBuffer.wrap(this.peerId.getBytes()));
					freePeers.add(pr);
				}
			}
			for (Peer pr : freePeers)
				(new Thread(pr)).start();

            sendStartedEvent();
            lastAnnounce = System.currentTimeMillis();

			while (true) {
                if ((System.currentTimeMillis() - lastAnnounce) >= (minInterval - 5000)) {
                   sendAnnounce();
                    lastAnnounce = System.currentTimeMillis();
                }

				// We don't have all of the pieces yet...
				if (!sentComplete) {
					// Check to see if we have finished...
					synchronized (fileLock) {
						boolean done = true;
						Iterator<Piece> it = pieces.iterator();
						while (it.hasNext() && done) {
							done = (it.next().getState() == Piece.PieceState.COMPLETE);
						}
						if (done) {
							sendCompleteEvent();
                            System.out.println("Sent complete event");
                        }
					}

					// Pick a peer and a piece and download.
					ArrayList<Peer> tmpPeers = new ArrayList<Peer>();
					synchronized (peerLock) {
						for (Peer p : freePeers) {
							Piece piece = null;
							synchronized (fileLock) {
								for (Piece pc : pieces) {
									if (pc.getState() == Piece.PieceState.INCOMPLETE && p.canGetPiece(pc.getIndex())) {
										piece = pc;
										break;
									}
								}
							}
							if (piece == null)
								continue;
							synchronized (fileLock) {
								piece.setState(Piece.PieceState.DOWNLOADING);
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

				// Check for exit condition.
				synchronized (runLock) {
					if (!running) {
						break;
					} else {
						try {
							Thread.sleep(10);
						} catch (InterruptedException e) {
							e.printStackTrace();
						}
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
					if (dataFile != null) dataFile.close();
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

    /**
     * Callback of sorts when peers die unexpectedly
     *
     * @param p
     */

	public void peerDying(Peer p) {
		System.out.println("Peer " + p + " died. Sadface.");
		synchronized (peerLock) {
			if (freePeers.contains(p)) {
				freePeers.remove(p);
			} else if (busyPeers.values().contains(p)) {
				for (Piece pc : busyPeers.keySet()) {
					if (busyPeers.get(pc) == p) {
						pc.clearSlices();
						pc.setState(Piece.PieceState.INCOMPLETE);
					}
				}
				busyPeers.values().remove(p);
			}
		}
	}

    /**
     * Retrieves the piece based on an integer index
     *
     * @param index
     * @return
     */

	public Piece getPiece(int index) {
		return pieces.get(index);
	}

	/**
	 * Write the piece data to the piece buffer
	 *
	 * @param piece A piece object representation to be added
	 */
	public void putPiece(Piece piece) {
		MessageDigest md = null;
		byte[] sha1 = null;
		try {
			md = MessageDigest.getInstance("SHA-1");
			sha1 = md.digest(piece.getByteBuffer().array());
		}
		catch(NoSuchAlgorithmException e) {
			e.printStackTrace();
		}

		synchronized (peerLock) {
			synchronized (fileLock) {
				if (Arrays.equals(sha1, piece.getHash())) {
					fileByteBuffer.position(piece.getIndex() * torrentInfo.piece_length);
					fileByteBuffer.put(piece.getByteBuffer());
					piece.setState(Piece.PieceState.COMPLETE);
					for (Peer p : freePeers) {
						p.sendHaveMessage(piece.getIndex());
					}
					for (Peer p : busyPeers.values())
						p.sendHaveMessage(piece.getIndex());
                    // Update stats
                    downloaded += piece.getSize();
                    left -= piece.getSize();
                    System.out.println("DOWNLOADED: " + downloaded + " LEFT: " + left + " UPLOADED: " + uploaded);
                } else {
					System.out.println("Piece " + piece.getIndex() + " failed.");
					piece.clearSlices();
					piece.setState(Piece.PieceState.INCOMPLETE);
				}
				if (busyPeers.get(piece) == null) {
					System.out.println("NULL");
				}
				freePeers.add(busyPeers.get(piece));
				busyPeers.remove(piece);
			}
		}
	}

	/**
	 * Calculates and creates an arraylist of pieces to be downloaded
	 * for a given torrent
	 *
	 * @return An arraylist of pieces
	 */
	private ArrayList<Piece> generatePieces() {
		ArrayList<Piece> al = new ArrayList<Piece>();
		int total = torrentInfo.file_length;
		for (int i = 0; i < torrentInfo.piece_hashes.length; ++i, total -= torrentInfo.piece_length) {
			al.add(new Piece(i, Math.min(total, torrentInfo.piece_length), torrentInfo.piece_hashes[i], this));
		}
		return al;
	}

	/**
	 * Generates a peer id with prefix EWOK
	 *
	 * @return A peer id as a string
	 */
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
				"&port=" + port +
				"&uploaded=" + uploaded +
				"&downloaded=" + downloaded +
				"&left=" + left); // TODO: Add start event
		HttpURLConnection con = (HttpURLConnection) url.openConnection();

		DataInputStream dis = new DataInputStream(con.getInputStream());
		ByteArrayOutputStream baos = new ByteArrayOutputStream(); // Like a baos

		int reads = dis.read();
		while (reads != -1) {
			baos.write(reads);
			reads = dis.read();
		}
		dis.close();
		System.out.println("Decode:" + new String(baos.toByteArray()));
		HashMap<String,Object> res = (HashMap<String,Object>)BencodeWrapper.decode(baos.toByteArray());
        minInterval = ((Integer) res.get("min interval")) * 1000;
        return (ArrayList<HashMap<String,Object>>)res.get("peers");
	}

    // info peer port uploaded download left
    public void sendAnnounce() throws IOException {
        URL url =  new URL(this.torrentInfo.announce_url.toString() +
                "?info_hash=" + this.encodedInfoHash +
                "&peer_id=" + peerId +
                "&port=" + port +
                "&uploaded=" + uploaded +
                "&downloaded=" + downloaded +
                "&left=" + left);
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        System.out.println("Sent announce event");
    }


    /**
     * Makes a get request to the tracker to let it know that
     * we have started downloading the torrent
     *
     * @throws IOException
     */
    public void sendStartedEvent() throws IOException {
        URL url = new URL(this.torrentInfo.announce_url.toString() +
                "?info_hash=" + this.encodedInfoHash +
                "&peer_id=" + peerId +
                "&port="+ port +
                "&uploaded=" + uploaded +
                "&downloaded=" + downloaded +
                "&left=" + left +
                "&event=started");
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        System.out.println("Sent started event");
    }


	/**
	 * Makes a get request to the tracker to let it know that
	 * we have finished downloading the torrent
	 *
	 * @throws IOException
	 */
	public void sendCompleteEvent() throws IOException {
		URL url = new URL(this.torrentInfo.announce_url.toString() +
				"?info_hash=" + this.encodedInfoHash +
				"&peer_id=" + peerId +
				"&port="+ port +
				"&uploaded=" + uploaded +
				"&downloaded=" + torrentInfo.file_length +
				"&left=0" +
				"&event=completed");
		HttpURLConnection con = (HttpURLConnection) url.openConnection();
		sentComplete = true;
	}

	/**
	 * URL encodes the infohash byte array
	 *
	 * @param infoHashByteArray Byte array from torrent file
	 * @return The encoded infohash as a string
	 */
	private String encodeInfoHash(byte[] infoHashByteArray) {
		StringBuilder sb = new StringBuilder();
		for (byte b : infoHashByteArray) {
			sb.append(String.format("%%%02X", b));
		}
		return sb.toString();
	}

    public void setUploaded(int uploaded) {
        this.uploaded += uploaded;
    }

	/**
	 * Calculates a bitfield for the torrent's current state.
	 * @return
	 */
	public ByteBuffer getBitField() {
		synchronized (fileLock) {
			byte[] bf = new byte[(pieces.size() + 8 - 1) / 8]; // Ceiling(pieces.size() / 8)
			for (int i = 0; i < pieces.size(); ++i) {
				bf[i/8] |= (pieces.get(i).getState() == Piece.PieceState.COMPLETE) ? 0x80 >> (i % 8) : 0;
			}
			boolean fail = false;
			for (int i = 0; i < pieces.size() && !fail; ++i) {
				fail = (bf[i/8] != 0);
			}
			if (fail)
				return ByteBuffer.wrap(bf);
			return null;
		}
	}
}

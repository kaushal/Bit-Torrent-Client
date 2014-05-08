GROUP 02

Kaushal Parikh
William Langford
Edward Zaneski



Part 3:
This third project expands on our implementation of a bittorrent client in parts 1 and 2. This time, we are able to interface with multiple peers that we use to both upload to and download from. Additions that we added for this project include:
    allowing the user to gracefully exit all connections by typing q at any time.
    Rearchitecting the code to halve the number of threads that are actually used, and uses the "Torrent.java" class as a message broker between the tracker
        This involved getting rid of the convoluted "peerSocketRunnable" thread that each peer thread spawned another one of.
        instead, there is now a peer object to keep state, and one "PeerConnection" thread per peer to handle communicating/uploading/downloading with/to/from
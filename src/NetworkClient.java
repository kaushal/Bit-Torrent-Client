import java.io.IOException;

/**
 * Created by wlangford on 4/5/14.
 */
public interface NetworkClient {
	public void connectable();
	public void readable() throws IOException;
	public void writable();
	public void acceptable();
}

package com.cobre.notifications.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;

/**
 * A TLS receiver that never finishes answering: after reading the request it writes the response one byte at a time,
 * pausing {@code pauseMillis} between bytes, until the client closes the connection. Every single read on the client
 * succeeds within a few milliseconds, so only a TOTAL deadline can end the call; per-read timeouts never fire.
 * <p>
 * It uses the same test certificate as {@link WebhookTestServer}. {@link #droppedConnections()} counts the connections
 * on which the server saw the client go away, which proves the client released them.
 */
public final class DrippingTlsReceiver implements AutoCloseable {

	/** The status line, then headers that never end: the client is stuck waiting for the response head. */
	private static final String ENDLESS_HEAD = "HTTP/1.1 200 OK\r\nX-Drip: ";

	/** A complete head promising a huge body, then the body one byte at a time. */
	private static final String ENDLESS_BODY = "HTTP/1.1 200 OK\r\nContent-Length: 100000000\r\n\r\n";

	private final SSLServerSocket server;

	private final byte[] script;

	private final int pauseMillis;

	private final AtomicInteger accepted = new AtomicInteger();

	private final AtomicInteger dropped = new AtomicInteger();

	private DrippingTlsReceiver(boolean endlessBody, int pauseMillis) {
		try {
			KeyStore keys = KeyStore.getInstance("PKCS12");
			try (InputStream in = Files.newInputStream(Path.of("src/test/resources/tls/webhook-test-keystore.p12"))) {
				keys.load(in, "changeit".toCharArray());
			}
			KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
			factory.init(keys, "changeit".toCharArray());
			SSLContext tls = SSLContext.getInstance("TLS");
			tls.init(factory.getKeyManagers(), null, null);
			this.server = (SSLServerSocket) tls.getServerSocketFactory().createServerSocket(0, 50,
					java.net.InetAddress.getLoopbackAddress());
		}
		catch (IOException | GeneralSecurityException ex) {
			throw new IllegalStateException("Cannot start the dripping receiver", ex);
		}
		this.script = (endlessBody ? ENDLESS_BODY : ENDLESS_HEAD).getBytes(StandardCharsets.US_ASCII);
		this.pauseMillis = pauseMillis;
		Thread.ofPlatform().daemon().name("dripping-receiver").start(this::acceptLoop);
	}

	/** Never completes the response head. */
	public static DrippingTlsReceiver draggingTheHead(int pauseMillis) {
		return new DrippingTlsReceiver(false, pauseMillis);
	}

	/** Completes the head at once, then drips the body forever. */
	public static DrippingTlsReceiver draggingTheBody(int pauseMillis) {
		return new DrippingTlsReceiver(true, pauseMillis);
	}

	public int port() {
		return server.getLocalPort();
	}

	public int acceptedConnections() {
		return accepted.get();
	}

	public int droppedConnections() {
		return dropped.get();
	}

	private void acceptLoop() {
		while (!server.isClosed()) {
			try {
				Socket socket = server.accept();
				accepted.incrementAndGet();
				Thread.ofVirtual().start(() -> serve(socket));
			}
			catch (IOException ex) {
				return;
			}
		}
	}

	private void serve(Socket socket) {
		try (socket) {
			InputStream in = socket.getInputStream();
			OutputStream out = socket.getOutputStream();
			readRequestHead(in);
			for (byte b : script) {
				out.write(b);
				out.flush();
			}
			// Drip forever; a write fails once the client closed the connection.
			while (true) {
				Thread.sleep(pauseMillis);
				out.write('x');
				out.flush();
			}
		}
		catch (IOException | InterruptedException ex) {
			dropped.incrementAndGet();
		}
	}

	/** Reads up to the blank line that ends the request head; the small test body is left unread. */
	private static void readRequestHead(InputStream in) throws IOException {
		int matched = 0;
		int b;
		while (matched < 4 && (b = in.read()) != -1) {
			matched = (b == '\r' && (matched == 0 || matched == 2)) || (b == '\n' && (matched == 1 || matched == 3))
					? matched + 1 : (b == '\r' ? 1 : 0);
		}
	}

	@Override
	public void close() {
		try {
			server.close();
		}
		catch (IOException ex) {
			// Nothing left to release.
		}
	}

}

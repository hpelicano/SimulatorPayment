package org.simulator.base24.pathway;

import org.simulator.common.SimulatorException;
import org.simulator.config.model.PathwayEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sends PATHCOM commands over a plain TCP connection (ip/port) to a command gateway on the
 * Base24 host. Each command is written followed by the line terminator; the response is read
 * until the prompt appears at the end of the output.
 */
public class SocketPathwayClient implements PathwayCommandPort {

    private static final Logger log = LoggerFactory.getLogger(SocketPathwayClient.class);

    private final PathwayEndpoint ep;
    private final Charset charset;
    private final Pattern promptAtEnd;
    private final String consoleName;
    private Socket socket;
    private InputStream in;
    private OutputStream out;

    public SocketPathwayClient(PathwayEndpoint ep) {
        this(ep, StandardCharsets.ISO_8859_1);
    }

    public SocketPathwayClient(PathwayEndpoint ep, Charset charset) {
        this(ep, charset, "Pathway");
    }

    public SocketPathwayClient(PathwayEndpoint ep, Charset charset, String consoleName) {
        this.ep = ep;
        this.charset = charset;
        this.consoleName = consoleName;
        this.promptAtEnd = Pattern.compile("(?:^|\\r?\\n)" + Pattern.quote(ep.getPrompt()) + "\\s*$");
    }

    @Override
    public synchronized void connect() {
        if (isConnected()) {
            return;
        }
        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(ep.getHost(), ep.getPort()), ep.getTimeoutMs());
            s.setSoTimeout(200);
            s.setTcpNoDelay(true);
            socket = s;
            in = s.getInputStream();
            out = s.getOutputStream();
            // Gateways may or may not print a banner/prompt on connect: wait briefly for it.
            String banner = readUntilPrompt(Math.min(1000, ep.getTimeoutMs()), true);
            log.info("{} gateway connected {}:{} {}", consoleName, ep.getHost(), ep.getPort(), banner.isBlank() ? "" : "(" + banner.strip() + ")");
        } catch (IOException e) {
            close();
            throw new SimulatorException("Cannot connect to " + consoleName + " gateway " + ep.getHost() + ":" + ep.getPort()
                    + ": " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    @Override
    public synchronized String execute(String command) {
        connect();
        try {
            out.write((command + ep.getLineTerminator()).getBytes(charset));
            out.flush();
            String raw = readUntilPrompt(ep.getTimeoutMs(), false);
            return clean(raw, command);
        } catch (IOException e) {
            close();
            throw new SimulatorException(consoleName + " command failed: " + e.getMessage(), e);
        }
    }

    private String readUntilPrompt(long timeoutMs, boolean lenient) throws IOException {
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[4096];
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            try {
                int n = in.read(buf);
                if (n < 0) {
                    throw new IOException("Connection closed by peer");
                }
                sb.append(new String(buf, 0, n, charset));
                if (promptAtEnd.matcher(sb).find()) {
                    return sb.toString();
                }
            } catch (SocketTimeoutException e) {
                // poll again until the deadline
            }
            if (System.currentTimeMillis() > deadline) {
                if (lenient) {
                    return sb.toString();
                }
                throw new SimulatorException("Timeout waiting for " + consoleName + " prompt '" + ep.getPrompt() + "'. Received: " + sb);
            }
        }
    }

    private String clean(String raw, String command) {
        Matcher m = promptAtEnd.matcher(raw);
        String body = m.find() ? raw.substring(0, m.start()) : raw;
        String[] lines = body.split("\\r?\\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i == 0 && lines[i].trim().equalsIgnoreCase(command.trim())) {
                continue;
            }
            sb.append(lines[i]).append('\n');
        }
        return sb.toString().strip();
    }

    @Override
    public String describe() {
        return "tcp://" + ep.getHost() + ":" + ep.getPort();
    }

    @Override
    public synchronized void close() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing
            }
            socket = null;
        }
    }
}

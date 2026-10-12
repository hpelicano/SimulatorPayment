package org.simulator.ssh;

import com.jcraft.jsch.ChannelShell;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Interactive shell (e.g. a TACL or PATHCOM session) with send/expect semantics.
 * Not thread-safe: use from a single thread.
 */
public class InteractiveShell implements AutoCloseable {

    private final ChannelShell channel;
    private final InputStream in;
    private final OutputStream out;
    private final Charset charset;
    private final String lineTerminator;
    private final StringBuilder buffer = new StringBuilder();
    private final StringBuilder transcript = new StringBuilder();

    InteractiveShell(ChannelShell channel, Charset charset, String lineTerminator) throws IOException {
        this.channel = channel;
        this.in = channel.getInputStream();
        this.out = channel.getOutputStream();
        this.charset = charset;
        this.lineTerminator = lineTerminator;
    }

    public InteractiveShell sendLine(String line) {
        try {
            out.write((line + lineTerminator).getBytes(charset));
            out.flush();
            transcript.append(">> ").append(line).append('\n');
            return this;
        } catch (IOException e) {
            throw new SshException("Cannot write to shell: " + e.getMessage(), e);
        }
    }

    /**
     * Reads until {@code regex} is found in the output and returns everything read before and
     * including the match. Output after the match stays buffered for the next call.
     */
    public String expect(String regex, long timeoutMs) {
        Pattern p = Pattern.compile(regex, Pattern.MULTILINE);
        long deadline = System.currentTimeMillis() + timeoutMs;
        byte[] chunk = new byte[4096];
        try {
            while (true) {
                Matcher m = p.matcher(buffer);
                if (m.find()) {
                    String result = buffer.substring(0, m.end());
                    buffer.delete(0, m.end());
                    transcript.append(result);
                    return result;
                }
                if (System.currentTimeMillis() > deadline) {
                    throw new SshException("Timeout waiting for '" + regex + "'. Received: " + tail(buffer));
                }
                if (in.available() > 0) {
                    int n = in.read(chunk);
                    if (n > 0) {
                        buffer.append(new String(chunk, 0, n, charset));
                    }
                } else if (channel.isClosed()) {
                    throw new SshException("Shell closed while waiting for '" + regex + "'. Received: " + tail(buffer));
                } else {
                    Thread.sleep(20);
                }
            }
        } catch (IOException e) {
            throw new SshException("Cannot read from shell: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SshException("Interrupted", e);
        }
    }

    /** Sends a command and waits for the prompt, returning the command output without echo/prompt. */
    public String command(String line, String promptRegex, long timeoutMs) {
        sendLine(line);
        String raw = expect(promptRegex, timeoutMs);
        String withoutPrompt = raw.replaceFirst("(?s)" + promptRegex + "\\s*$", "");
        int nl = withoutPrompt.indexOf('\n');
        if (nl >= 0 && withoutPrompt.substring(0, nl).contains(line.trim())) {
            withoutPrompt = withoutPrompt.substring(nl + 1);
        }
        return withoutPrompt.strip();
    }

    public String drain() {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            while (in.available() > 0) {
                bos.write(in.read());
            }
        } catch (IOException ignored) {
            // best effort
        }
        String s = buffer + bos.toString(charset);
        buffer.setLength(0);
        return s;
    }

    public String transcript() {
        return transcript.toString();
    }

    public boolean isOpen() {
        return channel.isConnected() && !channel.isClosed();
    }

    private static String tail(CharSequence s) {
        String str = s.toString();
        return str.length() > 300 ? "..." + str.substring(str.length() - 300) : str;
    }

    @Override
    public void close() {
        channel.disconnect();
    }
}

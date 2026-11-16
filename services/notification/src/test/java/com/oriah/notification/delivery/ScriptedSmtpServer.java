package com.oriah.notification.delivery;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A mail server for tests that says exactly what a test tells it to, on a local socket inside the test's own JVM: nothing leaves the machine.
 *
 * <p>By default it accepts everything (220, 250, 354, 250, 221). A test can change the reply to any step, make the server say nothing at a step
 * (it keeps the connection open and stays silent), make it drop the connection at a step, or make every reply slow. It keeps every command it was
 * sent and every message it was handed, so a test can assert what the server saw as well as what the sender concluded.
 *
 * <p>Steps are named by the command's first word in capitals ({@code EHLO}, {@code AUTH}, {@code MAIL}, {@code RCPT}, {@code DATA}, {@code QUIT});
 * {@code "."} is the end of the message body and {@code "GREETING"} the line a server sends when a client connects.
 */
final class ScriptedSmtpServer implements AutoCloseable {

    static final String GREETING = "GREETING";
    static final String END_OF_BODY = ".";

    private final ServerSocket listening;
    private final Map<String, String> replies = new ConcurrentHashMap<>();
    private final Set<String> silentAt = ConcurrentHashMap.newKeySet();
    private final Set<String> dropAt = ConcurrentHashMap.newKeySet();
    private final List<String> extensions = new CopyOnWriteArrayList<>();
    private final List<String> commands = new CopyOnWriteArrayList<>();
    private final List<String> messages = new CopyOnWriteArrayList<>();
    private final List<Socket> open = new CopyOnWriteArrayList<>();
    private final AtomicInteger connections = new AtomicInteger();
    private volatile Duration delay = Duration.ZERO;
    private volatile boolean closed;

    private ScriptedSmtpServer() throws IOException {
        this.listening = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofPlatform().daemon().name("scripted-smtp-accept").start(this::accept);
    }

    static ScriptedSmtpServer start() {
        try {
            return new ScriptedSmtpServer();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    int port() {
        return listening.getLocalPort();
    }

    /** The whole reply line to a step, for example {@code reply("RCPT", "550 no such user")}. */
    ScriptedSmtpServer reply(String step, String line) {
        replies.put(step, line);
        return this;
    }

    /** What the server says it can do, each a line of its EHLO answer: {@code "AUTH PLAIN"}, {@code "STARTTLS"}. */
    ScriptedSmtpServer offering(String... extension) {
        extensions.addAll(List.of(extension));
        return this;
    }

    /** At this step the server says nothing and keeps the connection open. */
    ScriptedSmtpServer silentAt(String step) {
        silentAt.add(step);
        return this;
    }

    /** At this step the server closes the connection without a word. */
    ScriptedSmtpServer dropAt(String step) {
        dropAt.add(step);
        return this;
    }

    /** Every reply waits this long first. */
    ScriptedSmtpServer slowBy(Duration perReply) {
        this.delay = perReply;
        return this;
    }

    /** Every command line the server was sent, in order, across connections (the message body is not among them). */
    List<String> commands() {
        return List.copyOf(commands);
    }

    boolean saw(String firstWord) {
        return commands.stream().anyMatch(command -> command.toUpperCase(Locale.ROOT).startsWith(firstWord));
    }

    /** Every message the server was handed, headers and body, as text. */
    List<String> messages() {
        return List.copyOf(messages);
    }

    int connections() {
        return connections.get();
    }

    @Override
    public void close() {
        closed = true;
        closeQuietly(listening);
        open.forEach(ScriptedSmtpServer::closeQuietly);
    }

    private void accept() {
        while (!closed) {
            try {
                Socket socket = listening.accept();
                open.add(socket);
                connections.incrementAndGet();
                Thread.ofPlatform().daemon().name("scripted-smtp-talk").start(() -> talk(socket));
            } catch (IOException e) {
                return;
            }
        }
    }

    private void talk(Socket socket) {
        try (socket) {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = socket.getOutputStream();
            if (!answer(GREETING, "220 scripted ESMTP ready", socket, out)) {
                return;
            }
            String line;
            while ((line = in.readLine()) != null) {
                commands.add(line);
                String step = line.isBlank() ? "" : line.trim().split("\\s+")[0].toUpperCase(Locale.ROOT);
                boolean goOn = switch (step) {
                    case "EHLO" -> answer(step, ehlo(), socket, out);
                    case "HELO", "MAIL", "RCPT", "RSET", "NOOP" -> answer(step, "250 OK", socket, out);
                    case "AUTH" -> answer(step, "235 2.7.0 signed in", socket, out);
                    case "DATA" -> answer(step, "354 go ahead", socket, out) && (!accepts("DATA") || body(in, socket, out));
                    case "QUIT" -> {
                        answer(step, "221 bye", socket, out);
                        yield false;
                    }
                    default -> answer(step, "502 not understood", socket, out);
                };
                if (!goOn) {
                    return;
                }
            }
        } catch (IOException | InterruptedException e) {
            // The client went away, or the test closed the server: the conversation is over either way.
        }
    }

    /** True when the reply to DATA (scripted or default) invites the body. */
    private boolean accepts(String step) {
        return replies.getOrDefault(step, "354").startsWith("354");
    }

    private boolean body(BufferedReader in, Socket socket, OutputStream out) throws IOException, InterruptedException {
        List<String> lines = new ArrayList<>();
        String line;
        while ((line = in.readLine()) != null && !line.equals(END_OF_BODY)) {
            lines.add(line);
        }
        messages.add(String.join("\n", lines));
        return line != null && answer(END_OF_BODY, "250 2.0.0 accepted", socket, out);
    }

    private String ehlo() {
        StringBuilder answer = new StringBuilder();
        for (String extension : extensions) {
            answer.append("250-").append(extension).append("\r\n");
        }
        return answer.insert(0, "250-scripted\r\n").append("250 OK").toString();
    }

    /** @return false when the conversation ends here (the step is silent or drops the connection) */
    private boolean answer(String step, String usual, Socket socket, OutputStream out) throws IOException, InterruptedException {
        if (dropAt.contains(step)) {
            socket.close();
            return false;
        }
        if (silentAt.contains(step)) {
            // Say nothing, and keep the connection open until the test ends or the client gives up.
            while (!closed && !socket.isClosed()) {
                Thread.sleep(20);
            }
            return false;
        }
        Thread.sleep(delay.toMillis());
        out.write((replies.getOrDefault(step, usual) + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
        return true;
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception e) {
            // Nothing to do: the test is over.
        }
    }
}

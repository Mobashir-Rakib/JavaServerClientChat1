package Client;

import javax.swing.*;
import javax.swing.text.DefaultCaret;
import java.awt.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class Client extends JFrame {
    private static final int MAX_MESSAGE = 500;

    private final JTextArea chatArea = new JTextArea(18, 60);
    private final JTextField inputField = new JTextField(45);
    private final JButton sendBtn = new JButton("Send");
    private final JButton connectBtn = new JButton("Connect");
    private final JButton disconnectBtn = new JButton("Disconnect");
    private final JLabel statusLabel = new JLabel("Disconnected");

    private final DateTimeFormatter timeFmt =
            DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault());

    private Socket socket;
    private BufferedReader in;
    private BufferedWriter out;
    private volatile boolean connected;
    private String nickname;
    private SwingWorker<Void, String[]> readerWorker;
    private Thread writerThread;
    private final LinkedBlockingQueue<String> sendQ = new LinkedBlockingQueue<>(200);

    public Client() {
        super("Java Swing Chat Client");
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        buildUi();
        bindActions();
        pack();
        setLocationByPlatform(true);
    }

    private void buildUi() {
        chatArea.setEditable(false);
        chatArea.setLineWrap(true);
        chatArea.setWrapStyleWord(true);
        ((DefaultCaret) chatArea.getCaret()).setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.add(connectBtn);
        top.add(disconnectBtn);
        disconnectBtn.setEnabled(false);

        JPanel center = new JPanel(new BorderLayout());
        center.add(new JScrollPane(chatArea), BorderLayout.CENTER);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT));
        bottom.add(inputField);
        bottom.add(sendBtn);
        inputField.setEnabled(false);
        sendBtn.setEnabled(false);

        JPanel status = new JPanel(new BorderLayout());
        status.add(statusLabel, BorderLayout.WEST);
        status.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));

        JPanel southWrapper = new JPanel(new BorderLayout());
        southWrapper.add(bottom, BorderLayout.CENTER);
        southWrapper.add(status, BorderLayout.SOUTH);

        setLayout(new BorderLayout(8, 8));
        add(top, BorderLayout.NORTH);
        add(center, BorderLayout.CENTER);
        add(southWrapper, BorderLayout.SOUTH);

        pack();
        setLocationByPlatform(true);
    }

    private void bindActions() {
        connectBtn.addActionListener(_ -> onConnect());
        disconnectBtn.addActionListener(_ -> onDisconnect());
        sendBtn.addActionListener(_ -> sendCurrentText());
        inputField.addActionListener(_ -> sendCurrentText());

        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                onDisconnect();
            }
        });
    }

    private static String sanitize(String s) {
        if (s == null) return "";
        String x = s.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
        if (x.length() > MAX_MESSAGE) x = x.substring(0, MAX_MESSAGE);
        return x.strip();
    }

    private void onConnect() {
        var params = promptForConnection();
        if (params == null) return;

        String host = params.host();
        int port = params.port();
        String nick = params.nick();

        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);

            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

            nickname = nick;

            // Handshake
            writeImmediate("JOIN\t" + nickname);
            String first = in.readLine();
            if (first == null) throw new IOException("Server closed connection during handshake.");
            String[] parts = first.split("\t", 4);
            if (parts.length >= 1 && "ERROR".equals(parts[0])) {
                String reason = parts.length >= 4 ? parts[3] : "Rejected.";
                throw new IOException(reason);
            }
            if (!(parts.length >= 1 && "SYSTEM".equals(parts[0]))) {
                throw new IOException("Unexpected handshake response.");
            }

            // Start writer thread
            writerThread = new Thread(this::writerLoop, "ClientWriter");
            writerThread.setDaemon(true);
            writerThread.start();

            // Background reader via SwingWorker
            readerWorker = new SwingWorker<>() {
                @Override
                protected Void doInBackground() {
                    try {
                        connected = true;
                        while (!isCancelled() && connected) {
                            String line = in.readLine();
                            if (line == null) break;
                            String[] tokens = line.split("\t", 4);
                            publish(tokens);
                        }
                    } catch (IOException e) {
                        publish(new String[]{"ERROR", "", String.valueOf(System.currentTimeMillis()),
                                "Connection error: " + e.getMessage()});
                    }
                    return null;
                }

                @Override
                protected void process(java.util.List<String[]> chunks) {
                    for (String[] t : chunks) appendMessage(t);
                }

                @Override
                protected void done() {
                    statusLabel.setText("Disconnected");
                    connectBtn.setEnabled(true);
                    disconnectBtn.setEnabled(false);
                    inputField.setEnabled(false);
                    sendBtn.setEnabled(false);
                    appendSystem("Connection closed.");
                }
            };
            readerWorker.execute();

            connectBtn.setEnabled(false);
            disconnectBtn.setEnabled(true);
            inputField.setEnabled(true);
            sendBtn.setEnabled(true);
            statusLabel.setText("Connected");
            appendSystem("Connected as " + nickname + " to " + host + ":" + port);
            inputField.requestFocusInWindow();

        } catch (Exception ex) {
            closeQuietly();
            JOptionPane.showMessageDialog(this,
                    "Could not connect: " + ex.getMessage(),
                    "Connection Error",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void writerLoop() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                String msg = sendQ.take();
                out.write(msg);
                out.write("\n");
                out.flush();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
        }
    }

    private void sendCurrentText() {
        String text = inputField.getText();
        if (text == null || text.isBlank()) return;
        inputField.setText("");
        try {
            String clean = sanitize(text);
            if (clean.isEmpty()) return;
            String line = "CHAT\t" + nickname + "\t" + System.currentTimeMillis() + "\t" + clean;
            boolean ok = sendQ.offer(line, 2, TimeUnit.SECONDS);
            if (!ok) throw new IOException("Send queue full; connection congested.");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                    "Failed to send: " + ex.getMessage(),
                    "Send Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void onDisconnect() {
        if (!connected) {
            closeQuietly();
            return;
        }
        try {
            writeImmediate("LEAVE\t" + nickname);
        } catch (Exception ignored) {
        }
        connected = false;
        if (readerWorker != null) readerWorker.cancel(true);
        if (writerThread != null) writerThread.interrupt();
        closeQuietly();

        connectBtn.setEnabled(true);
        disconnectBtn.setEnabled(false);
        inputField.setEnabled(false);
        sendBtn.setEnabled(false);
        statusLabel.setText("Disconnected");
        appendSystem("Disconnected.");
    }

    private void appendMessage(String[] t) {
        if (t.length == 0) return;
        String type = t[0];
        String from = t.length >= 2 ? t[1] : "";
        String tsStr = t.length >= 3 ? t[2] : String.valueOf(System.currentTimeMillis());
        String body = t.length >= 4 ? t[3] : "";
        long ts;
        try {
            ts = Long.parseLong(tsStr);
        } catch (NumberFormatException e) {
            ts = System.currentTimeMillis();
        }

        String time = timeFmt.format(Instant.ofEpochMilli(ts));
        if ("SYSTEM".equals(type)) {
            chatArea.append(String.format("%s  [SYSTEM] %s%n", time, body));
        } else if ("CHAT".equals(type)) {
            chatArea.append(String.format("%s  [%s] %s%n", time, from, body));
        } else if ("ERROR".equals(type)) {
            chatArea.append(String.format("%s  [ERROR] %s%n", time, body));
        }
    }

    private void appendSystem(String msg) {
        chatArea.append(String.format("[SYSTEM] %s%n", msg));
    }

    private void writeImmediate(String line) throws IOException {
        out.write(line);
        out.write("\n");
        out.flush();
    }

    private void closeQuietly() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
        try {
            if (in != null) in.close();
        } catch (IOException ignored) {
        }
        try {
            if (out != null) out.close();
        } catch (IOException ignored) {
        }
        if (writerThread != null) writerThread.interrupt();
        connected = false;
    }

    private record ConnectParams(String host, int port, String nick) {}

    private ConnectParams promptForConnection() {
        JTextField hostField = new JTextField("127.0.0.1", 16);
        JTextField portField = new JTextField("8080", 6); // Default port manually set
        JTextField nickField = new JTextField("User" + (int) (Math.random() * 1000), 10);

        JPanel panel = new JPanel(new GridLayout(0, 2, 8, 8));
        panel.add(new JLabel("Host:"));
        panel.add(hostField);
        panel.add(new JLabel("Port:"));
        panel.add(portField);
        panel.add(new JLabel("Nickname:"));
        panel.add(nickField);

        int result = JOptionPane.showConfirmDialog(
                this, panel, "Connect to Chat",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result != JOptionPane.OK_OPTION) return null;

        String host = hostField.getText().trim();
        if (host.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Host cannot be empty.", "Input Error", JOptionPane.ERROR_MESSAGE);
            return null;
        }

        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "Port must be a number.", "Input Error", JOptionPane.ERROR_MESSAGE);
            return null;
        }

        if (port <= 0 || port > 65535) {
            JOptionPane.showMessageDialog(this, "Port out of range.", "Input Error", JOptionPane.ERROR_MESSAGE);
            return null;
        }

        String nick = nickField.getText().trim();
        if (!nick.matches("[A-Za-z0-9_]{3,24}") || "SYSTEM".equalsIgnoreCase(nick)) {
            JOptionPane.showMessageDialog(this,
                    "Nickname must be 3–24 letters/digits/underscore and not 'SYSTEM'.",
                    "Input Error", JOptionPane.ERROR_MESSAGE);
            return null;
        }

        return new ConnectParams(host, port, nick);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            Client frame = new Client();
            frame.setVisible(true);
        });
    }
}
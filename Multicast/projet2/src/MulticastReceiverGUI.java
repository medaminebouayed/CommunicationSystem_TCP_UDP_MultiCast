import javax.swing.*;
import javax.swing.text.StyledDocument;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.util.*;

public class MulticastReceiverGUI extends JFrame {

    private JTextPane pane;
    private StyledDocument doc;
    private JPanel filePanel;
    private JScrollPane fileScroll;
    private MulticastSocket socket;
    private InetAddress group;
    private int port = 5000;

    private Map<Integer, byte[]> chunkMap = new HashMap<>();
    private int totalChunks = -1;
    private String incomingFileName = "";

    public MulticastReceiverGUI() {
        setTitle("Multicast Receiver");
        setSize(700, 500);
        setLayout(new BorderLayout());
        setDefaultCloseOperation(EXIT_ON_CLOSE);

        // Text messages panel
        pane = new JTextPane();
        pane.setEditable(false);
        doc = pane.getStyledDocument();
        add(new JScrollPane(pane), BorderLayout.CENTER);

        // File buttons panel
        filePanel = new JPanel();
        filePanel.setLayout(new BoxLayout(filePanel, BoxLayout.Y_AXIS));
        fileScroll = new JScrollPane(filePanel);
        fileScroll.setPreferredSize(new Dimension(700, 150));
        add(fileScroll, BorderLayout.SOUTH);

        new Thread(this::receive).start();
    }

    private void log(String txt) {
        try {
            doc.insertString(doc.getLength(), txt + "\n", null);
        } catch (Exception ignored) {}
    }

    private void showImage(byte[] data) {
        try {
            ImageIcon ic = new ImageIcon(data);
            Image img = ic.getImage().getScaledInstance(120, 120, Image.SCALE_SMOOTH);
            pane.setCaretPosition(doc.getLength());
            pane.insertIcon(new ImageIcon(img));
            log("[Image Received]");
        } catch (Exception e) {
            log("Image error: " + e.getMessage());
        }
    }

    private void receive() {
        try {
            group = InetAddress.getByName("230.0.0.0");
            socket = new MulticastSocket(port);
            socket.joinGroup(group);

            byte[] buf = new byte[65000];

            while (true) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                socket.receive(p);

                byte[] data = Arrays.copyOf(p.getData(), p.getLength());
                String header = new String(data, 0, 4);

                // TEXT----------------------------
                if (header.startsWith("TXT:")) {
                    String msg = new String(data, 4, data.length - 4);
                    log("Message: " + msg);
                }

                // IMAGE or FILE -------------------
                else if (header.startsWith("BIN:")) {
                    int index = ((data[4] & 0xFF) << 8) | (data[5] & 0xFF);
                    totalChunks = ((data[6] & 0xFF) << 8) | (data[7] & 0xFF);
                    int nameLen = data[8] & 0xFF;
                    incomingFileName = new String(data, 9, nameLen);

                    byte[] chunk = Arrays.copyOfRange(data, 9 + nameLen, data.length);
                    chunkMap.put(index, chunk);

                    if (chunkMap.size() == totalChunks) {
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        for (int i = 0; i < totalChunks; i++) out.write(chunkMap.get(i));
                        byte[] full = out.toByteArray();

                        if (incomingFileName.matches(".*\\.(png|jpg|jpeg|gif)$")) {
                            showImage(full);
                        } else {
                            File dir = new File("received_files");
                            if (!dir.exists()) dir.mkdir();
                            File outFile = new File(dir, incomingFileName);
                            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                                fos.write(full);
                            }
                            addFileButton(outFile);
                        }

                        chunkMap.clear();
                    }
                }
            }
        } catch (Exception e) {
            log("Receiver error: " + e.getMessage());
        }
    }

    private void addFileButton(File file) {
        JButton btn = new JButton("Open: " + file.getName());
        btn.setAlignmentX(Component.LEFT_ALIGNMENT);
        btn.addActionListener(e -> {
            try {
                Desktop.getDesktop().open(file);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Cannot open file: " + ex.getMessage());
            }
        });

        SwingUtilities.invokeLater(() -> {
            filePanel.add(btn);
            filePanel.revalidate();
            filePanel.repaint();
            log("File received: " + file.getName());
        });
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new MulticastReceiverGUI().setVisible(true));
    }
}

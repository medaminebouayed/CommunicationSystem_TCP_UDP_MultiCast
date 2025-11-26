import javax.swing.*;
import javax.swing.text.StyledDocument;
import java.awt.*;
import java.io.*;
import java.net.*;

public class MulticastSenderGUI extends JFrame {

    private JTextField field;
    private JTextPane pane;
    private StyledDocument doc;
    private InetAddress group;
    private int port = 5000;

    public MulticastSenderGUI() {
        setTitle("Multicast Sender");
        setSize(600, 400);
        setLayout(new BorderLayout());
        setDefaultCloseOperation(EXIT_ON_CLOSE);

        field = new JTextField();
        JButton sendBtn = new JButton("Send Text");
        JButton imgBtn = new JButton("Send Image");
        JButton fileBtn = new JButton("Send File");

        JPanel top = new JPanel(new BorderLayout());
        top.add(field, BorderLayout.CENTER);

        JPanel right = new JPanel(new FlowLayout());
        right.add(imgBtn);
        right.add(fileBtn);
        right.add(sendBtn);
        top.add(right, BorderLayout.EAST);

        add(top, BorderLayout.NORTH);

        pane = new JTextPane();
        pane.setEditable(false);
        doc = pane.getStyledDocument();
        add(new JScrollPane(pane), BorderLayout.CENTER);

        try {
            group = InetAddress.getByName("230.0.0.0");
        } catch (Exception ignored) {}

        sendBtn.addActionListener(e -> sendText());
        imgBtn.addActionListener(e -> chooseFile(true));
        fileBtn.addActionListener(e -> chooseFile(false));
    }

    private void log(String txt) {
        try {
            doc.insertString(doc.getLength(), txt + "\n", null);
        } catch (Exception ignored) {}
    }

    private void sendText() {
        String msg = field.getText();
        if (msg.isEmpty()) return;

        try (DatagramSocket s = new DatagramSocket()) {
            byte[] data = ("TXT:" + msg).getBytes();
            s.send(new DatagramPacket(data, data.length, group, port));
            log("Text Sent: " + msg);
        } catch (Exception ex) {
            log("Send error: " + ex.getMessage());
        }
        field.setText("");
    }

    private void chooseFile(boolean imageOnly) {
        JFileChooser ch = new JFileChooser();
        if (ch.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = ch.getSelectedFile();

        if (imageOnly && !f.getName().matches(".*\\.(png|jpg|jpeg|gif)$")) {
            JOptionPane.showMessageDialog(this, "Please select an image file.");
            return;
        }

        try {
            sendBinary(f);
            if (imageOnly) {
                ImageIcon ic = new ImageIcon(f.getAbsolutePath());
                Image img = ic.getImage().getScaledInstance(120, 120, Image.SCALE_SMOOTH);
                pane.setCaretPosition(doc.getLength());
                pane.insertIcon(new ImageIcon(img));
                log("Image Sent: " + f.getName());
            } else {
                log("File Sent: " + f.getName());
            }
        } catch (Exception ex) {
            log("Send error: " + ex.getMessage());
        }
    }

    private void sendBinary(File file) throws Exception {
        byte[] fileBytes = java.nio.file.Files.readAllBytes(file.toPath());
        int chunkSize = 60000;
        int total = (int) Math.ceil((double) fileBytes.length / chunkSize);
        byte[] nameBytes = file.getName().getBytes();
        int nameLen = nameBytes.length;

        DatagramSocket sock = new DatagramSocket();

        for (int i = 0; i < total; i++) {
            int start = i * chunkSize;
            int len = Math.min(chunkSize, fileBytes.length - start);

            byte[] chunk = new byte[9 + nameLen + len];
            chunk[0] = 'B'; chunk[1] = 'I'; chunk[2] = 'N'; chunk[3] = ':';
            chunk[4] = (byte) (i >> 8);
            chunk[5] = (byte) i;
            chunk[6] = (byte) (total >> 8);
            chunk[7] = (byte) total;
            chunk[8] = (byte) nameLen;

            System.arraycopy(nameBytes, 0, chunk, 9, nameLen);
            System.arraycopy(fileBytes, start, chunk, 9 + nameLen, len);

            sock.send(new DatagramPacket(chunk, chunk.length, group, port));
        }

        sock.close();
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new MulticastSenderGUI().setVisible(true));
    }
}

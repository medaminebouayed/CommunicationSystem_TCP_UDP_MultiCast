import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.util.*;
import javax.imageio.ImageIO;
import javax.sound.sampled.*;

public class ChatClient extends JFrame {

    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;

    private String name;

    private JTextPane chatPane;
    private JTextField inputField;
    private JButton sendButton, imageButton, voiceButton, fileButton;
    private JComboBox<String> destSelector;
    private DefaultListModel<String> listModel;
    private JList<String> userList;

    private boolean recording = false;
    private TargetDataLine microphone;
    private File currentAudioFile;

    public ChatClient() {
        setupUI();
        connectToServer();
    }

    private void setupUI() {
        name = JOptionPane.showInputDialog(this, "Entrez votre nom :");
        if (name == null || name.trim().isEmpty()) name = "ANONYME";

        setTitle("Chat Client (" + name + ")");
        setSize(700, 500);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        chatPane = new JTextPane();
        chatPane.setEditable(false);
        add(new JScrollPane(chatPane), BorderLayout.CENTER);

        // Bottom panel
        JPanel bottom = new JPanel(new BorderLayout());
        inputField = new JTextField();
        sendButton = new JButton("Envoyer");
        imageButton = new JButton("📸 Image");
        voiceButton = new JButton("🎙️ Vocal");
        fileButton = new JButton("📁 Fichier");

        JPanel topBottom = new JPanel(new FlowLayout(FlowLayout.LEFT));
        destSelector = new JComboBox<>();
        destSelector.addItem("TOUS");
        topBottom.add(new JLabel("À :"));
        topBottom.add(destSelector);
        topBottom.add(imageButton);
        topBottom.add(voiceButton);
        topBottom.add(fileButton);
        bottom.add(topBottom, BorderLayout.NORTH);

        JPanel msgPanel = new JPanel(new BorderLayout());
        msgPanel.add(inputField, BorderLayout.CENTER);
        msgPanel.add(sendButton, BorderLayout.EAST);
        bottom.add(msgPanel, BorderLayout.SOUTH);

        add(bottom, BorderLayout.SOUTH);

        listModel = new DefaultListModel<>();
        userList = new JList<>(listModel);
        userList.setBorder(BorderFactory.createTitledBorder("Connectés"));
        add(new JScrollPane(userList), BorderLayout.EAST);

        // Event listeners
        sendButton.addActionListener(e -> sendMessage());
        inputField.addActionListener(e -> sendMessage());
        imageButton.addActionListener(e -> sendImage());
        voiceButton.addActionListener(e -> toggleVoiceRecording());
        fileButton.addActionListener(e -> sendFile());

        setVisible(true);
    }

    private void connectToServer() {
        try {
            socket = new Socket("localhost", 5000);
            in = new DataInputStream(socket.getInputStream());
            out = new DataOutputStream(socket.getOutputStream());

            out.writeUTF(name);
            out.flush();

            new Thread(this::listenServer).start();

        } catch (IOException e) {
            appendText("❌ Impossible de se connecter au serveur\n", Color.RED);
        }
    }

    private void sendMessage() {
        String msg = inputField.getText().trim();
        if (msg.isEmpty()) return;

        String dest = (String) destSelector.getSelectedItem();
        if (dest == null) dest = "TOUS";

        try {
            out.writeUTF("TEXT");
            out.writeUTF(dest);
            out.writeUTF(getTimestamp());
            out.writeUTF(msg);
            out.flush();

            appendText("Moi → " + dest + " : " + msg + "\n", Color.BLUE);
            inputField.setText("");
        } catch (IOException e) {
            appendText("❌ Erreur d’envoi.\n", Color.RED);
        }
    }

    private void sendImage() {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            sendBinaryFile("IMG", chooser.getSelectedFile());
        }
    }

    private void sendFile() {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            sendBinaryFile("FILE", chooser.getSelectedFile());
        }
    }

    private void toggleVoiceRecording() {
        if (!recording) startRecording();
        else stopRecordingAndSend();
    }

    private void sendBinaryFile(String type, File file) {
        String dest = (String) destSelector.getSelectedItem();
        if (dest == null) dest = "TOUS";

        try {
            byte[] data = java.nio.file.Files.readAllBytes(file.toPath());
            out.writeUTF(type);
            out.writeUTF(dest);
            out.writeUTF(name);
            out.writeUTF(file.getName());
            out.writeInt(data.length);
            out.write(data);
            out.flush();

            appendText("Moi → " + dest + " : " + file.getName() + "\n", Color.BLUE);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void startRecording() {
        try {
            AudioFormat format = new AudioFormat(16000, 16, 2, true, true);
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
            microphone = (TargetDataLine) AudioSystem.getLine(info);
            microphone.open(format);
            microphone.start();

            currentAudioFile = new File("voice_" + System.currentTimeMillis() + ".wav");
            new Thread(() -> {
                try (AudioInputStream ais = new AudioInputStream(microphone)) {
                    AudioSystem.write(ais, AudioFileFormat.Type.WAVE, currentAudioFile);
                } catch (IOException e) { e.printStackTrace(); }
            }).start();

            recording = true;
            voiceButton.setText("⏹️ Stop");
            appendText("🎙️ Enregistrement...\n", Color.GRAY);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void stopRecordingAndSend() {
        try {
            microphone.stop();
            microphone.close();
            recording = false;
            voiceButton.setText("🎙️ Vocal");
            appendText("🎤 Envoi du vocal...\n", Color.GRAY);

            sendBinaryFile("AUDIO", currentAudioFile);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void listenServer() {
        try {
            while (true) {
                String type = in.readUTF();
                if (type.equals("TEXT")) {
                    String target = in.readUTF();
                    String msg = in.readUTF();
                    appendText(msg + "\n", target.equals("ALL") ? Color.BLACK : Color.MAGENTA);
                } else if (type.equals("IMG") || type.equals("AUDIO") || type.equals("FILE")) {
                    receiveFile(type);
                } else if (type.equals("LISTE")) {
                    updateUserList(in.readUTF());
                }
            }
        } catch (IOException e) {
            appendText("⚠️ Déconnecté.\n", Color.RED);
        }
    }

    private void receiveFile(String type) throws IOException {
        String sender = in.readUTF();
        String filename = in.readUTF();
        int size = in.readInt();
        byte[] data = new byte[size];
        in.readFully(data);

        File file = new File("received_" + filename);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(data);
        }

        if (type.equals("IMG")) appendImage(file);
        else if (type.equals("AUDIO")) appendAudioMessage(sender, file);
        else if (type.equals("FILE")) appendFileMessage(sender, file);
    }

    private void appendText(String msg, Color color) {
        try {
            StyledDocument doc = chatPane.getStyledDocument();
            Style style = chatPane.addStyle("Style", null);
            StyleConstants.setForeground(style, color);
            doc.insertString(doc.getLength(), msg, style);
            chatPane.setCaretPosition(doc.getLength());
        } catch (Exception ignored) {}
    }

    private void appendImage(File file) {
        try {
            ImageIcon icon = new ImageIcon(ImageIO.read(file));
            chatPane.setCaretPosition(chatPane.getDocument().getLength());
            chatPane.insertIcon(icon);
            appendText("\n", Color.BLACK);
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void appendAudioMessage(String sender, File audioFile) {
        JButton playBtn = new JButton("▶️ Écouter " + sender);
        playBtn.addActionListener(e -> playAudio(audioFile));
        chatPane.insertComponent(playBtn);
        appendText("\n", Color.BLACK);
    }

    private void appendFileMessage(String sender, File file) {
        JButton downloadBtn = new JButton("📂 Télécharger " + file.getName() + " (" + sender + ")");
        downloadBtn.addActionListener(e -> downloadFile(file));
        chatPane.insertComponent(downloadBtn);
        appendText("\n", Color.BLACK);
    }

    private void playAudio(File file) {
        try {
            AudioInputStream ais = AudioSystem.getAudioInputStream(file);
            Clip clip = AudioSystem.getClip();
            clip.open(ais);
            clip.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void downloadFile(File file) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Sélectionnez le dossier où télécharger le fichier");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        int userSelection = chooser.showSaveDialog(this);
        if (userSelection == JFileChooser.APPROVE_OPTION) {
            File destDir = chooser.getSelectedFile();
            File destFile = new File(destDir, file.getName());
            try (FileInputStream fis = new FileInputStream(file);
                 FileOutputStream fos = new FileOutputStream(destFile)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                }
                JOptionPane.showMessageDialog(this, "Fichier téléchargé vers :\n" + destFile.getAbsolutePath(),
                        "Téléchargement terminé",
                        JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this, "Impossible de télécharger le fichier.", "Erreur", JOptionPane.ERROR_MESSAGE);
                e.printStackTrace();
            }
        }
    }

    private void updateUserList(String names) {
        SwingUtilities.invokeLater(() -> {
            listModel.clear();
            destSelector.removeAllItems();
            destSelector.addItem("TOUS");
            for (String n : names.split(",")) {
                if (!n.isEmpty() && !n.equals(name)) {
                    listModel.addElement(n);
                    destSelector.addItem(n);
                }
            }
        });
    }

    private String getTimestamp() {
        return new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(ChatClient::new);
    }
}

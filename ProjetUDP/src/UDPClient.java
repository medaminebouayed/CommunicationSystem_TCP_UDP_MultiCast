import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.util.*;
import javax.imageio.ImageIO;
import javax.sound.sampled.*;

public class UDPClient extends JFrame {

    private DatagramSocket socket;
    private InetAddress serverAddress;
    private int serverPort = 5000;

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

    public UDPClient() {
        setupUI();
        connectToServer();
    }

    private void setupUI() {
        name = JOptionPane.showInputDialog(this, "Entrez votre nom :");
        if (name == null || name.trim().isEmpty()) name = "ANONYME";

        setTitle("Chat Client UDP (" + name + ")");
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
            socket = new DatagramSocket();
            serverAddress = InetAddress.getByName("localhost");

            // Envoyer le nom au serveur
            sendPacket("REGISTER:" + name);

            new Thread(this::listenServer).start();

        } catch (IOException e) {
            appendText("❌ Impossible de se connecter au serveur\n", Color.RED);
        }
    }

    private void sendPacket(String message) {
        try {
            byte[] data = message.getBytes("UTF-8");
            DatagramPacket packet = new DatagramPacket(data, data.length, serverAddress, serverPort);
            socket.send(packet);
        } catch (IOException e) {
            appendText("❌ Erreur d'envoi\n", Color.RED);
        }
    }

    private void sendMessage() {
        String msg = inputField.getText().trim();
        if (msg.isEmpty()) return;

        String dest = (String) destSelector.getSelectedItem();
        if (dest == null) dest = "TOUS";

        try {
            sendPacket("TEXT:" + dest + ":" + name + ":" + msg);
            appendText("Moi → " + dest + " : " + msg + "\n", Color.BLUE);
            inputField.setText("");
        } catch (Exception e) {
            appendText("❌ Erreur d'envoi.\n", Color.RED);
        }
    }

    private void sendImage() {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            // Vérifier la taille du fichier
            if (file.length() > 60000) {
                JOptionPane.showMessageDialog(this, "Fichier trop volumineux pour UDP (max 60KB)", "Erreur", JOptionPane.ERROR_MESSAGE);
                return;
            }
            sendFile("IMG", file);
        }
    }

    private void sendFile() {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            if (file.length() > 60000) {
                JOptionPane.showMessageDialog(this, "Fichier trop volumineux pour UDP (max 60KB)", "Erreur", JOptionPane.ERROR_MESSAGE);
                return;
            }
            sendFile("FILE", file);
        }
    }

    private void toggleVoiceRecording() {
        if (!recording) startRecording();
        else stopRecordingAndSend();
    }

    private void sendFile(String type, File file) {
        String dest = (String) destSelector.getSelectedItem();
        if (dest == null) dest = "TOUS";

        try {
            byte[] fileData = java.nio.file.Files.readAllBytes(file.toPath());

            // Envoyer l'en-tête et les données en un seul paquet
            String header = type + ":" + dest + ":" + name + ":" + file.getName() + ":" + fileData.length;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            baos.write(header.getBytes("UTF-8"));
            baos.write(0x1E); // Séparateur
            baos.write(fileData);

            byte[] combinedData = baos.toByteArray();

            if (combinedData.length > 65507) {
                appendText("❌ Fichier trop volumineux pour UDP\n", Color.RED);
                return;
            }

            DatagramPacket packet = new DatagramPacket(combinedData, combinedData.length, serverAddress, serverPort);
            socket.send(packet);

            appendText("Moi → " + dest + " : " + file.getName() + "\n", Color.BLUE);
        } catch (Exception e) {
            e.printStackTrace();
            appendText("❌ Erreur d'envoi du fichier\n", Color.RED);
        }
    }

    private void startRecording() {
        try {
            AudioFormat format = new AudioFormat(16000, 16, 1, true, true);
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

            // Vérifier la taille du fichier audio
            if (currentAudioFile.length() > 60000) {
                appendText("❌ Fichier audio trop volumineux\n", Color.RED);
                return;
            }

            sendFile("AUDIO", currentAudioFile);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void listenServer() {
        byte[] buffer = new byte[65507];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

        try {
            while (true) {
                socket.receive(packet);
                String message = new String(packet.getData(), 0, packet.getLength(), "UTF-8");

                if (message.startsWith("TEXT:")) {
                    String[] parts = message.split(":", 4);
                    if (parts.length >= 4) {
                        String target = parts[1];
                        String sender = parts[2];
                        String msg = parts[3];
                        Color color = target.equals("ALL") ? Color.BLACK : Color.MAGENTA;
                        appendText("[" + sender + "] " + msg + "\n", color);
                    }
                } else if (message.startsWith("IMG:") || message.startsWith("AUDIO:") || message.startsWith("FILE:")) {
                    receiveFile(message, packet.getData(), packet.getLength());
                } else if (message.startsWith("LISTE:")) {
                    updateUserList(message.substring(6));
                }
            }
        } catch (IOException e) {
            appendText("⚠️ Déconnecté.\n", Color.RED);
        }
    }

    private void receiveFile(String message, byte[] data, int length) {
        try {
            // Trouver le séparateur
            int separatorIndex = -1;
            for (int i = 0; i < length; i++) {
                if (data[i] == 0x1E) {
                    separatorIndex = i;
                    break;
                }
            }

            if (separatorIndex == -1) return;

            // Extraire l'en-tête
            String header = new String(data, 0, separatorIndex, "UTF-8");
            String[] parts = header.split(":");
            if (parts.length < 5) return;

            String type = parts[0];
            String sender = parts[2];
            String filename = parts[3];
            int size = Integer.parseInt(parts[4]);

            // Extraire les données du fichier
            int fileDataStart = separatorIndex + 1;
            int fileDataEnd = Math.min(length, fileDataStart + size);
            byte[] fileData = Arrays.copyOfRange(data, fileDataStart, fileDataEnd);

            File file = new File("received_" + System.currentTimeMillis() + "_" + filename);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(fileData);
            }

            if (type.equals("IMG")) {
                appendImage(file);
            } else if (type.equals("AUDIO")) {
                appendAudioMessage(sender, file);
            } else if (type.equals("FILE")) {
                appendFileMessage(sender, file);
            }
        } catch (Exception e) {
            e.printStackTrace();
            appendText("❌ Erreur de réception du fichier\n", Color.RED);
        }
    }

    private void appendText(String msg, Color color) {
        SwingUtilities.invokeLater(() -> {
            try {
                StyledDocument doc = chatPane.getStyledDocument();
                Style style = chatPane.addStyle("Style", null);
                StyleConstants.setForeground(style, color);
                doc.insertString(doc.getLength(), msg, style);
                chatPane.setCaretPosition(doc.getLength());
            } catch (Exception ignored) {}
        });
    }

    private void appendImage(File file) {
        SwingUtilities.invokeLater(() -> {
            try {
                // Vérifier que le fichier existe et est lisible
                if (!file.exists() || file.length() == 0) {
                    appendText("❌ Image corrompue ou manquante\n", Color.RED);
                    return;
                }

                Image image = ImageIO.read(file);
                if (image == null) {
                    appendText("❌ Format d'image non supporté\n", Color.RED);
                    return;
                }

                ImageIcon icon = new ImageIcon(image.getScaledInstance(200, 150, Image.SCALE_SMOOTH));
                chatPane.setCaretPosition(chatPane.getDocument().getLength());
                chatPane.insertIcon(icon);
                appendText("\n", Color.BLACK);
            } catch (Exception e) {
                e.printStackTrace();
                appendText("❌ Erreur d'affichage de l'image\n", Color.RED);
            }
        });
    }

    private void appendAudioMessage(String sender, File audioFile) {
        SwingUtilities.invokeLater(() -> {
            JButton playBtn = new JButton("▶️ Écouter " + sender);
            playBtn.addActionListener(e -> playAudio(audioFile));
            chatPane.insertComponent(playBtn);
            appendText("\n", Color.BLACK);
        });
    }

    private void appendFileMessage(String sender, File file) {
        SwingUtilities.invokeLater(() -> {
            JButton downloadBtn = new JButton("📂 Télécharger " + file.getName() + " (" + sender + ")");
            downloadBtn.addActionListener(e -> downloadFile(file));
            chatPane.insertComponent(downloadBtn);
            appendText("\n", Color.BLACK);
        });
    }

    private void playAudio(File file) {
        try {
            AudioInputStream ais = AudioSystem.getAudioInputStream(file);
            Clip clip = AudioSystem.getClip();
            clip.open(ais);
            clip.start();
        } catch (Exception e) {
            appendText("❌ Erreur de lecture audio\n", Color.RED);
        }
    }

    private void downloadFile(File file) {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File(file.getName()));
        int userSelection = chooser.showSaveDialog(this);
        if (userSelection == JFileChooser.APPROVE_OPTION) {
            File destFile = chooser.getSelectedFile();
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

    public static void main(String[] args) {
        SwingUtilities.invokeLater(UDPClient::new);
    }
}
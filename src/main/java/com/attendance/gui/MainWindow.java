package com.attendance.gui;

import com.attendance.database.DatabaseHelper;
import com.attendance.model.Attendance;
import com.attendance.model.Student;
import com.attendance.recognition.FaceRecognitionEngine;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.MatOfRect;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.videoio.VideoCapture;

import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Main Swing window for camera recognition and attendance logging.
 */
public class MainWindow extends JFrame {

    private JLabel webcamLabel;
    private JLabel statusLabel;
    private JTable attendanceTable;
    private DefaultTableModel tableModel;
    private JButton startBtn;
    private JButton stopBtn;
    private JButton trainBtn;
    private JButton exportBtn;

    private VideoCapture camera;
    private final FaceRecognitionEngine recognitionEngine;
    private final DatabaseHelper dbHelper;
    private Timer webcamTimer;
    private Timer clearDataTimer;
    private boolean isRunning = false;

    private String lastRecognizedName = "";
    private String lastMarkedSapId = "";
    private String lastPhoneProxyIdentity = "";
    private String livenessIdentity = "";
    private Mat previousLivenessFace = null;
    private int recognitionCounter = 0;
    private int unknownCounter = 0;
    private int phoneProxyCounter = 0;
    private int livenessSamples = 0;
    private int livenessMotionFrames = 0;
    private int livenessFailCounter = 0;
    private static final int STABILITY_THRESHOLD = 8;
    private static final int UNKNOWN_STABILITY_THRESHOLD = 10;
    private static final int PHONE_PROXY_STABILITY_THRESHOLD = 3;
    private static final int LIVENESS_MIN_SAMPLES = 10;
    private static final int LIVENESS_REQUIRED_MOTION_FRAMES = 4;
    private static final int LIVENESS_PROXY_STABILITY_THRESHOLD = 24;
    private static final double LIVENESS_MIN_FRAME_CHANGE = 3.8;
    private static final double LIVENESS_MAX_FRAME_CHANGE = 42.0;
    private static final double LIVENESS_MIN_EDGE_DENSITY = 0.035;
    private static final double PHONE_PROXY_IDENTITY_THRESHOLD = 0.42;
    private long lastMarkTime = 0;
    private long lastUnknownMarkTime = 0;
    private long lastPhoneProxyMarkTime = 0;
    private static final long COOLDOWN_MS = 1500;
    private static final long UNKNOWN_COOLDOWN_MS = 10000;
    private static final long PHONE_PROXY_COOLDOWN_MS = 10000;
    private static final int DATA_RETENTION_MINUTES = 10;
    private static final int DATA_RESET_INTERVAL_MS = DATA_RETENTION_MINUTES * 60 * 1000;

    private static final String STUDENTS_FOLDER = "students";

    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "attendance-db-worker");
        thread.setDaemon(true);
        return thread;
    });

    public MainWindow() {
        dbHelper = new DatabaseHelper();
        recognitionEngine = new FaceRecognitionEngine();
        setupUI();
        resetAttendanceOnStartup();
        SwingUtilities.invokeLater(this::trainModel);
    }

    private void setupUI() {
        setTitle("Face Recognition Attendance System");
        setSize(1300, 720);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout(5, 5));
        getContentPane().setBackground(new Color(20, 20, 40));

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                stopCamera();
                dbExecutor.shutdownNow();
                dbHelper.close();
            }
        });

        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.setBackground(new Color(15, 15, 35));
        topPanel.setBorder(BorderFactory.createEmptyBorder(8, 15, 8, 15));

        JLabel titleLabel = new JLabel("Face Recognition Attendance System");
        titleLabel.setFont(new Font("Arial", Font.BOLD, 22));
        titleLabel.setForeground(Color.WHITE);

        statusLabel = new JLabel("Status: Idle", JLabel.RIGHT);
        statusLabel.setForeground(Color.YELLOW);
        statusLabel.setFont(new Font("Arial", Font.BOLD, 14));

        topPanel.add(titleLabel, BorderLayout.WEST);
        topPanel.add(statusLabel, BorderLayout.EAST);
        add(topPanel, BorderLayout.NORTH);

        JPanel centerPanel = new JPanel(new BorderLayout());
        centerPanel.setBackground(new Color(20, 20, 40));
        centerPanel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 5));

        webcamLabel = new JLabel("Camera Feed", JLabel.CENTER);
        webcamLabel.setBackground(Color.BLACK);
        webcamLabel.setForeground(new Color(100, 100, 100));
        webcamLabel.setFont(new Font("Arial", Font.BOLD, 16));
        webcamLabel.setOpaque(true);
        webcamLabel.setPreferredSize(new Dimension(700, 520));

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 5));
        btnPanel.setBackground(new Color(20, 20, 40));

        startBtn = new JButton("Start Camera");
        stopBtn = new JButton("Stop");
        trainBtn = new JButton("Train Model");
        exportBtn = new JButton("Export CSV");

        styleButton(startBtn, new Color(0, 130, 200));
        styleButton(stopBtn, new Color(200, 50, 50));
        styleButton(trainBtn, new Color(160, 110, 0));
        styleButton(exportBtn, new Color(0, 140, 80));

        startBtn.addActionListener(e -> startCamera());
        stopBtn.addActionListener(e -> stopCamera());
        trainBtn.addActionListener(e -> trainModel());
        exportBtn.addActionListener(e -> exportCSV());
        stopBtn.setEnabled(false);

        btnPanel.add(startBtn);
        btnPanel.add(stopBtn);
        btnPanel.add(trainBtn);
        btnPanel.add(exportBtn);

        centerPanel.add(webcamLabel, BorderLayout.CENTER);
        centerPanel.add(btnPanel, BorderLayout.SOUTH);
        add(centerPanel, BorderLayout.CENTER);

        JPanel rightPanel = new JPanel(new BorderLayout());
        rightPanel.setBackground(new Color(20, 20, 40));
        rightPanel.setPreferredSize(new Dimension(520, 0));
        rightPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 10));

        JLabel logLabel = new JLabel("  Attendance Log - Today");
        logLabel.setForeground(Color.WHITE);
        logLabel.setFont(new Font("Arial", Font.BOLD, 15));
        logLabel.setOpaque(true);
        logLabel.setBackground(new Color(15, 15, 35));
        logLabel.setBorder(BorderFactory.createEmptyBorder(8, 5, 8, 5));

        String[] columns = {"Sr.", "Name", "SAP ID", "Class", "Date", "Time", "Status"};
        tableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        attendanceTable = new JTable(tableModel);
        attendanceTable.setBackground(new Color(30, 30, 50));
        attendanceTable.setForeground(Color.WHITE);
        attendanceTable.setGridColor(new Color(50, 50, 70));
        attendanceTable.setRowHeight(28);
        attendanceTable.setFont(new Font("Arial", Font.PLAIN, 13));
        attendanceTable.getTableHeader().setBackground(new Color(15, 15, 35));
        attendanceTable.getTableHeader().setForeground(Color.WHITE);
        attendanceTable.getTableHeader().setFont(new Font("Arial", Font.BOLD, 13));

        attendanceTable.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                                                           boolean isSelected, boolean hasFocus,
                                                           int row, int col) {
                super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col);
                String status = String.valueOf(table.getValueAt(row, 6));
                if ("Absent".equals(status) || "Proxy".equals(status) || "Unknown".equals(status)) {
                    setBackground(new Color(120, 30, 30));
                } else {
                    setBackground(row % 2 == 0 ? new Color(30, 30, 50) : new Color(35, 35, 58));
                }
                setForeground(Color.WHITE);
                return this;
            }
        });

        JScrollPane tableScroll = new JScrollPane(attendanceTable);
        tableScroll.setBackground(new Color(20, 20, 40));
        tableScroll.getViewport().setBackground(new Color(30, 30, 50));

        rightPanel.add(logLabel, BorderLayout.NORTH);
        rightPanel.add(tableScroll, BorderLayout.CENTER);
        add(rightPanel, BorderLayout.EAST);

        setLocationRelativeTo(null);
    }

    private void startCamera() {
        camera = new VideoCapture();
        for (int i = 0; i < 3; i++) {
            camera.open(i);
            if (camera.isOpened()) {
                break;
            }
        }

        if (!camera.isOpened()) {
            statusLabel.setText("Camera not found.");
            return;
        }

        lastMarkedSapId = "";
        lastRecognizedName = "";
        clearRecognitionState();
        lastMarkTime = 0;
        lastUnknownMarkTime = 0;
        lastPhoneProxyMarkTime = 0;
        loadAttendanceTable();

        camera.set(3, 700);
        camera.set(4, 520);
        isRunning = true;
        startBtn.setEnabled(false);
        stopBtn.setEnabled(true);
        statusLabel.setText("Camera running...");

        webcamTimer = new Timer(100, e -> captureFrame());
        webcamTimer.start();
        startAutoClearTimer();
    }

    private void startAutoClearTimer() {
        if (clearDataTimer != null) {
            clearDataTimer.stop();
        }

        clearDataTimer = new Timer(DATA_RESET_INTERVAL_MS, e -> {
            if (isRunning) {
                resetAttendanceData();
            }
        });
        clearDataTimer.setRepeats(true);
        clearDataTimer.start();
    }

    private void resetAttendanceData() {
        dbExecutor.execute(() -> {
            int deletedRows = dbHelper.clearAllAttendance();
            SwingUtilities.invokeLater(() -> {
                tableModel.setRowCount(0);
                lastMarkedSapId = "";
                clearRecognitionState();
                lastMarkTime = 0;
                lastUnknownMarkTime = 0;
                lastPhoneProxyMarkTime = 0;
                statusLabel.setText("Attendance reset after " + DATA_RETENTION_MINUTES
                    + " minutes. Cleared records: " + deletedRows);
            });
        });
    }

    private void resetAttendanceOnStartup() {
        dbExecutor.execute(() -> {
            int deletedRows = dbHelper.clearAllAttendance();
            SwingUtilities.invokeLater(() -> {
                tableModel.setRowCount(0);
                statusLabel.setText("Ready. Cleared previous attendance records: " + deletedRows);
            });
        });
    }

    private void captureFrame() {
        if (camera == null || !camera.isOpened()) {
            return;
        }

        Mat frame = new Mat();
        if (!camera.read(frame) || frame.empty()) {
            frame.release();
            return;
        }

        MatOfRect faces = recognitionEngine.detectFaces(frame);
        Rect[] faceArray = faces.toArray();
        faces.release();

        for (int i = 0; i < faceArray.length; i++) {
            Rect face = faceArray[i];
            if (i > 0) {
                continue;
            }

            FaceRecognitionEngine.RecognitionResult result = recognitionEngine.recognize(frame, face);
            Rect phoneRect = findPhoneProxyRect(frame, face);
            if (phoneRect != null) {
                handleProxyResult(result, "Phone");
                continue;
            }

            if (result.recognized) {
                unknownCounter = 0;
                phoneProxyCounter = 0;
                lastPhoneProxyIdentity = "";
                LivenessResult liveness = updateLiveness(frame, face, identityKey(result));
                if (!liveness.liveEnough) {
                    handleLivenessPending(result, liveness);
                    continue;
                }
                handleRecognizedResult(result);
            } else {
                resetLivenessState();
                handleUnrecognizedResult(result);
            }
        }

        if (faceArray.length == 0) {
            clearRecognitionState();
            statusLabel.setText("Camera running - no face detected.");
        }

        webcamLabel.setIcon(new ImageIcon(matToBufferedImage(frame)));
        frame.release();
    }

    private void handleUnrecognizedResult(FaceRecognitionEngine.RecognitionResult result) {
        recognitionCounter = 0;
        lastRecognizedName = "";
        phoneProxyCounter = 0;

        if ("Not Trained".equals(result.name)) {
            unknownCounter = 0;
            statusLabel.setText("Model not trained. Click Train Model first.");
            return;
        }

        unknownCounter++;
        String message = "Unknown: face not in training data [" + unknownCounter + "/"
            + UNKNOWN_STABILITY_THRESHOLD + "]";
        statusLabel.setText(message);

        long now = System.currentTimeMillis();
        if (unknownCounter >= UNKNOWN_STABILITY_THRESHOLD
                && now - lastUnknownMarkTime > UNKNOWN_COOLDOWN_MS) {
            markUnknownFace();
            lastUnknownMarkTime = now;
            unknownCounter = UNKNOWN_STABILITY_THRESHOLD - 2;
        }
    }

    private void handleProxyResult(FaceRecognitionEngine.RecognitionResult result, String reason) {
        recognitionCounter = 0;
        unknownCounter = 0;
        lastRecognizedName = "";
        resetLivenessState();

        String identityKey = proxyIdentityKey(result);
        if (identityKey.equals(lastPhoneProxyIdentity)) {
            phoneProxyCounter++;
        } else {
            lastPhoneProxyIdentity = identityKey;
            phoneProxyCounter = 1;
        }

        String personLabel = canUseProxyIdentity(result) ? result.name : "Unknown";
        statusLabel.setText("Proxy detected (" + reason + ") for " + personLabel + " ["
            + phoneProxyCounter + "/" + PHONE_PROXY_STABILITY_THRESHOLD + "]");

        long now = System.currentTimeMillis();
        if (phoneProxyCounter >= PHONE_PROXY_STABILITY_THRESHOLD
                && now - lastPhoneProxyMarkTime > PHONE_PROXY_COOLDOWN_MS) {
            if (canUseProxyIdentity(result)) {
                markAttendance(result, "Proxy");
            } else {
                markUnknownPhoneProxy();
            }
            lastPhoneProxyMarkTime = now;
            phoneProxyCounter = PHONE_PROXY_STABILITY_THRESHOLD - 1;
        }
    }

    private void handleLivenessPending(FaceRecognitionEngine.RecognitionResult result, LivenessResult liveness) {
        recognitionCounter = 0;
        phoneProxyCounter = 0;
        lastPhoneProxyIdentity = "";

        if (liveness.samples >= LIVENESS_MIN_SAMPLES) {
            livenessFailCounter++;
        }

        statusLabel.setText("Liveness check for " + result.name
            + ": blink or move slightly [" + liveness.motionFrames + "/"
            + LIVENESS_REQUIRED_MOTION_FRAMES + "]");

        long now = System.currentTimeMillis();
        if (livenessFailCounter >= LIVENESS_PROXY_STABILITY_THRESHOLD
                && now - lastPhoneProxyMarkTime > PHONE_PROXY_COOLDOWN_MS) {
            handleProxyResult(result, "No liveness");
            livenessFailCounter = LIVENESS_PROXY_STABILITY_THRESHOLD - 3;
        }
    }

    private void markUnknownPhoneProxy() {
        dbExecutor.execute(() -> {
            boolean marked = dbHelper.markUnknownAttendance("Proxy");
            dbHelper.clearAttendanceOlderThanMinutes(DATA_RETENTION_MINUTES);
            List<Attendance> latestRows = dbHelper.getTodayAttendance();
            SwingUtilities.invokeLater(() -> {
                updateAttendanceTable(latestRows);
                statusLabel.setText(marked
                    ? "Proxy marked: phone/photo detected."
                    : "Could not mark phone/photo proxy.");
            });
        });
    }

    private String proxyIdentityKey(FaceRecognitionEngine.RecognitionResult result) {
        if (!canUseProxyIdentity(result)) {
            return "Unknown";
        }
        return identityKey(result);
    }

    private boolean canUseProxyIdentity(FaceRecognitionEngine.RecognitionResult result) {
        return result != null
            && !"Unknown".equals(result.name)
            && !"Not Trained".equals(result.name)
            && result.confidence >= PHONE_PROXY_IDENTITY_THRESHOLD;
    }

    private String identityKey(FaceRecognitionEngine.RecognitionResult result) {
        return result.sapId == null || result.sapId.equals("N/A") ? result.name : result.sapId;
    }

    private void markUnknownFace() {
        dbExecutor.execute(() -> {
            boolean marked = dbHelper.markUnknownAttendance("Unknown");
            dbHelper.clearAttendanceOlderThanMinutes(DATA_RETENTION_MINUTES);
            List<Attendance> latestRows = dbHelper.getTodayAttendance();
            SwingUtilities.invokeLater(() -> {
                updateAttendanceTable(latestRows);
                statusLabel.setText(marked
                    ? "Unknown marked: face not in training data."
                    : "Could not mark unknown face.");
            });
        });
    }

    private void handleRecognizedResult(FaceRecognitionEngine.RecognitionResult result) {
        String identityKey = identityKey(result);

        if (identityKey.equals(lastRecognizedName)) {
            recognitionCounter++;
        } else {
            lastRecognizedName = identityKey;
            recognitionCounter = 1;
        }

        if (recognitionCounter < STABILITY_THRESHOLD) {
            statusLabel.setText("Verifying " + result.name + " [" + recognitionCounter + "/"
                + STABILITY_THRESHOLD + "]");
            return;
        }

        long timeSinceLastMark = System.currentTimeMillis() - lastMarkTime;
        if (!identityKey.equals(lastMarkedSapId) || timeSinceLastMark > COOLDOWN_MS) {
            statusLabel.setText("Marking " + result.name + "...");
            markAttendance(result, "Present");
            lastMarkedSapId = identityKey;
            lastMarkTime = System.currentTimeMillis();
            recognitionCounter = STABILITY_THRESHOLD - 2;
        } else {
            statusLabel.setText(result.name + " already marked.");
        }
    }

    private void markAttendance(FaceRecognitionEngine.RecognitionResult result, String status) {
        dbExecutor.execute(() -> {
            Student student = dbHelper.getOrAddStudent(result.name, result.sapId, result.className, "");
            if (student == null) {
                SwingUtilities.invokeLater(() -> statusLabel.setText("Could not save student " + result.name));
                return;
            }

            boolean marked = dbHelper.markAttendance(student, status);
            dbHelper.clearAttendanceOlderThanMinutes(DATA_RETENTION_MINUTES);
            List<Attendance> latestRows = dbHelper.getTodayAttendance();
            SwingUtilities.invokeLater(() -> {
                updateAttendanceTable(latestRows);
                if ("Proxy".equals(status)) {
                    statusLabel.setText(marked
                        ? "Proxy marked: " + result.name + " | SAP: " + result.sapId
                        : result.name + " proxy record already exists.");
                } else {
                    statusLabel.setText(marked
                        ? "Marked present: " + result.name
                        : result.name + " already marked present today.");
                }
            });
        });
    }

    private Rect findPhoneProxyRect(Mat frame, Rect face) {
        Mat gray = new Mat();
        Mat blurred = new Mat();
        Mat edges = new Mat();
        Mat closedEdges = new Mat();
        Mat hierarchy = new Mat();
        List<MatOfPoint> contours = new ArrayList<>();

        Imgproc.cvtColor(frame, gray, Imgproc.COLOR_BGR2GRAY);
        Imgproc.equalizeHist(gray, gray);
        Imgproc.GaussianBlur(gray, blurred, new Size(5, 5), 0);
        Imgproc.Canny(blurred, edges, 40, 140);
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(5, 5));
        Imgproc.morphologyEx(edges, closedEdges, Imgproc.MORPH_CLOSE, kernel);
        Imgproc.findContours(closedEdges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE);

        Point faceCenter = new Point(face.x + face.width / 2.0, face.y + face.height / 2.0);
        Rect bestRect = null;
        double bestScore = 0.0;
        double frameArea = frame.width() * frame.height();

        for (MatOfPoint contour : contours) {
            MatOfPoint2f perimeterCurve = new MatOfPoint2f(contour.toArray());
            double perimeter = Imgproc.arcLength(perimeterCurve, true);
            perimeterCurve.release();

            if (perimeter < 120) {
                contour.release();
                continue;
            }

            MatOfPoint2f curve = new MatOfPoint2f(contour.toArray());
            MatOfPoint2f approx = new MatOfPoint2f();
            Imgproc.approxPolyDP(curve, approx, perimeter * 0.03, true);
            MatOfPoint approxPoints = new MatOfPoint(approx.toArray());
            Rect rect = Imgproc.boundingRect(approxPoints);

            double contourArea = Math.abs(Imgproc.contourArea(contour));
            double rectArea = rect.area();
            double rectangularity = rectArea > 0.0 ? contourArea / rectArea : 0.0;
            double aspect = rect.width / (double) Math.max(1, rect.height);
            boolean phoneAspect = (aspect >= 0.30 && aspect <= 0.95) || (aspect >= 1.05 && aspect <= 3.20);
            boolean containsFaceCenter = faceCenter.x >= rect.x && faceCenter.x <= rect.x + rect.width
                && faceCenter.y >= rect.y && faceCenter.y <= rect.y + rect.height;
            boolean overlapsFace = intersectionArea(rect, face) >= face.area() * 0.72;
            boolean surroundsFace = rect.width >= face.width * 1.01 && rect.height >= face.height * 1.01;
            boolean usefulSize = rectArea >= face.area() * 1.02 && rectArea <= frameArea * 0.82;
            double score = rectangularity + (rectArea / Math.max(1.0, frameArea));

            approxPoints.release();
            curve.release();
            approx.release();
            contour.release();

            if (phoneAspect && containsFaceCenter && overlapsFace && surroundsFace && usefulSize
                    && rectangularity >= 0.30 && score > bestScore) {
                bestRect = rect;
                bestScore = score;
            }
        }

        if (bestRect == null && hasPhoneLikeStraightEdges(gray, edges, face)) {
            bestRect = expandRect(face, frame.width(), frame.height(), 0.55, 0.90);
        }

        gray.release();
        blurred.release();
        edges.release();
        closedEdges.release();
        kernel.release();
        hierarchy.release();

        return bestRect;
    }

    private boolean hasPhoneLikeStraightEdges(Mat gray, Mat edges, Rect face) {
        Rect searchRect = expandRect(face, gray.width(), gray.height(), 0.75, 1.05);
        Mat searchEdges = edges.submat(searchRect);
        Mat lines = new Mat();

        Imgproc.HoughLinesP(
            searchEdges,
            lines,
            1,
            Math.PI / 180,
            35,
            Math.max(45, Math.min(searchRect.width, searchRect.height) * 0.34),
            12
        );

        int verticalLines = 0;
        int horizontalLines = 0;
        int longLines = 0;
        int sideVerticalLines = 0;
        int sideHorizontalLines = 0;

        for (int i = 0; i < lines.rows(); i++) {
            double[] line = lines.get(i, 0);
            if (line == null || line.length < 4) {
                continue;
            }

            double dx = line[2] - line[0];
            double dy = line[3] - line[1];
            double length = Math.sqrt(dx * dx + dy * dy);
            if (length < Math.min(searchRect.width, searchRect.height) * 0.28) {
                continue;
            }

            longLines++;
            double angle = Math.abs(Math.toDegrees(Math.atan2(dy, dx)));
            double midX = (line[0] + line[2]) / 2.0;
            double midY = (line[1] + line[3]) / 2.0;
            if (angle < 15 || angle > 165) {
                horizontalLines++;
                if (midY <= searchRect.height * 0.28 || midY >= searchRect.height * 0.72) {
                    sideHorizontalLines++;
                }
            } else if (angle > 75 && angle < 105) {
                verticalLines++;
                if (midX <= searchRect.width * 0.28 || midX >= searchRect.width * 0.72) {
                    sideVerticalLines++;
                }
            }
        }

        lines.release();
        searchEdges.release();

        return longLines >= 3
            && verticalLines >= 1
            && horizontalLines >= 1
            && (sideVerticalLines >= 1 || sideHorizontalLines >= 1);
    }

    private Rect expandRect(Rect rect, int maxWidth, int maxHeight, double xPaddingRatio, double yPaddingRatio) {
        int padX = (int) Math.round(rect.width * xPaddingRatio);
        int padY = (int) Math.round(rect.height * yPaddingRatio);
        int x = Math.max(0, rect.x - padX);
        int y = Math.max(0, rect.y - padY);
        int right = Math.min(maxWidth, rect.x + rect.width + padX);
        int bottom = Math.min(maxHeight, rect.y + rect.height + padY);
        return new Rect(x, y, Math.max(1, right - x), Math.max(1, bottom - y));
    }

    private double intersectionArea(Rect a, Rect b) {
        int left = Math.max(a.x, b.x);
        int top = Math.max(a.y, b.y);
        int right = Math.min(a.x + a.width, b.x + b.width);
        int bottom = Math.min(a.y + a.height, b.y + b.height);
        return Math.max(0, right - left) * Math.max(0, bottom - top);
    }

    private LivenessResult updateLiveness(Mat frame, Rect face, String identityKey) {
        if (!identityKey.equals(livenessIdentity)) {
            resetLivenessState();
            livenessFailCounter = 0;
            livenessIdentity = identityKey;
        }

        Mat currentFace = normalizedFacePatch(frame, face);
        double edgeDensity = faceEdgeDensity(currentFace);

        livenessSamples++;
        if (previousLivenessFace != null) {
            Mat diff = new Mat();
            Core.absdiff(currentFace, previousLivenessFace, diff);
            Scalar meanDiff = Core.mean(diff);
            double frameChange = meanDiff.val[0];
            diff.release();

            if (frameChange >= LIVENESS_MIN_FRAME_CHANGE
                    && frameChange <= LIVENESS_MAX_FRAME_CHANGE
                    && edgeDensity >= LIVENESS_MIN_EDGE_DENSITY) {
                livenessMotionFrames++;
                livenessFailCounter = 0;
            }
        }

        if (previousLivenessFace != null) {
            previousLivenessFace.release();
        }
        previousLivenessFace = currentFace;

        boolean hasEnoughSamples = livenessSamples >= LIVENESS_MIN_SAMPLES;
        boolean liveEnough = hasEnoughSamples
            && livenessMotionFrames >= LIVENESS_REQUIRED_MOTION_FRAMES;
        return new LivenessResult(livenessSamples, livenessMotionFrames, liveEnough);
    }

    private Mat normalizedFacePatch(Mat frame, Rect face) {
        Mat gray = new Mat();
        if (frame.channels() == 1) {
            frame.copyTo(gray);
        } else {
            Imgproc.cvtColor(frame, gray, Imgproc.COLOR_BGR2GRAY);
        }

        Rect patchRect = expandRect(face, frame.width(), frame.height(), 0.10, 0.12);
        Mat facePatch = gray.submat(patchRect);
        Mat resized = new Mat();
        Imgproc.resize(facePatch, resized, new Size(80, 80));
        Imgproc.equalizeHist(resized, resized);
        facePatch.release();
        gray.release();
        return resized;
    }

    private double faceEdgeDensity(Mat normalizedFace) {
        Mat edges = new Mat();
        Imgproc.Canny(normalizedFace, edges, 40, 120);
        double density = Core.countNonZero(edges)
            / Math.max(1.0, normalizedFace.width() * normalizedFace.height());
        edges.release();
        return density;
    }

    private void stopCamera() {
        isRunning = false;
        if (webcamTimer != null) {
            webcamTimer.stop();
        }
        if (clearDataTimer != null) {
            clearDataTimer.stop();
        }
        if (camera != null) {
            camera.release();
        }
        if (startBtn != null) {
            startBtn.setEnabled(true);
        }
        if (stopBtn != null) {
            stopBtn.setEnabled(false);
        }
        if (statusLabel != null) {
            statusLabel.setText("Camera stopped.");
        }
        if (webcamLabel != null) {
            webcamLabel.setIcon(null);
        }
    }

    private void clearRecognitionState() {
        recognitionCounter = 0;
        unknownCounter = 0;
        phoneProxyCounter = 0;
        lastRecognizedName = "";
        lastPhoneProxyIdentity = "";
        livenessFailCounter = 0;
        resetLivenessState();
    }

    private void resetLivenessState() {
        livenessIdentity = "";
        livenessSamples = 0;
        livenessMotionFrames = 0;
        if (previousLivenessFace != null) {
            previousLivenessFace.release();
            previousLivenessFace = null;
        }
    }

    private void trainModel() {
        statusLabel.setText("Training model...");
        trainBtn.setEnabled(false);

        Thread trainThread = new Thread(() -> {
            try {
                String actualPath = resolveStudentsPath();
                if (actualPath == null) {
                    SwingUtilities.invokeLater(() -> {
                        statusLabel.setText("Students folder missing.");
                        trainBtn.setEnabled(true);
                        JOptionPane.showMessageDialog(this,
                            "Create a students folder with photos named name_sapid_class.jpg.",
                            "Setup Required", JOptionPane.WARNING_MESSAGE);
                    });
                    return;
                }

                recognitionEngine.trainModel(actualPath);
                SwingUtilities.invokeLater(() -> {
                    int studentCount = recognitionEngine.getStudentNames().size();
                    statusLabel.setText(studentCount == 0
                        ? "No students trained. Add clear face photos."
                        : "Model trained: " + studentCount + " students loaded.");
                    trainBtn.setEnabled(true);
                });
            } catch (Exception e) {
                System.err.println("Training error: " + e.getMessage());
                e.printStackTrace();
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("Training failed. Check console.");
                    trainBtn.setEnabled(true);
                });
            }
        }, "face-training-worker");
        trainThread.setDaemon(true);
        trainThread.start();
    }

    private String resolveStudentsPath() {
        String[] candidates = {
            STUDENTS_FOLDER,
            "FaceAttendance/" + STUDENTS_FOLDER,
            "../" + STUDENTS_FOLDER
        };

        for (String candidate : candidates) {
            java.io.File studentsDir = new java.io.File(candidate);
            if (studentsDir.exists() && studentsDir.isDirectory()) {
                return candidate;
            }
        }
        return null;
    }

    private void exportCSV() {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new java.io.File("attendance_" + java.time.LocalDate.now() + ".csv"));
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            dbHelper.exportToCSV(chooser.getSelectedFile().getAbsolutePath());
            JOptionPane.showMessageDialog(this, "CSV exported.");
        }
    }

    private void loadAttendanceTable() {
        dbExecutor.execute(() -> {
            dbHelper.clearAttendanceOlderThanMinutes(DATA_RETENTION_MINUTES);
            List<Attendance> list = dbHelper.getTodayAttendance();
            SwingUtilities.invokeLater(() -> updateAttendanceTable(list));
        });
    }

    private void updateAttendanceTable(List<Attendance> list) {
        tableModel.setRowCount(0);
        int count = 1;
        for (Attendance attendance : list) {
            tableModel.addRow(new Object[]{
                count++,
                attendance.getStudentName(),
                attendance.getSapId(),
                attendance.getClassName(),
                attendance.getDate(),
                attendance.getTime(),
                attendance.getStatus()
            });
        }
    }

    private BufferedImage matToBufferedImage(Mat mat) {
        int type = mat.channels() == 1 ? BufferedImage.TYPE_BYTE_GRAY : BufferedImage.TYPE_3BYTE_BGR;
        BufferedImage image = new BufferedImage(mat.width(), mat.height(), type);
        byte[] data = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        mat.get(0, 0, data);
        return image;
    }

    private void styleButton(JButton btn, Color color) {
        btn.setBackground(color);
        btn.setForeground(Color.WHITE);
        btn.setFont(new Font("Arial", Font.BOLD, 13));
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btn.setPreferredSize(new Dimension(150, 35));
    }

    private static class LivenessResult {
        final int samples;
        final int motionFrames;
        final boolean liveEnough;

        LivenessResult(int samples, int motionFrames, boolean liveEnough) {
            this.samples = samples;
            this.motionFrames = motionFrames;
            this.liveEnough = liveEnough;
        }
    }
}

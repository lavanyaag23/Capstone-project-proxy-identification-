package com.attendance.recognition;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfRect;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.CascadeClassifier;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * FaceRecognitionEngine
 *
 * Detection: OpenCV Haar Cascade
 * Features : Local Binary Pattern histograms over an 8x8 grid
 * Matching : Per-student multi-image voting with score margin check
 */
public class FaceRecognitionEngine {

    private static final String CASCADE_PATH =
        "FaceAttendance/resources/haarcascades/haarcascade_frontalface_default.xml";

    private static final Size NORMALIZED_FACE_SIZE = new Size(96, 96);
    private static final int GRID_SIZE = 8;
    private static final int LBP_BINS = 256;

    private static final Size TEMPLATE_FACE_SIZE = new Size(32, 32);

    /*
     * Combined scores are in [0, 1]. Multiple consecutive frames are still
     * required by the GUI before attendance is marked, so this can be forgiving
     * enough for webcam lighting and glasses without marking instantly.
     */
    private static final double CONFIDENCE_THRESHOLD = 0.45;
    private static final double MIN_WINNING_MARGIN = 0.008;

    private final CascadeClassifier faceDetector;
    private final List<String> studentNames = new ArrayList<>();
    private final List<String> studentSapIds = new ArrayList<>();
    private final List<String> studentClasses = new ArrayList<>();
    private final List<FaceFeature> studentFeatures = new ArrayList<>();
    private final List<Integer> featureToStudentIndex = new ArrayList<>();

    public FaceRecognitionEngine() {
        String cascadePath = resolveCascadePath();
        faceDetector = new CascadeClassifier(cascadePath);

        if (faceDetector.empty()) {
            System.err.println("Haar cascade not loaded. Tried: " + cascadePath
                + " | CWD: " + System.getProperty("user.dir"));
        } else {
            System.out.println("Haar cascade loaded from: " + cascadePath);
        }
    }

    public MatOfRect detectFaces(Mat frame) {
        Mat gray = toGray(frame);
        Imgproc.equalizeHist(gray, gray);

        MatOfRect faces = new MatOfRect();
        Size minSize = new Size(
            Math.max(50, frame.width() * 0.08),
            Math.max(50, frame.height() * 0.08)
        );
        Size maxSize = new Size(frame.width(), frame.height());

        faceDetector.detectMultiScale(gray, faces, 1.08, 5, 0, minSize, maxSize);
        if (faces.empty()) {
            faceDetector.detectMultiScale(
                gray,
                faces,
                1.05,
                3,
                0,
                new Size(35, 35),
                maxSize
            );
        }
        Rect[] sortedFaces = faces.toArray();
        Arrays.sort(sortedFaces, Comparator.comparingDouble(Rect::area).reversed());
        faces.fromArray(sortedFaces);
        gray.release();
        return faces;
    }

    public synchronized void trainModel(String studentsFolder) {
        studentNames.clear();
        studentSapIds.clear();
        studentClasses.clear();
        studentFeatures.clear();
        featureToStudentIndex.clear();

        File folder = resolveStudentsFolder(studentsFolder);
        File[] files = folder.listFiles((dir, name) ->
            name.toLowerCase(Locale.ROOT).matches(".*\\.(png|jpg|jpeg)")
        );

        if (files == null || files.length == 0) {
            System.err.println("No student photos found in: " + folder.getAbsolutePath());
            return;
        }

        Map<String, Integer> studentMap = new HashMap<>();

        for (File file : files) {
            Mat img = Imgcodecs.imread(file.getAbsolutePath());
            if (img.empty()) {
                System.err.println("Skipping unreadable image: " + file.getName());
                continue;
            }

            MatOfRect detectedFaces = detectFaces(img);
            Rect faceRect = largestFace(detectedFaces.toArray());
            detectedFaces.release();

            if (faceRect == null) {
                System.err.println("No face found in: " + file.getName());
                img.release();
                continue;
            }

            Mat normalizedFace = normalizeFace(img, faceRect);
            FaceFeature features = extractFaceFeature(normalizedFace);
            normalizedFace.release();
            img.release();

            String[] parsed = parseFileName(file.getName());
            String studentKey = (parsed[1].equals("N/A") ? parsed[0] : parsed[1]).toLowerCase(Locale.ROOT);

            int studentIndex = studentMap.computeIfAbsent(studentKey, key -> {
                studentNames.add(parsed[0]);
                studentSapIds.add(parsed[1]);
                studentClasses.add(parsed[2]);
                System.out.println("Student loaded: " + parsed[0] + " | SAP: " + parsed[1]
                    + " | Class: " + parsed[2]);
                return studentNames.size() - 1;
            });

            studentFeatures.add(features);
            featureToStudentIndex.add(studentIndex);
            System.out.println("  image trained: " + file.getName());
        }

        System.out.println("Training done: " + studentNames.size()
            + " students, " + studentFeatures.size() + " images.");
    }

    public synchronized RecognitionResult recognize(Mat frame, Rect faceRect) {
        if (studentFeatures.isEmpty()) {
            return new RecognitionResult("Not Trained", "N/A", "N/A", 0.0, false);
        }

        Mat normalizedFace = normalizeFace(frame, faceRect);
        FaceFeature testFeatures = extractFaceFeature(normalizedFace);
        normalizedFace.release();

        Map<Integer, StudentScore> scoresByStudent = new HashMap<>();
        for (int i = 0; i < studentFeatures.size(); i++) {
            int studentIndex = featureToStudentIndex.get(i);
            double score = compareFeatures(testFeatures, studentFeatures.get(i));
            scoresByStudent.computeIfAbsent(studentIndex, key -> new StudentScore()).add(score);
        }

        List<Map.Entry<Integer, StudentScore>> ranked = new ArrayList<>(scoresByStudent.entrySet());
        ranked.sort(Comparator.comparingDouble((Map.Entry<Integer, StudentScore> e) -> e.getValue().finalScore()).reversed());

        if (ranked.isEmpty()) {
            return new RecognitionResult("Unknown", "N/A", "N/A", 0.0, false);
        }

        int bestStudent = ranked.get(0).getKey();
        double bestScore = ranked.get(0).getValue().finalScore();
        double secondScore = ranked.size() > 1 ? ranked.get(1).getValue().finalScore() : 0.0;
        boolean hasClearMargin = ranked.size() == 1
            || bestScore >= 0.68
            || (bestScore - secondScore) >= MIN_WINNING_MARGIN;
        boolean recognized = bestScore >= CONFIDENCE_THRESHOLD && hasClearMargin;

        if (!recognized) {
            return new RecognitionResult(
                "Unknown",
                "N/A",
                "Not in training data",
                bestScore,
                false
            );
        }

        return new RecognitionResult(
            studentNames.get(bestStudent),
            studentSapIds.get(bestStudent),
            studentClasses.get(bestStudent),
            bestScore,
            true
        );
    }

    public List<String> getStudentNames() {
        return studentNames;
    }

    public List<String> getStudentSapIds() {
        return studentSapIds;
    }

    public List<String> getStudentClasses() {
        return studentClasses;
    }

    private String resolveCascadePath() {
        String[] candidates = {
            CASCADE_PATH,
            "resources/haarcascades/haarcascade_frontalface_default.xml",
            "../resources/haarcascades/haarcascade_frontalface_default.xml"
        };

        for (String candidate : candidates) {
            if (new File(candidate).exists()) {
                return candidate;
            }
        }
        return CASCADE_PATH;
    }

    private File resolveStudentsFolder(String studentsFolder) {
        String[] candidates = {
            studentsFolder,
            "FaceAttendance/students",
            "students",
            "../students"
        };

        for (String candidate : candidates) {
            File folder = new File(candidate);
            if (folder.exists() && folder.isDirectory()) {
                return folder;
            }
        }
        return new File(studentsFolder);
    }

    private Rect largestFace(Rect[] faces) {
        Rect best = null;
        double bestArea = 0;
        for (Rect face : faces) {
            double area = face.area();
            if (area > bestArea) {
                best = face;
                bestArea = area;
            }
        }
        return best;
    }

    private Mat normalizeFace(Mat image, Rect faceRect) {
        Mat gray = toGray(image);
        Rect expanded = expandRect(faceRect, image.width(), image.height(), 0.16);
        Mat face = gray.submat(expanded);
        Mat resized = new Mat();
        Imgproc.resize(face, resized, NORMALIZED_FACE_SIZE);
        Imgproc.equalizeHist(resized, resized);
        Core.normalize(resized, resized, 0, 255, Core.NORM_MINMAX);
        face.release();
        gray.release();
        return resized;
    }

    private Mat toGray(Mat image) {
        Mat gray = new Mat();
        if (image.channels() == 1) {
            image.copyTo(gray);
        } else {
            Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY);
        }
        return gray;
    }

    private Rect expandRect(Rect rect, int maxWidth, int maxHeight, double paddingRatio) {
        int padX = (int) Math.round(rect.width * paddingRatio);
        int padY = (int) Math.round(rect.height * paddingRatio);
        int x = Math.max(0, rect.x - padX);
        int y = Math.max(0, rect.y - padY);
        int right = Math.min(maxWidth, rect.x + rect.width + padX);
        int bottom = Math.min(maxHeight, rect.y + rect.height + padY);
        return new Rect(x, y, Math.max(1, right - x), Math.max(1, bottom - y));
    }

    private String[] parseFileName(String filename) {
        String nameOnly = filename.replaceFirst("[.][^.]+$", "");
        nameOnly = nameOnly.replaceAll("(?i)_v\\d+$", "");

        String[] parts = nameOnly.split("_");
        String name = parts.length > 0 ? parts[0].trim() : nameOnly;
        String sapId = parts.length > 1 ? parts[1].trim() : "N/A";
        String className = "N/A";
        if (parts.length > 2) {
            StringBuilder classBuilder = new StringBuilder(parts[2].trim());
            for (int i = 3; i < parts.length; i++) {
                classBuilder.append('_').append(parts[i].trim());
            }
            className = classBuilder.toString();
        }

        return new String[]{name, sapId, className};
    }

    private FaceFeature extractFaceFeature(Mat face) {
        return new FaceFeature(extractLbpHistogram(face), extractTemplateVector(face));
    }

    private double[] extractLbpHistogram(Mat face) {
        int width = face.width();
        int height = face.height();
        int cellWidth = width / GRID_SIZE;
        int cellHeight = height / GRID_SIZE;
        double[] histogram = new double[GRID_SIZE * GRID_SIZE * LBP_BINS];

        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                int center = pixel(face, x, y);
                int code = 0;
                code |= pixel(face, x - 1, y - 1) >= center ? 1 << 7 : 0;
                code |= pixel(face, x, y - 1) >= center ? 1 << 6 : 0;
                code |= pixel(face, x + 1, y - 1) >= center ? 1 << 5 : 0;
                code |= pixel(face, x + 1, y) >= center ? 1 << 4 : 0;
                code |= pixel(face, x + 1, y + 1) >= center ? 1 << 3 : 0;
                code |= pixel(face, x, y + 1) >= center ? 1 << 2 : 0;
                code |= pixel(face, x - 1, y + 1) >= center ? 1 << 1 : 0;
                code |= pixel(face, x - 1, y) >= center ? 1 : 0;

                int gridX = Math.min(GRID_SIZE - 1, x / cellWidth);
                int gridY = Math.min(GRID_SIZE - 1, y / cellHeight);
                int offset = (gridY * GRID_SIZE + gridX) * LBP_BINS;
                histogram[offset + code]++;
            }
        }

        normalizeCells(histogram);
        return histogram;
    }

    private int pixel(Mat mat, int x, int y) {
        return (int) mat.get(y, x)[0];
    }

    private void normalizeCells(double[] histogram) {
        for (int cell = 0; cell < GRID_SIZE * GRID_SIZE; cell++) {
            int offset = cell * LBP_BINS;
            double sum = 0.0;
            for (int bin = 0; bin < LBP_BINS; bin++) {
                sum += histogram[offset + bin];
            }
            if (sum == 0.0) {
                continue;
            }
            for (int bin = 0; bin < LBP_BINS; bin++) {
                histogram[offset + bin] /= sum;
            }
        }
    }

    private double histogramIntersection(double[] a, double[] b) {
        double intersection = 0.0;
        int length = Math.min(a.length, b.length);
        for (int i = 0; i < length; i++) {
            intersection += Math.min(a[i], b[i]);
        }
        return intersection / (GRID_SIZE * GRID_SIZE);
    }

    private double[] extractTemplateVector(Mat face) {
        Mat small = new Mat();
        Imgproc.resize(face, small, TEMPLATE_FACE_SIZE);

        int length = small.width() * small.height();
        double[] vector = new double[length];
        double sum = 0.0;
        int index = 0;

        for (int y = 0; y < small.height(); y++) {
            for (int x = 0; x < small.width(); x++) {
                double value = small.get(y, x)[0] / 255.0;
                vector[index++] = value;
                sum += value;
            }
        }
        small.release();

        double mean = sum / vector.length;
        double magnitude = 0.0;
        for (int i = 0; i < vector.length; i++) {
            vector[i] -= mean;
            magnitude += vector[i] * vector[i];
        }

        magnitude = Math.sqrt(magnitude);
        if (magnitude > 0.0) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] /= magnitude;
            }
        }

        return vector;
    }

    private double compareFeatures(FaceFeature a, FaceFeature b) {
        double lbpScore = histogramIntersection(a.lbpHistogram, b.lbpHistogram);
        double templateScore = (cosineSimilarity(a.templateVector, b.templateVector) + 1.0) / 2.0;
        return (lbpScore * 0.72) + (templateScore * 0.28);
    }

    private double cosineSimilarity(double[] a, double[] b) {
        double dot = 0.0;
        int length = Math.min(a.length, b.length);
        for (int i = 0; i < length; i++) {
            dot += a[i] * b[i];
        }
        return Math.max(-1.0, Math.min(1.0, dot));
    }

    private static class FaceFeature {
        private final double[] lbpHistogram;
        private final double[] templateVector;

        FaceFeature(double[] lbpHistogram, double[] templateVector) {
            this.lbpHistogram = lbpHistogram;
            this.templateVector = templateVector;
        }
    }

    private static class StudentScore {
        private double best = 0.0;
        private double secondBest = 0.0;

        void add(double score) {
            if (score >= best) {
                secondBest = best;
                best = score;
            } else if (score > secondBest) {
                secondBest = score;
            }
        }

        double finalScore() {
            return secondBest > 0.0 ? (best * 0.75) + (secondBest * 0.25) : best;
        }
    }

    public static class RecognitionResult {
        public final String name;
        public final String sapId;
        public final String className;
        public final double confidence;
        public final boolean recognized;

        public RecognitionResult(String name, String sapId, String className,
                                 double confidence, boolean recognized) {
            this.name = name;
            this.sapId = sapId;
            this.className = className;
            this.confidence = confidence;
            this.recognized = recognized;
        }
    }
}

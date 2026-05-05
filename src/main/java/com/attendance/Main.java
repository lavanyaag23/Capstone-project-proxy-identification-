package com.attendance;

import com.attendance.gui.MainWindow;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.io.File;

public class Main {

    private static final double MINIMUM_CLASS_VERSION = 55.0; // Java 11

    public static void main(String[] args) {
        if (!isJavaRuntimeSupported()) {
            JOptionPane.showMessageDialog(null,
                "This app must run with Java 11 or newer because OpenCV 4.10 was built for Java 11.\n\n"
                    + "Current runtime: Java " + System.getProperty("java.version") + "\n"
                    + "Use C:\\Program Files\\Java\\jdk-25\\bin\\java.exe or run FaceAttendance\\run.bat.",
                "Wrong Java Runtime",
                JOptionPane.ERROR_MESSAGE);
            return;
        }

        if (!loadOpenCv()) {
            JOptionPane.showMessageDialog(null,
                "OpenCV DLL could not be loaded. Check FaceAttendance/lib/opencv_java4100.dll.",
                "Startup Error",
                JOptionPane.ERROR_MESSAGE);
            return;
        }

        SwingUtilities.invokeLater(() -> {
            try {
                MainWindow window = new MainWindow();
                window.setVisible(true);
            } catch (RuntimeException e) {
                JOptionPane.showMessageDialog(null,
                    e.getMessage(),
                    "Startup Error",
                    JOptionPane.ERROR_MESSAGE);
                e.printStackTrace();
            }
        });
    }

    private static boolean isJavaRuntimeSupported() {
        try {
            double classVersion = Double.parseDouble(System.getProperty("java.class.version", "0"));
            return classVersion >= MINIMUM_CLASS_VERSION;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean loadOpenCv() {
        String[] candidates = {
            "FaceAttendance/lib/opencv_java4100.dll",
            "lib/opencv_java4100.dll",
            "../lib/opencv_java4100.dll"
        };

        for (String candidate : candidates) {
            File dll = new File(candidate);
            if (dll.exists()) {
                System.load(dll.getAbsolutePath());
                return true;
            }
        }

        try {
            System.loadLibrary("opencv_java4100");
            return true;
        } catch (UnsatisfiedLinkError error) {
            System.err.println("OpenCV load failed: " + error.getMessage());
            return false;
        }
    }
}

package com.attendance.database;

import com.attendance.model.Attendance;
import com.attendance.model.Student;

import java.io.File;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.DriverPropertyInfo;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * DatabaseHelper
 *
 * SQLite access layer with connection pragmas, useful indexes, and in-memory
 * student caching so the camera loop does not wait on repeated lookups.
 */
public class DatabaseHelper {

    private static final String DB_URL = "jdbc:sqlite:attendance.db";

    private static boolean sqliteDriverLoaded = false;

    private final Map<String, Student> studentBySapId = new ConcurrentHashMap<>();
    private final Map<String, Student> studentByName = new ConcurrentHashMap<>();
    private Connection connection;

    public DatabaseHelper() {
        connect();
        if (connection == null) {
            throw new IllegalStateException(
                "SQLite JDBC driver is not available. Add both SQLite jars from lib to the runtime classpath: "
                    + "sqlite-jdbc-3.53.0.0-without-natives.jar and "
                    + "sqlite-jdbc-3.53.0.0-natives-windows.jar."
            );
        }
        configureConnection();
        createTables();
        loadStudentCache();
    }

    private void connect() {
        try {
            loadSqliteDriver();
            connection = DriverManager.getConnection(DB_URL);
            System.out.println("Database connected: " + new File("attendance.db").getAbsolutePath());
        } catch (Exception e) {
            System.err.println("Database connection failed: " + e.getMessage());
        }
    }

    private static synchronized void loadSqliteDriver() throws Exception {
        if (sqliteDriverLoaded) {
            return;
        }

        try {
            Class.forName("org.sqlite.JDBC");
            sqliteDriverLoaded = true;
            return;
        } catch (ClassNotFoundException e) {
            // VS Code sometimes omits jars from its generated temp classpath.
            // Fall back to loading the two SQLite jars directly from lib/.
        }

        File driverJar = findLibrary("sqlite-jdbc-3.53.0.0-without-natives.jar");
        File nativeJar = findLibrary("sqlite-jdbc-3.53.0.0-natives-windows.jar");
        if (driverJar == null || nativeJar == null) {
            throw new ClassNotFoundException("SQLite JDBC jars were not found in lib.");
        }

        URL[] urls = {
            driverJar.toURI().toURL(),
            nativeJar.toURI().toURL()
        };
        URLClassLoader loader = new URLClassLoader(urls, DatabaseHelper.class.getClassLoader());
        Class<?> driverClass = Class.forName("org.sqlite.JDBC", true, loader);
        Driver driver = (Driver) driverClass.getDeclaredConstructor().newInstance();
        DriverManager.registerDriver(new DriverShim(driver));
        sqliteDriverLoaded = true;
    }

    private static File findLibrary(String fileName) {
        String[] candidates = {
            "lib/" + fileName,
            "FaceAttendance/lib/" + fileName,
            "../lib/" + fileName
        };

        for (String candidate : candidates) {
            File file = new File(candidate);
            if (file.exists()) {
                return file;
            }
        }
        return null;
    }

    private void configureConnection() {
        if (connection == null) {
            return;
        }

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("PRAGMA synchronous=NORMAL");
            stmt.execute("PRAGMA temp_store=MEMORY");
            stmt.execute("PRAGMA busy_timeout=5000");
            stmt.execute("PRAGMA foreign_keys=ON");
        } catch (SQLException e) {
            System.err.println("Database pragma setup warning: " + e.getMessage());
        }
    }

    private void createTables() {
        if (connection == null) {
            return;
        }

        String studentsTable =
            "CREATE TABLE IF NOT EXISTS students ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "name TEXT NOT NULL,"
                + "sap_id TEXT UNIQUE,"
                + "class_name TEXT,"
                + "photo_path TEXT"
                + ")";

        String attendanceTable =
            "CREATE TABLE IF NOT EXISTS attendance ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "student_id INTEGER,"
                + "student_name TEXT,"
                + "sap_id TEXT,"
                + "class_name TEXT,"
                + "date TEXT,"
                + "time TEXT,"
                + "status TEXT,"
                + "FOREIGN KEY (student_id) REFERENCES students(id)"
                + ")";

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(studentsTable);
            stmt.execute(attendanceTable);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_students_name ON students(name)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_attendance_date ON attendance(date)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_attendance_sap_date ON attendance(sap_id, date)");
            stmt.execute(
                "CREATE UNIQUE INDEX IF NOT EXISTS ux_attendance_present_once "
                    + "ON attendance(sap_id, date, status) "
                    + "WHERE status = 'Present' AND sap_id IS NOT NULL"
            );
            System.out.println("Database tables and indexes ready.");
        } catch (SQLException e) {
            System.err.println("Table creation failed: " + e.getMessage());
        }
    }

    private synchronized void loadStudentCache() {
        if (connection == null) {
            return;
        }

        studentBySapId.clear();
        studentByName.clear();

        String sql = "SELECT * FROM students";
        try (Statement stmt = connection.createStatement();
            ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                cacheStudent(toStudent(rs));
            }
        } catch (SQLException e) {
            System.err.println("Student cache load failed: " + e.getMessage());
        }
    }

    public synchronized Student getOrAddStudent(String name, String sapId, String className, String photoPath) {
        String cleanName = clean(name, "Unknown");
        String cleanSapId = clean(sapId, "N/A");
        String cleanClass = clean(className, "N/A");
        String cleanPhotoPath = photoPath == null ? "" : photoPath.trim();

        Student cached = findCachedStudent(cleanName, cleanSapId);
        if (cached != null) {
            return cached;
        }

        String insertSql =
            "INSERT OR IGNORE INTO students (name, sap_id, class_name, photo_path) "
                + "VALUES (?, ?, ?, ?)";

        try (PreparedStatement pstmt = connection.prepareStatement(insertSql)) {
            pstmt.setString(1, cleanName);
            pstmt.setString(2, cleanSapId.equals("N/A") ? null : cleanSapId);
            pstmt.setString(3, cleanClass);
            pstmt.setString(4, cleanPhotoPath);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Add student failed: " + e.getMessage());
            return null;
        }

        Student student = cleanSapId.equals("N/A")
            ? queryStudentByName(cleanName)
            : queryStudentBySapId(cleanSapId);
        if (student != null) {
            cacheStudent(student);
        }
        return student;
    }

    public boolean addStudent(String name, String sapId, String className, String photoPath) {
        return getOrAddStudent(name, sapId, className, photoPath) != null;
    }

    public synchronized Student getStudentBySapId(String sapId) {
        String cleanSapId = clean(sapId, "N/A");
        if (cleanSapId.equals("N/A")) {
            return null;
        }

        Student cached = studentBySapId.get(cacheKey(cleanSapId));
        if (cached != null) {
            return cached;
        }

        Student student = queryStudentBySapId(cleanSapId);
        if (student != null) {
            cacheStudent(student);
        }
        return student;
    }

    public synchronized Student getStudentByName(String name) {
        String cleanName = clean(name, "Unknown");
        Student cached = studentByName.get(cacheKey(cleanName));
        if (cached != null) {
            return cached;
        }

        Student student = queryStudentByName(cleanName);
        if (student != null) {
            cacheStudent(student);
        }
        return student;
    }

    public synchronized boolean markAttendance(Student student, String status) {
        if (student == null || student.getSapId() == null || student.getSapId().trim().isEmpty()) {
            System.err.println("Cannot mark attendance: student data is incomplete.");
            return false;
        }

        String updateSql =
            "UPDATE attendance "
                + "SET student_id = ?, student_name = ?, class_name = ?, "
                + "time = TIME('now', 'localtime'), status = ? "
                + "WHERE sap_id = ? AND date = DATE('now', 'localtime')";

        String insertSql =
            "INSERT INTO attendance "
                + "(student_id, student_name, sap_id, class_name, date, time, status) "
                + "VALUES (?, ?, ?, ?, DATE('now', 'localtime'), TIME('now', 'localtime'), ?)";

        try (PreparedStatement pstmt = connection.prepareStatement(updateSql)) {
            pstmt.setInt(1, student.getId());
            pstmt.setString(2, student.getName());
            pstmt.setString(3, student.getClassName());
            pstmt.setString(4, status);
            pstmt.setString(5, student.getSapId());
            if (pstmt.executeUpdate() > 0) {
                return true;
            }
        } catch (SQLException e) {
            System.err.println("Database update failed: " + e.getMessage());
            return false;
        }

        try (PreparedStatement pstmt = connection.prepareStatement(insertSql)) {
            pstmt.setInt(1, student.getId());
            pstmt.setString(2, student.getName());
            pstmt.setString(3, student.getSapId());
            pstmt.setString(4, student.getClassName());
            pstmt.setString(5, status);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("Database insert failed: " + e.getMessage());
            return false;
        }
    }

    public synchronized boolean markUnknownAttendance(String status) {
        String sql =
            "INSERT INTO attendance "
                + "(student_id, student_name, sap_id, class_name, date, time, status) "
                + "VALUES (NULL, 'Unknown', 'N/A', 'N/A', DATE('now', 'localtime'), "
                + "TIME('now', 'localtime'), ?)";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, status);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("Unknown attendance write failed: " + e.getMessage());
            return false;
        }
    }

    public synchronized List<Attendance> getTodayAttendance() {
        List<Attendance> list = new ArrayList<>();
        String sql = "SELECT * FROM attendance WHERE date = DATE('now', 'localtime') ORDER BY id DESC";

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                Attendance attendance = new Attendance(
                    rs.getInt("student_id"),
                    rs.getString("student_name"),
                    rs.getString("sap_id"),
                    rs.getString("class_name"),
                    rs.getString("date"),
                    rs.getString("time"),
                    rs.getString("status")
                );
                attendance.setId(rs.getInt("id"));
                list.add(attendance);
            }
        } catch (SQLException e) {
            System.err.println("Get attendance failed: " + e.getMessage());
        }
        return list;
    }

    public void exportToCSV(String filePath) {
        List<Attendance> list = getTodayAttendance();
        try (java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.File(filePath))) {
            pw.println("Sr.No,Name,SAP ID,Class,Date,Time,Status");
            int sr = 1;
            for (Attendance attendance : list) {
                pw.println(sr++ + "," + attendance.getStudentName() + "," + attendance.getSapId() + ","
                    + attendance.getClassName() + "," + attendance.getDate() + ","
                    + attendance.getTime() + "," + attendance.getStatus());
            }
        } catch (Exception e) {
            System.err.println("CSV export failed: " + e.getMessage());
        }
    }

    public synchronized void clearTodayAttendance() {
        String sql = "DELETE FROM attendance WHERE date = DATE('now', 'localtime')";
        try (Statement stmt = connection.createStatement()) {
            int deletedRows = stmt.executeUpdate(sql);
            System.out.println("Cleared " + deletedRows + " attendance records from today.");
        } catch (SQLException e) {
            System.err.println("Clear attendance failed: " + e.getMessage());
        }
    }

    public synchronized int clearAllAttendance() {
        String sql = "DELETE FROM attendance";
        try (Statement stmt = connection.createStatement()) {
            int deletedRows = stmt.executeUpdate(sql);
            System.out.println("Reset attendance table. Deleted records: " + deletedRows);
            return deletedRows;
        } catch (SQLException e) {
            System.err.println("Reset attendance failed: " + e.getMessage());
            return 0;
        }
    }

    public synchronized int clearAttendanceOlderThanMinutes(int minutes) {
        String sql =
            "DELETE FROM attendance "
                + "WHERE datetime(date || ' ' || time) <= datetime('now', 'localtime', ?)";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, "-" + minutes + " minutes");
            int deletedRows = pstmt.executeUpdate();
            if (deletedRows > 0) {
                System.out.println("Expired attendance records deleted: " + deletedRows);
            }
            return deletedRows;
        } catch (SQLException e) {
            System.err.println("Expire attendance failed: " + e.getMessage());
            return 0;
        }
    }

    public synchronized void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                System.out.println("Database connection closed.");
            }
        } catch (SQLException e) {
            System.err.println("Database close failed: " + e.getMessage());
        }
    }

    private Student findCachedStudent(String name, String sapId) {
        if (!sapId.equals("N/A")) {
            Student bySap = studentBySapId.get(cacheKey(sapId));
            if (bySap != null) {
                return bySap;
            }
        }
        return studentByName.get(cacheKey(name));
    }

    private Student queryStudentBySapId(String sapId) {
        String sql = "SELECT * FROM students WHERE sap_id = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, sapId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return toStudent(rs);
                }
            }
        } catch (SQLException e) {
            System.err.println("Get student by SAP ID failed: " + e.getMessage());
        }
        return null;
    }

    private Student queryStudentByName(String name) {
        String sql = "SELECT * FROM students WHERE name = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, name);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return toStudent(rs);
                }
            }
        } catch (SQLException e) {
            System.err.println("Get student by name failed: " + e.getMessage());
        }
        return null;
    }

    private Student toStudent(ResultSet rs) throws SQLException {
        return new Student(
            rs.getInt("id"),
            rs.getString("name"),
            rs.getString("sap_id"),
            rs.getString("class_name"),
            rs.getString("photo_path")
        );
    }

    private void cacheStudent(Student student) {
        if (student.getSapId() != null && !student.getSapId().trim().isEmpty()) {
            studentBySapId.put(cacheKey(student.getSapId()), student);
        }
        studentByName.put(cacheKey(student.getName()), student);
    }

    private String clean(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        return value.trim();
    }

    private String cacheKey(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private static class DriverShim implements Driver {
        private final Driver driver;

        DriverShim(Driver driver) {
            this.driver = driver;
        }

        @Override
        public Connection connect(String url, Properties info) throws SQLException {
            return driver.connect(url, info);
        }

        @Override
        public boolean acceptsURL(String url) throws SQLException {
            return driver.acceptsURL(url);
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
            return driver.getPropertyInfo(url, info);
        }

        @Override
        public int getMajorVersion() {
            return driver.getMajorVersion();
        }

        @Override
        public int getMinorVersion() {
            return driver.getMinorVersion();
        }

        @Override
        public boolean jdbcCompliant() {
            return driver.jdbcCompliant();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return driver.getParentLogger();
        }
    }
}

# 🎓 Face Recognition Attendance System
## Java + OpenCV + SQLite + Java Swing

---

## 📁 Project Structure
```
FaceAttendance/
├── src/main/java/com/attendance/
│   ├── Main.java                          ← Entry point (GAURAV)
│   ├── gui/
│   │   └── MainWindow.java               ← Swing GUI (ANJALI)
│   ├── recognition/
│   │   └── FaceRecognitionEngine.java    ← LBPH + Detection (KANAK SHREE)
│   ├── database/
│   │   └── DatabaseHelper.java           ← SQLite JDBC (LAVANYA)
│   └── model/
│       ├── Student.java
│       └── Attendance.java
├── resources/
│   └── haarcascades/
│       └── haarcascade_frontalface_default.xml  ← Download this!
├── students/                              ← Add student photos here
├── trained/                              ← Auto-generated trained model
└── README.md
```

---

## 🔧 Setup Steps

### Step 1 — Install Java JDK 17+
- Download from: https://adoptium.net/

### Step 2 — Download OpenCV for Java
- Download from: https://opencv.org/releases/
- Extract and find `opencv-4xx.jar` and `opencv_java4xx.dll`

### Step 3 — Download SQLite JDBC
- Download from: https://github.com/xerial/sqlite-jdbc/releases
- Get `sqlite-jdbc-x.x.x.jar`

### Step 4 — Download Haar Cascade XML
- Download from: https://github.com/opencv/opencv/tree/master/data/haarcascades
- File: `haarcascade_frontalface_default.xml`
- Put in: `resources/haarcascades/` folder

### Step 5 — VS Code Setup
Install these extensions:
- Extension Pack for Java
- Maven for Java (optional)

### Step 6 — Add JARs to classpath
In VS Code, press `Ctrl+Shift+P` → "Java: Configure Classpath"
Add:
- opencv-4xx.jar
- sqlite-jdbc-x.x.x.jar

### Step 7 — Add Student Photos
- Put photos in `students/` folder
- Name each photo as student's name: `gaurav.jpg`, `lavanya.png`
- Each photo must have ONE clear face

### Step 8 — Run
```
Run Main.java
```
Then click "Train Model" button first, then "Start Camera"

---

## 👥 Team Tasks

| Member | Module | File |
|--------|--------|------|
| GAURAV | Setup + Integration | Main.java |
| KANAK SHREE | Face Recognition | FaceRecognitionEngine.java |
| LAVANYA | Database | DatabaseHelper.java |
| ANJALI | GUI | MainWindow.java |

---

## ⚠️ Important Notes
- Train model FIRST before using camera
- Student photos must have clear frontal face
- Confidence threshold is 80 — lower = stricter matching
- Attendance is saved automatically when face is recognized

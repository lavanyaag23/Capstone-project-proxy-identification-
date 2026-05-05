package com.attendance.model;

public class Attendance {
    private int id;
    private int studentId;
    private String studentName;
    private String sapId;
    private String className;
    private String date;
    private String time;
    private String status; // "Present", "Proxy", or "Absent"

    public Attendance(int studentId, String studentName, String sapId, String className, String date, String time, String status) {
        this.studentId = studentId;
        this.studentName = studentName;
        this.sapId = sapId;
        this.className = className;
        this.date = date;
        this.time = time;
        this.status = status;
    }

    public int getId() { return id; }
    public int getStudentId() { return studentId; }
    public String getStudentName() { return studentName; }
    public String getSapId() { return sapId; }
    public String getClassName() { return className; }
    public String getDate() { return date; }
    public String getTime() { return time; }
    public String getStatus() { return status; }

    public void setId(int id) { this.id = id; }
}

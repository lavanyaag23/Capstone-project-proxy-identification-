package com.attendance.model;

public class Student {
    private int id;
    private String name;
    private String sapId;
    private String className;
    private String photoPath;

    public Student(int id, String name, String sapId, String className, String photoPath) {
        this.id = id;
        this.name = name;
        this.sapId = sapId;
        this.className = className;
        this.photoPath = photoPath;
    }

    // Getters
    public int getId() { return id; }
    public String getName() { return name; }
    public String getSapId() { return sapId; }
    public String getClassName() { return className; }
    public String getPhotoPath() { return photoPath; }

    // Setters
    public void setId(int id) { this.id = id; }
    public void setName(String name) { this.name = name; }
    public void setSapId(String sapId) { this.sapId = sapId; }
    public void setClassName(String className) { this.className = className; }
    public void setPhotoPath(String photoPath) { this.photoPath = photoPath; }

    @Override
    public String toString() {
        return "Student{id=" + id + ", name='" + name + "', sapId='" + sapId + "', class='" + className + "'}";
    }
}
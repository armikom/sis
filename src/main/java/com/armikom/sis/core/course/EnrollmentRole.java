package com.armikom.sis.core.course;

public enum EnrollmentRole {
    OWNER, INSTRUCTOR, ASSISTANT, STUDENT;

    /** OWNER, INSTRUCTOR and ASSISTANT may run the course (sessions, exams, roster, pool review). */
    public boolean isStaff() {
        return this != STUDENT;
    }
}

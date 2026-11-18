package com.armikom.sis.core.course;

/** A student with a live STUDENT enrollment in a course (the course roster). */
public record RosterStudent(long personId, String studentNo, String fullName, String email) {}

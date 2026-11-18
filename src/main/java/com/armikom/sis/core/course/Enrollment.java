package com.armikom.sis.core.course;

import com.armikom.sis.core.person.Person;
import jakarta.persistence.*;

import java.time.Instant;

/** Membership of a person in a course with one role; removal is a soft delete ({@code deleted_at}). */
@Entity
@Table(name = "enrollment")
public class Enrollment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "person_id", nullable = false)
    private Person person;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EnrollmentRole role;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Enrollment() {}

    public Enrollment(Course course, Person person, EnrollmentRole role) {
        this.course = course;
        this.person = person;
        this.role = role;
        this.createdAt = Instant.now();
    }

    public boolean isLive() { return deletedAt == null; }

    public Long getId() { return id; }
    public Course getCourse() { return course; }
    public Person getPerson() { return person; }
    public EnrollmentRole getRole() { return role; }
    public void setRole(EnrollmentRole role) { this.role = role; }
    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }
    public Instant getCreatedAt() { return createdAt; }
}

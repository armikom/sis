package com.armikom.sis.core.person;

import jakarta.persistence.*;

import java.time.Instant;

/** One row per human: student, instructor, assistant or administrator. */
@Entity
@Table(name = "person")
public class Person {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "full_name", nullable = false, length = 200)
    private String fullName;

    @Column(name = "student_no", length = 32)
    private String studentNo;

    /** Always lower-case. */
    @Column(length = 254)
    private String email;

    @Column(length = 64)
    private String username;

    @Column(name = "password_hash", length = 100)
    private String passwordHash;

    @Column(nullable = false, length = 64)
    private String nickname;

    @Column(name = "system_admin", nullable = false)
    private boolean systemAdmin;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    protected Person() {}

    public Person(String fullName, String nickname) {
        this.fullName = fullName;
        this.nickname = nickname;
        this.createdAt = Instant.now();
    }

    /** Active and not soft-deleted: may sign in and be enrolled. */
    public boolean isUsable() {
        return active && deletedAt == null;
    }

    public Long getId() { return id; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public String getStudentNo() { return studentNo; }
    public void setStudentNo(String studentNo) { this.studentNo = studentNo; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public boolean isSystemAdmin() { return systemAdmin; }
    public void setSystemAdmin(boolean systemAdmin) { this.systemAdmin = systemAdmin; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(Instant lastLoginAt) { this.lastLoginAt = lastLoginAt; }
}

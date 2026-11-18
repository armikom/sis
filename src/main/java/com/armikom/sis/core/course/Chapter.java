package com.armikom.sis.core.course;

import jakarta.persistence.*;

/** A chapter (week) of a course. CRUD lives in the pool module; core only maps the table. */
@Entity
@Table(name = "chapter")
public class Chapter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @Column(nullable = false)
    private int number;

    @Column(nullable = false, columnDefinition = "text")
    private String title;

    @Column(name = "doc_en", nullable = false, columnDefinition = "text")
    private String docEn = "";

    @Column(name = "doc_tr", nullable = false, columnDefinition = "text")
    private String docTr = "";

    @Column(nullable = false)
    private boolean open = true;

    protected Chapter() {}

    public Chapter(Course course, int number, String title) {
        this.course = course;
        this.number = number;
        this.title = title;
    }

    public Long getId() { return id; }
    public Course getCourse() { return course; }
    public int getNumber() { return number; }
    public void setNumber(int number) { this.number = number; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDocEn() { return docEn; }
    public void setDocEn(String docEn) { this.docEn = docEn; }
    public String getDocTr() { return docTr; }
    public void setDocTr(String docTr) { this.docTr = docTr; }
    public boolean isOpen() { return open; }
    public void setOpen(boolean open) { this.open = open; }
}

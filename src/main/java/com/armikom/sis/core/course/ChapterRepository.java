package com.armikom.sis.core.course;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChapterRepository extends JpaRepository<Chapter, Long> {
    List<Chapter> findByCourse_IdOrderByNumberAsc(long courseId);
    boolean existsByCourse_Id(long courseId);
}

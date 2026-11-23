package com.armikom.sis.exam;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ExamQuestionGroupRepository extends JpaRepository<ExamQuestionGroup, Long> {

    @Query("select g from ExamQuestionGroup g where g.exam.id = :examId order by g.position, g.id")
    List<ExamQuestionGroup> findByExamId(@Param("examId") long examId);

    @Query("select g from ExamQuestionGroup g where g.exam.id in :examIds order by g.exam.id, g.position, g.id")
    List<ExamQuestionGroup> findByExamIds(@Param("examIds") Collection<Long> examIds);
}

package com.armikom.sis.core.course;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CourseRepository extends JpaRepository<Course, Long> {
    List<Course> findAllByOrderByTermDescCodeAsc();
    boolean existsByCodeAndTerm(String code, String term);
    boolean existsByCodeAndTermAndIdNot(String code, String term, long id);

    /** Courses where the person has a live enrollment (any role). */
    @Query("""
            select c from Enrollment e join e.course c
            where e.person.id = :personId and e.deletedAt is null
            order by c.term desc, c.code asc""")
    List<Course> findLiveForPerson(@Param("personId") long personId);
}

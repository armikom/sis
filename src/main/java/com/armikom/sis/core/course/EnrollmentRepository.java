package com.armikom.sis.core.course;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EnrollmentRepository extends JpaRepository<Enrollment, Long> {

    Optional<Enrollment> findByCourse_IdAndPerson_Id(long courseId, long personId);

    @Query("""
            select e from Enrollment e
            where e.course.id = :courseId and e.person.id = :personId and e.deletedAt is null""")
    Optional<Enrollment> findLive(@Param("courseId") long courseId, @Param("personId") long personId);

    /** Live enrollments of a person with their courses (for /api/auth/me and course lists). */
    @Query("""
            select e from Enrollment e join fetch e.course c
            where e.person.id = :personId and e.deletedAt is null
            order by c.term desc, c.code asc""")
    List<Enrollment> findLiveByPerson(@Param("personId") long personId);

    /** Live enrollments of a course in the given roles, people fetched. */
    @Query("""
            select e from Enrollment e join fetch e.person p
            where e.course.id = :courseId and e.deletedAt is null and e.role in :roles
            order by p.fullName, p.id""")
    List<Enrollment> findLiveByCourseAndRoles(@Param("courseId") long courseId,
                                              @Param("roles") Collection<EnrollmentRole> roles);

    /** The roster: live STUDENT enrollments of usable people, ordered by student number. */
    @Query("""
            select new com.armikom.sis.core.course.RosterStudent(p.id, p.studentNo, p.fullName, p.email)
            from Enrollment e join e.person p
            where e.course.id = :courseId and e.deletedAt is null and e.role = com.armikom.sis.core.course.EnrollmentRole.STUDENT
              and p.active = true and p.deletedAt is null
            order by p.studentNo, p.fullName""")
    List<RosterStudent> roster(@Param("courseId") long courseId);

    /** One roster student by student number, if the number has a live STUDENT enrollment in the course. */
    @Query("""
            select new com.armikom.sis.core.course.RosterStudent(p.id, p.studentNo, p.fullName, p.email)
            from Enrollment e join e.person p
            where e.course.id = :courseId and p.studentNo = :studentNo and e.deletedAt is null
              and e.role = com.armikom.sis.core.course.EnrollmentRole.STUDENT
              and p.active = true and p.deletedAt is null""")
    Optional<RosterStudent> rosterStudent(@Param("courseId") long courseId, @Param("studentNo") String studentNo);

    @Query("""
            select count(e) from Enrollment e join e.person p
            where e.course.id = :courseId and e.deletedAt is null and e.role = com.armikom.sis.core.course.EnrollmentRole.STUDENT
              and p.active = true and p.deletedAt is null""")
    long rosterCount(@Param("courseId") long courseId);

    /** Ids of courses where the person is live staff (OWNER, INSTRUCTOR, ASSISTANT). */
    @Query("""
            select e.course.id from Enrollment e
            where e.person.id = :personId and e.deletedAt is null
              and e.role <> com.armikom.sis.core.course.EnrollmentRole.STUDENT""")
    List<Long> staffCourseIds(@Param("personId") long personId);

    @Query("""
            select count(e) > 0 from Enrollment e
            where e.person.id = :personId and e.deletedAt is null""")
    boolean hasLiveEnrollment(@Param("personId") long personId);

    @Modifying
    @Query("update Enrollment e set e.deletedAt = :now where e.person.id = :personId and e.deletedAt is null")
    int softDeleteAllOfPerson(@Param("personId") long personId, @Param("now") Instant now);
}

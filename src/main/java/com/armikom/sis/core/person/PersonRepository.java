package com.armikom.sis.core.person;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PersonRepository extends JpaRepository<Person, Long> {
    Optional<Person> findByUsername(String username);
    Optional<Person> findByEmail(String email);
    Optional<Person> findByStudentNo(String studentNo);
    boolean existsByUsername(String username);
    boolean existsByNickname(String nickname);

    /** Case-insensitive search over name, e-mail, student number and username; deleted people included. */
    @Query("""
            select p from Person p
            where (:q = '' or lower(p.fullName) like concat('%', :q, '%')
                   or lower(coalesce(p.email, '')) like concat('%', :q, '%')
                   or coalesce(p.studentNo, '') like concat('%', :q, '%')
                   or lower(coalesce(p.username, '')) like concat('%', :q, '%'))
            order by p.fullName, p.id""")
    List<Person> search(@Param("q") String q, org.springframework.data.domain.Pageable page);
}

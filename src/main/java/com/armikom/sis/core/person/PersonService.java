package com.armikom.sis.core.person;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Creates people with a unique nickname (ported from question-pool's AuthService): students get a
 * random "CalmOtter42", staff get "Instructor", "Instructor2", ...
 */
@Service
public class PersonService {

    private static final SecureRandom RNG = new SecureRandom();
    private static final String[] ADJECTIVES = {"Brave", "Calm", "Clever", "Curious", "Eager", "Gentle", "Happy",
            "Honest", "Kind", "Lucky", "Patient", "Quick", "Quiet", "Sharp", "Steady", "Sunny", "Swift", "Wise",
            "Bold", "Bright"};
    private static final String[] ANIMALS = {"Otter", "Falcon", "Panda", "Fox", "Heron", "Lynx", "Dolphin", "Owl",
            "Tiger", "Koala", "Badger", "Crane", "Gecko", "Hawk", "Ibex", "Robin", "Seal", "Wolf", "Bee", "Turtle"};
    public static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

    private final PersonRepository repo;

    public PersonService(PersonRepository repo) {
        this.repo = repo;
    }

    /** Trimmed, lower-case e-mail; null when blank. */
    public static String normalizeEmail(String email) {
        if (email == null) return null;
        String e = email.trim().toLowerCase(Locale.ROOT);
        return e.isEmpty() ? null : e;
    }

    public static boolean isEmail(String s) {
        return s != null && EMAIL.matcher(s.trim()).matches();
    }

    @Transactional
    public Person createStudent(String fullName, String studentNo, String email) {
        Person p = new Person(fullName, newNickname());
        p.setStudentNo(studentNo);
        p.setEmail(normalizeEmail(email));
        return repo.save(p);
    }

    @Transactional
    public Person createStaff(String fullName, String email) {
        Person p = new Person(fullName, newStaffNickname());
        p.setEmail(normalizeEmail(email));
        return repo.save(p);
    }

    /** A random name such as "CalmOtter42" that other students see instead of the real name. */
    public String newNickname() {
        for (int i = 0; i < 200; i++) {
            String nick = ADJECTIVES[RNG.nextInt(ADJECTIVES.length)] + ANIMALS[RNG.nextInt(ANIMALS.length)]
                    + (10 + RNG.nextInt(90));
            if (!repo.existsByNickname(nick)) return nick;
        }
        return "Student" + Long.toHexString(RNG.nextLong());
    }

    /** A nickname that is still free: Instructor, Instructor2, Instructor3, ... */
    public String newStaffNickname() {
        String nick = "Instructor";
        for (int n = 2; n < 200; n++) {
            if (!repo.existsByNickname(nick)) return nick;
            nick = "Instructor" + n;
        }
        return newNickname();
    }
}

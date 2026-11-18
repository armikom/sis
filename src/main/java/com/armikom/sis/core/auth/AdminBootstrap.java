package com.armikom.sis.core.auth;

import com.armikom.sis.config.AppProperties;
import com.armikom.sis.core.person.Person;
import com.armikom.sis.core.person.PersonRepository;
import com.armikom.sis.core.person.PersonService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Creates a system-admin person from APP_ADMIN_USERNAME / APP_ADMIN_PASSWORD when nobody has that username. */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final PersonRepository repo;
    private final PersonService personService;
    private final PasswordEncoder encoder;
    private final AppProperties props;

    public AdminBootstrap(PersonRepository repo, PersonService personService, PasswordEncoder encoder,
                          AppProperties props) {
        this.repo = repo;
        this.personService = personService;
        this.encoder = encoder;
        this.props = props;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String username = props.admin() != null ? props.admin().username() : null;
        String password = props.admin() != null ? props.admin().password() : null;
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            if (repo.count() == 0) {
                log.warn("No people exist and no admin credentials are configured. "
                        + "Set APP_ADMIN_USERNAME and APP_ADMIN_PASSWORD to create a system administrator.");
            }
            return;
        }
        if (repo.existsByUsername(username.trim())) return;
        Person admin = new Person("Yönetici", personService.newStaffNickname());
        admin.setUsername(username.trim());
        admin.setPasswordHash(encoder.encode(password));
        admin.setSystemAdmin(true);
        repo.save(admin);
        log.info("Created system administrator: {}", username.trim());
    }
}

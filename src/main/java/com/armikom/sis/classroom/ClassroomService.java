package com.armikom.sis.classroom;

import com.armikom.sis.common.ApiException;
import com.armikom.sis.common.ErrorCode;
import com.armikom.sis.core.auth.CourseAccess;
import com.armikom.sis.core.auth.SisPrincipal;
import com.armikom.sis.core.person.PersonRepository;
import com.armikom.sis.session.ClassSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Service
public class ClassroomService {

    private final ClassroomRepository repo;
    private final ClassSessionRepository sessionRepo;
    private final CourseAccess access;
    private final PersonRepository personRepo;
    private final SecureRandom random = new SecureRandom();

    public ClassroomService(ClassroomRepository repo, ClassSessionRepository sessionRepo,
                            CourseAccess access, PersonRepository personRepo) {
        this.repo = repo;
        this.sessionRepo = sessionRepo;
        this.access = access;
        this.personRepo = personRepo;
    }

    public record ClassroomData(String name, Double latitude, Double longitude, Integer radiusM, String bleBeaconId) {}

    @Transactional(readOnly = true)
    public List<Classroom> list() {
        SisPrincipal me = access.currentPerson();
        if (me.systemAdmin()) return repo.findAllByOrderByNameAsc();
        return repo.findByOwner_IdOrderByNameAsc(me.personId());
    }

    /** A classroom I own (system admins: any). */
    @Transactional(readOnly = true)
    public Classroom get(long id) {
        Classroom c = repo.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        requireOwner(c);
        return c;
    }

    void requireOwner(Classroom c) {
        SisPrincipal me = access.currentPerson();
        if (!me.systemAdmin() && c.getOwnerId() != me.personId()) throw new ApiException(ErrorCode.FORBIDDEN);
    }

    @Transactional
    public Classroom create(ClassroomData data) {
        access.requireStaffAnywhere();
        Classroom c = new Classroom(personRepo.getReferenceById(access.currentPerson().personId()), data.name().trim());
        apply(c, data);
        return repo.save(c);
    }

    @Transactional
    public Classroom update(long id, ClassroomData data) {
        Classroom c = get(id);
        c.setName(data.name().trim());
        apply(c, data);
        return repo.save(c);
    }

    private static void apply(Classroom c, ClassroomData d) {
        boolean anyGeo = d.latitude() != null || d.longitude() != null || d.radiusM() != null;
        boolean allGeo = d.latitude() != null && d.longitude() != null && d.radiusM() != null;
        if (anyGeo && !allGeo) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    Map.of("latitude", "Enlem, boylam ve yarıçap birlikte girilmeli"));
        }
        String beaconId = d.bleBeaconId() != null && !d.bleBeaconId().isBlank() ? d.bleBeaconId().trim() : null;
        c.setLatitude(d.latitude());
        c.setLongitude(d.longitude());
        c.setRadiusM(d.radiusM());
        c.setBleBeaconId(beaconId);
    }

    @Transactional
    public void delete(long id) {
        Classroom c = get(id);
        if (sessionRepo.existsByClassroomId(id)) {
            throw new ApiException(ErrorCode.CONFLICT);
        }
        repo.delete(c);
    }

    @Transactional
    public Classroom generateBleKey(long id) {
        Classroom c = get(id);
        byte[] key = new byte[32];
        random.nextBytes(key);
        c.setBleKeyHex(HexFormat.of().formatHex(key));
        return repo.save(c);
    }
}

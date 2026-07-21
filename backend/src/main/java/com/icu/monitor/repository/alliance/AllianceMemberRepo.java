package com.icu.monitor.repository.alliance;

import com.icu.monitor.domain.alliance.AllianceMember;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AllianceMemberRepo extends JpaRepository<AllianceMember, Long> {
    List<AllianceMember> findByAllianceId(Long allianceId);
    List<AllianceMember> findByHospitalId(Long hospitalId);
}

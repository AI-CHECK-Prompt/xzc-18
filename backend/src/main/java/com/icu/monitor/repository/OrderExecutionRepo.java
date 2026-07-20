package com.icu.monitor.repository;

import com.icu.monitor.domain.OrderExecution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.OffsetDateTime;
import java.util.List;

public interface OrderExecutionRepo extends JpaRepository<OrderExecution, Long> {
    @Query("select o from OrderExecution o where o.bedId=?1 and o.time between ?2 and ?3 order by o.time")
    List<OrderExecution> findInRange(Long bedId, OffsetDateTime from, OffsetDateTime to);
}

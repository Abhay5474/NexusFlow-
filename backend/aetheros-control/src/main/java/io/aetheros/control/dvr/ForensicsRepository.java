package io.aetheros.control.dvr;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ForensicsRepository extends JpaRepository<ForensicsRecord, Long> {

    @Query("select r from ForensicsRecord r where r.ts between :from and :to order by r.ts")
    List<ForensicsRecord> findWindow(@Param("from") Instant from,
                                     @Param("to") Instant to,
                                     Pageable pageable);

    long deleteByTsBefore(Instant cutoff);
}

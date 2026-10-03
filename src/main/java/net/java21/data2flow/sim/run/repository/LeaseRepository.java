package net.java21.data2flow.sim.run.repository;

import net.java21.data2flow.contracts.tenancy.OrganizationScopeExempt;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/** 실행 소유({@code leases}). 기한이 지나면 다른 인스턴스가 가져간다(TC-SIM-045) */
@Repository
@OrganizationScopeExempt("조직과 무관한 인스턴스 소유 표시(이름에 실행·조직 ID가 들어 있음)")
public class LeaseRepository {

    private final JdbcClient jdbc;

    public LeaseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 얻거나 늘린다. 다른 인스턴스가 기한 안에 쥐고 있으면 false */
    public boolean acquire(String name, String owner, Instant now, Instant until) {
        return jdbc.sql("""
                INSERT INTO data2flow_sim.leases (name, owner, lease_until) VALUES (:name, :owner, :until)
                ON CONFLICT (name) DO UPDATE SET owner = EXCLUDED.owner, lease_until = EXCLUDED.lease_until, updated_at = now()
                WHERE data2flow_sim.leases.owner = EXCLUDED.owner OR data2flow_sim.leases.lease_until < :now
                """).param("name", name).param("owner", owner).param("until", Timestamp.from(until))
                .param("now", Timestamp.from(now)).update() > 0;
    }

    public void release(String name, String owner) {
        jdbc.sql("DELETE FROM data2flow_sim.leases WHERE name = :name AND owner = :owner").param("name", name).param("owner", owner).update();
    }
}

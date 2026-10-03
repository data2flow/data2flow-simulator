package net.java21.data2flow.sim.fault.repository;

import net.java21.data2flow.sim.common.Json;
import net.java21.data2flow.sim.fault.domain.FaultKind;
import net.java21.data2flow.sim.fault.domain.FaultSpec;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 주입한 장애와 정답 라벨({@code faults}, SIM-05.04, BR-SIM-15) */
@Repository
public class FaultRepository {

    private final JdbcClient jdbc;

    public FaultRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param status SCHEDULED, ACTIVE, ENDED, CANCELLED
     */
    public record FaultRow(long id, long organizationId, Long runId, FaultSpec spec, String status) {
    }

    public long insert(long organizationId, Long runId, FaultSpec f) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO data2flow_sim.faults (organization_id, run_id, target_type, target_id, kind, params, sim_from, sim_to)
                VALUES (:org, :run, :type, :target, :kind, CAST(:params AS jsonb), :from, :to)
                """).param("org", organizationId).param("run", runId).param("type", f.targetType()).param("target", f.targetId())
                .param("kind", f.kind().name()).param("params", Json.write(f.params())).param("from", Timestamp.from(f.simFrom()))
                .param("to", f.simTo() == null ? null : Timestamp.from(f.simTo())).update(keys, "id");
        return keys.getKey().longValue();
    }

    public int updateStatus(long organizationId, long id, String status, Instant simTo) {
        return jdbc.sql("""
                UPDATE data2flow_sim.faults SET status = :status, sim_to = COALESCE(:to, sim_to), updated_at = now()
                WHERE id = :id AND organization_id = :org AND status NOT IN ('ENDED', 'CANCELLED')
                """).param("status", status).param("to", simTo == null ? null : Timestamp.from(simTo)).param("id", id)
                .param("org", organizationId).update();
    }

    public Optional<FaultRow> findById(long organizationId, long id) {
        return jdbc.sql("SELECT * FROM data2flow_sim.faults WHERE id = :id AND organization_id = :org")
                .param("id", id).param("org", organizationId).query(this::row).optional();
    }

    public List<FaultRow> findByRun(long organizationId, long runId) {
        return jdbc.sql("SELECT * FROM data2flow_sim.faults WHERE organization_id = :org AND run_id = :run ORDER BY id")
                .param("org", organizationId).param("run", runId).query(this::row).list();
    }

    /** 상시 모드(run_id 없음) 장애 중 끝나지 않은 것 */
    public List<FaultRow> findStanding(long organizationId) {
        return jdbc.sql("""
                SELECT * FROM data2flow_sim.faults WHERE organization_id = :org AND run_id IS NULL AND status IN ('SCHEDULED', 'ACTIVE')
                ORDER BY id
                """).param("org", organizationId).query(this::row).list();
    }

    public List<FaultRow> list(long organizationId, Long runId, String status, int size, long offset) {
        return jdbc.sql("""
                SELECT * FROM data2flow_sim.faults WHERE organization_id = :org
                  AND (CAST(:run AS bigint) IS NULL OR run_id = :run) AND (CAST(:status AS varchar) IS NULL OR status = :status)
                ORDER BY id DESC LIMIT :size OFFSET :offset
                """).param("org", organizationId).param("run", runId).param("status", status).param("size", size)
                .param("offset", offset).query(this::row).list();
    }

    public long count(long organizationId, Long runId, String status) {
        return jdbc.sql("""
                SELECT count(*) FROM data2flow_sim.faults WHERE organization_id = :org
                  AND (CAST(:run AS bigint) IS NULL OR run_id = :run) AND (CAST(:status AS varchar) IS NULL OR status = :status)
                """).param("org", organizationId).param("run", runId).param("status", status).query(Long.class).single();
    }

    private FaultRow row(ResultSet rs, int n) throws SQLException {
        long run = rs.getLong("run_id");
        Long runId = rs.wasNull() ? null : run;
        Timestamp to = rs.getTimestamp("sim_to");
        FaultSpec spec = new FaultSpec(rs.getLong("id"), FaultKind.valueOf(rs.getString("kind")), rs.getString("target_type"),
                rs.getString("target_id"), Json.map(rs.getString("params")), rs.getTimestamp("sim_from").toInstant(),
                to == null ? null : to.toInstant());
        return new FaultRow(rs.getLong("id"), rs.getLong("organization_id"), runId, spec, rs.getString("status"));
    }
}

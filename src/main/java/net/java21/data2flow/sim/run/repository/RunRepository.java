package net.java21.data2flow.sim.run.repository;

import net.java21.data2flow.contracts.tenancy.OrganizationScopeExempt;
import net.java21.data2flow.sim.engine.TimestampPolicy;
import net.java21.data2flow.sim.run.domain.RunStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 시나리오 실행({@code runs}, SIM-04.02) */
@Repository
public class RunRepository {

    private final JdbcClient jdbc;

    public RunRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record RunRow(long id, long organizationId, Long scenarioId, String kind, RunStatus status, int accelerationRequested,
                         int accelerationEffective, TimestampPolicy timestampPolicy, long seed, String notificationPolicy,
                         Instant simClock, Instant startedAt, Instant pausedAt, Instant finishedAt, double progressPct, boolean partial,
                         String plan, String checkpoint, Instant checkpointAt, String result, Instant retainUntil, long requestedBy,
                         String failureReason) {
    }

    public long insert(long organizationId, Long scenarioId, String kind, int acceleration, int effective, TimestampPolicy policy,
                       long seed, String notificationPolicy, Instant simClock, String plan, Instant retainUntil, long requestedBy) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO data2flow_sim.runs (organization_id, scenario_id, kind, status, acceleration_requested, acceleration_effective,
                    timestamp_policy, seed, notification_policy, sim_clock, plan, retain_until, requested_by)
                VALUES (:org, :scenario, :kind, 'CREATED', :accel, :eff, :policy, :seed, :notify, :clock, CAST(:plan AS jsonb), :retain, :by)
                """).param("org", organizationId).param("scenario", scenarioId).param("kind", kind).param("accel", acceleration)
                .param("eff", effective).param("policy", policy.name()).param("seed", seed).param("notify", notificationPolicy)
                .param("clock", Timestamp.from(simClock)).param("plan", plan).param("retain", Timestamp.from(retainUntil))
                .param("by", requestedBy).update(keys, "id");
        return keys.getKey().longValue();
    }

    public Optional<RunRow> findById(long organizationId, long id) {
        return jdbc.sql("SELECT * FROM data2flow_sim.runs WHERE id = :id AND organization_id = :org")
                .param("id", id).param("org", organizationId).query(this::row).optional();
    }

    @OrganizationScopeExempt("실행기가 소유한 실행을 ID로 다시 읽음")
    public Optional<RunRow> loadById(long id) {
        return jdbc.sql("SELECT * FROM data2flow_sim.runs WHERE id = :id").param("id", id).query(this::row).optional();
    }

    /** 상태를 바꾼다(기대 상태일 때만). 바뀐 행 수 */
    public int transition(long organizationId, long id, RunStatus from, RunStatus to, Instant realNow) {
        return jdbc.sql("""
                UPDATE data2flow_sim.runs SET status = :to,
                    started_at = CASE WHEN CAST(:to AS varchar) = 'RUNNING' AND started_at IS NULL THEN CAST(:now AS timestamptz) ELSE started_at END,
                    paused_at = CASE WHEN CAST(:to AS varchar) = 'PAUSED' THEN CAST(:now AS timestamptz) ELSE NULL END,
                    updated_at = now()
                WHERE id = :id AND organization_id = :org AND status = :from
                """).param("to", to.name()).param("now", Timestamp.from(realNow)).param("id", id).param("org", organizationId)
                .param("from", from.name()).update();
    }

    /** 초기화(SIM-04.02 reset): 처음 상태로 */
    public int reset(long organizationId, long id, Instant simStart) {
        return jdbc.sql("""
                UPDATE data2flow_sim.runs SET status = 'CREATED', sim_clock = :clock, progress_pct = 0, partial = false, checkpoint = NULL,
                    checkpoint_at = NULL, result = NULL, started_at = NULL, paused_at = NULL, finished_at = NULL, failure_reason = NULL,
                    acceleration_effective = acceleration_requested, updated_at = now()
                WHERE id = :id AND organization_id = :org AND status NOT IN ('PURGED', 'EVALUATING')
                """).param("clock", Timestamp.from(simStart)).param("id", id).param("org", organizationId).update();
    }

    @OrganizationScopeExempt("실행기가 소유한 실행의 체크포인트를 ID로 저장")
    public int saveCheckpoint(long id, Instant simClock, double progressPct, String checkpoint, Instant checkpointAt) {
        return jdbc.sql("""
                UPDATE data2flow_sim.runs SET sim_clock = :clock, progress_pct = :progress, checkpoint = CAST(:cp AS jsonb),
                    checkpoint_at = :at, updated_at = now()
                WHERE id = :id AND status IN ('RUNNING', 'PAUSED')
                """).param("clock", Timestamp.from(simClock)).param("progress", progressPct).param("cp", checkpoint)
                .param("at", Timestamp.from(checkpointAt)).param("id", id).update();
    }

    public int setAcceleration(long organizationId, long id, Integer requested, int effective) {
        return jdbc.sql("""
                UPDATE data2flow_sim.runs SET acceleration_requested = COALESCE(:req, acceleration_requested),
                    acceleration_effective = :eff, updated_at = now()
                WHERE id = :id AND organization_id = :org
                """).param("req", requested).param("eff", effective).param("id", id).param("org", organizationId).update();
    }

    /** 끝(완료·정지 판정·실패) */
    public int finish(long organizationId, long id, RunStatus from, RunStatus to, Instant simClock, double progressPct, boolean partial,
                      String result, Instant finishedAt, Instant retainUntil, String failureReason) {
        return jdbc.sql("""
                UPDATE data2flow_sim.runs SET status = :to, sim_clock = :clock, progress_pct = :progress, partial = :partial,
                    result = CAST(:result AS jsonb), finished_at = :finished, retain_until = :retain, failure_reason = :reason,
                    updated_at = now()
                WHERE id = :id AND organization_id = :org AND status = :from
                """).param("to", to.name()).param("clock", Timestamp.from(simClock)).param("progress", progressPct)
                .param("partial", partial).param("result", result).param("finished", Timestamp.from(finishedAt))
                .param("retain", Timestamp.from(retainUntil)).param("reason", failureReason).param("id", id)
                .param("org", organizationId).param("from", from.name()).update();
    }

    public int setRetainUntil(long organizationId, long id, Instant retainUntil) {
        return jdbc.sql("UPDATE data2flow_sim.runs SET retain_until = :retain, updated_at = now() WHERE id = :id AND organization_id = :org")
                .param("retain", Timestamp.from(retainUntil)).param("id", id).param("org", organizationId).update();
    }

    /** 실행 중으로 세는 실행 수(RUNNING·PAUSED·EVALUATING) */
    public long countActive(long organizationId) {
        return jdbc.sql("SELECT count(*) FROM data2flow_sim.runs WHERE organization_id = :org AND status IN ('RUNNING', 'PAUSED', 'EVALUATING')")
                .param("org", organizationId).query(Long.class).single();
    }

    /** 같은 공간을 쓰는 활성 실행(BR-SIM-17) */
    public List<Long> findActiveUsingSpaces(long organizationId, Collection<Long> spaceIds, long excludeRunId) {
        if (spaceIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT r.id FROM data2flow_sim.runs r JOIN data2flow_sim.scenarios s ON s.id = r.scenario_id
                WHERE r.organization_id = :org AND r.status IN ('RUNNING', 'PAUSED', 'EVALUATING') AND r.id <> :exclude
                  AND s.space_ids && CAST(:spaces AS bigint[])
                """).param("org", organizationId).param("exclude", excludeRunId)
                .param("spaces", "{" + String.join(",", spaceIds.stream().map(String::valueOf).toList()) + "}")
                .query(Long.class).list();
    }

    /** 활성 실행이 쓰는 공간 */
    public List<Long> findBusySpaces(long organizationId) {
        return jdbc.sql("""
                SELECT DISTINCT unnest(s.space_ids) FROM data2flow_sim.runs r JOIN data2flow_sim.scenarios s ON s.id = r.scenario_id
                WHERE r.organization_id = :org AND r.status IN ('RUNNING', 'PAUSED', 'EVALUATING')
                """).param("org", organizationId).query(Long.class).list();
    }

    @OrganizationScopeExempt("실행기가 넘겨받을 실행을 찾음(배포 조직 필터는 서비스가 적용)")
    public List<RunRow> listRunnable() {
        return jdbc.sql("SELECT * FROM data2flow_sim.runs WHERE status IN ('RUNNING', 'PAUSED') ORDER BY id").query(this::row).list();
    }

    public List<RunRow> listRecent(long organizationId, int limit) {
        return jdbc.sql("SELECT * FROM data2flow_sim.runs WHERE organization_id = :org ORDER BY id DESC LIMIT :limit")
                .param("org", organizationId).param("limit", limit).query(this::row).list();
    }

    @OrganizationScopeExempt("보관 기한 정리 배치: 모든 조직의 만료 실행(배포 조직 필터는 서비스가 적용)")
    public List<RunRow> listExpired(Instant now) {
        return jdbc.sql("""
                SELECT * FROM data2flow_sim.runs WHERE status IN ('COMPLETED', 'STOPPED', 'FAILED') AND retain_until < :now ORDER BY id
                """).param("now", Timestamp.from(now)).query(this::row).list();
    }

    /** 조직 단위 동시 시작 직렬화(TC-SIM-115). 트랜잭션 안에서 부른다 */
    public void lockOrganization(long organizationId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(:ns, :org)").param("ns", 0x51_4D).param("org", (int) organizationId)
                .query((rs, n) -> 1).list();
    }

    private RunRow row(ResultSet rs, int n) throws SQLException {
        long scenario = rs.getLong("scenario_id");
        Long scenarioId = rs.wasNull() ? null : scenario;
        return new RunRow(rs.getLong("id"), rs.getLong("organization_id"), scenarioId, rs.getString("kind"),
                RunStatus.valueOf(rs.getString("status")), rs.getInt("acceleration_requested"), rs.getInt("acceleration_effective"),
                TimestampPolicy.valueOf(rs.getString("timestamp_policy")), rs.getLong("seed"), rs.getString("notification_policy"),
                instant(rs, "sim_clock"), instant(rs, "started_at"), instant(rs, "paused_at"), instant(rs, "finished_at"),
                rs.getDouble("progress_pct"), rs.getBoolean("partial"), rs.getString("plan"), rs.getString("checkpoint"),
                instant(rs, "checkpoint_at"), rs.getString("result"), instant(rs, "retain_until"), rs.getLong("requested_by"),
                rs.getString("failure_reason"));
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}

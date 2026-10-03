package net.java21.data2flow.sim.scenario.repository;

import net.java21.data2flow.sim.common.Json;
import net.java21.data2flow.sim.physics.domain.OutdoorSpec;
import net.java21.data2flow.sim.scenario.domain.Expectation;
import net.java21.data2flow.sim.scenario.domain.Scenario;
import net.java21.data2flow.sim.scenario.domain.ScenarioEvent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 시나리오({@code scenarios}, SIM-04.01) */
@Repository
public class ScenarioRepository {

    private final JdbcClient jdbc;

    public ScenarioRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record ScenarioRow(long id, long organizationId, Scenario scenario, int schemaVersion, int version, Instant updatedAt) {
    }

    public long insert(long organizationId, Scenario s, Long userId) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO data2flow_sim.scenarios (organization_id, name, space_ids, sim_start_at, duration_sec, seed, use_calendar,
                    outdoor, events, expectations, preset_key, created_by, updated_by)
                VALUES (:org, :name, CAST(:spaces AS bigint[]), :start, :duration, :seed, :calendar, CAST(:outdoor AS jsonb),
                    CAST(:events AS jsonb), CAST(:expectations AS jsonb), :preset, :user, :user)
                """).paramSource(params(organizationId, s, userId)).update(keys, "id");
        return keys.getKey().longValue();
    }

    public int update(long organizationId, long id, Scenario s, int baseVersion, Long userId) {
        Map<String, Object> p = params(organizationId, s, userId);
        p.put("id", id);
        p.put("base", baseVersion);
        return jdbc.sql("""
                UPDATE data2flow_sim.scenarios SET name = :name, space_ids = CAST(:spaces AS bigint[]), sim_start_at = :start,
                    duration_sec = :duration, seed = :seed, use_calendar = :calendar, outdoor = CAST(:outdoor AS jsonb),
                    events = CAST(:events AS jsonb), expectations = CAST(:expectations AS jsonb), version = version + 1,
                    updated_by = :user, updated_at = now()
                WHERE id = :id AND organization_id = :org AND version = :base
                """).paramSource(p).update();
    }

    private static Map<String, Object> params(long org, Scenario s, Long userId) {
        Map<String, Object> m = new HashMap<>();
        m.put("org", org);
        m.put("name", s.name());
        m.put("spaces", "{" + String.join(",", s.spaceIds().stream().map(String::valueOf).toList()) + "}");
        m.put("start", Timestamp.from(s.simStartAt()));
        m.put("duration", s.durationSec());
        m.put("seed", s.seed());
        m.put("calendar", s.useCalendar());
        m.put("outdoor", Json.write(s.outdoor()));
        m.put("events", Json.write(s.events()));
        m.put("expectations", Json.write(s.expectations()));
        m.put("preset", s.presetKey());
        m.put("user", userId);
        return m;
    }

    public Optional<ScenarioRow> findById(long organizationId, long id) {
        return jdbc.sql("SELECT * FROM data2flow_sim.scenarios WHERE id = :id AND organization_id = :org")
                .param("id", id).param("org", organizationId).query(this::row).optional();
    }

    public Optional<ScenarioRow> findByName(long organizationId, String name) {
        return jdbc.sql("SELECT * FROM data2flow_sim.scenarios WHERE name = :name AND organization_id = :org")
                .param("name", name).param("org", organizationId).query(this::row).optional();
    }

    public Optional<ScenarioRow> findByPreset(long organizationId, String presetKey) {
        return jdbc.sql("SELECT * FROM data2flow_sim.scenarios WHERE preset_key = :key AND organization_id = :org ORDER BY id LIMIT 1")
                .param("key", presetKey).param("org", organizationId).query(this::row).optional();
    }

    public List<ScenarioRow> page(long organizationId, String keyword, int size, long offset) {
        return jdbc.sql("""
                SELECT * FROM data2flow_sim.scenarios WHERE organization_id = :org AND (:kw IS NULL OR name ILIKE '%' || :kw || '%')
                ORDER BY updated_at DESC, id DESC LIMIT :size OFFSET :offset
                """).param("org", organizationId).param("kw", keyword).param("size", size).param("offset", offset)
                .query(this::row).list();
    }

    public long count(long organizationId, String keyword) {
        return jdbc.sql("""
                SELECT count(*) FROM data2flow_sim.scenarios WHERE organization_id = :org AND (:kw IS NULL OR name ILIKE '%' || :kw || '%')
                """).param("org", organizationId).param("kw", keyword).query(Long.class).single();
    }

    public int delete(long organizationId, long id) {
        return jdbc.sql("DELETE FROM data2flow_sim.scenarios WHERE id = :id AND organization_id = :org")
                .param("id", id).param("org", organizationId).update();
    }

    private ScenarioRow row(ResultSet rs, int n) throws SQLException {
        long seed = rs.getLong("seed");
        Long seedValue = rs.wasNull() ? null : seed;
        String expectations = rs.getString("expectations");
        Scenario s = new Scenario(rs.getString("name"), longs(rs.getArray("space_ids")), rs.getTimestamp("sim_start_at").toInstant(),
                rs.getInt("duration_sec"), seedValue, rs.getBoolean("use_calendar"), Json.read(rs.getString("outdoor"), OutdoorSpec.class),
                Json.read(rs.getString("events"), new TypeReference<List<ScenarioEvent>>() {
                }), expectations == null ? List.of() : Json.read(expectations, new TypeReference<List<Expectation>>() {
                }), rs.getString("preset_key"));
        return new ScenarioRow(rs.getLong("id"), rs.getLong("organization_id"), s, rs.getInt("schema_version"), rs.getInt("version"),
                rs.getTimestamp("updated_at").toInstant());
    }

    static List<Long> longs(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.stream((Object[]) array.getArray()).map(o -> ((Number) o).longValue()).toList();
    }
}

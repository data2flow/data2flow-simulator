package net.java21.data2flow.sim.space.repository;

import net.java21.data2flow.sim.common.Json;
import net.java21.data2flow.sim.physics.domain.SpacePhysics;
import net.java21.data2flow.sim.physics.domain.SpacePreset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 가상 공간 물리 설정({@code space_physics}, SIM-01.02). 공간 기준 정보(이름·계층)는 core가 가진다 */
@Repository
public class SpacePhysicsRepository {

    private final JdbcClient jdbc;

    public SpacePhysicsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param name 시뮬레이터가 아는 이름(core가 넘겨준 값, 화면 이름은 core 정본)
     */
    public record SpaceRow(long spaceId, long organizationId, String name, SpacePhysics physics, boolean sandbox, int version,
                           Instant updatedAt) {
    }

    /** 처음이면 만들고, 있으면 바꾼다(core 사가 2단계, API-SIM-10). 새로 만들었으면 true */
    public boolean upsert(long organizationId, long spaceId, SpacePhysics p) {
        int updated = jdbc.sql("""
                UPDATE data2flow_sim.space_physics SET preset = :preset, area_m2 = :area, height_m = :height, u_value = :u,
                    envelope_m2 = :env, window_m2 = :win, window_orientation = :orient, solar_gain_factor = :solar,
                    initial_state = CAST(:initial AS jsonb), outdoor_linked = :linked, outdoor_co2_ppm = :co2,
                    per_person = CAST(:person AS jsonb), background_noise_db = :noise, noise_std = CAST(:std AS jsonb),
                    version = version + 1, updated_at = now()
                WHERE space_id = :id AND organization_id = :org
                """).paramSource(params(organizationId, spaceId, p)).update();
        if (updated > 0) {
            return false;
        }
        jdbc.sql("""
                INSERT INTO data2flow_sim.space_physics (space_id, organization_id, preset, area_m2, height_m, u_value, envelope_m2,
                    window_m2, window_orientation, solar_gain_factor, initial_state, outdoor_linked, outdoor_co2_ppm, per_person,
                    background_noise_db, noise_std)
                VALUES (:id, :org, :preset, :area, :height, :u, :env, :win, :orient, :solar, CAST(:initial AS jsonb), :linked, :co2,
                    CAST(:person AS jsonb), :noise, CAST(:std AS jsonb))
                """).paramSource(params(organizationId, spaceId, p)).update();
        return true;
    }

    private static Map<String, Object> params(long org, long id, SpacePhysics p) {
        java.util.HashMap<String, Object> m = new java.util.HashMap<>();
        m.put("id", id);
        m.put("org", org);
        m.put("preset", p.preset().name());
        m.put("area", p.areaM2());
        m.put("height", p.heightM());
        m.put("u", p.uValue());
        m.put("env", p.envelopeM2());
        m.put("win", p.windowM2());
        m.put("orient", p.windowOrientation());
        m.put("solar", p.solarGainFactor());
        m.put("initial", Json.write(p.initialState()));
        m.put("linked", p.outdoorLinked());
        m.put("co2", p.outdoorCo2Ppm());
        m.put("person", Json.write(p.perPerson()));
        m.put("noise", p.backgroundNoiseDb());
        m.put("std", Json.write(p.noiseStd()));
        return m;
    }

    public int setSandbox(long organizationId, long spaceId, boolean sandbox) {
        return jdbc.sql("""
                UPDATE data2flow_sim.space_physics SET sandbox = :sandbox, version = version + 1, updated_at = now()
                WHERE space_id = :id AND organization_id = :org
                """).param("sandbox", sandbox).param("id", spaceId).param("org", organizationId).update();
    }

    public Optional<SpaceRow> findById(long organizationId, long spaceId) {
        return jdbc.sql("SELECT * FROM data2flow_sim.space_physics WHERE space_id = :id AND organization_id = :org")
                .param("id", spaceId).param("org", organizationId).query(this::row).optional();
    }

    public List<SpaceRow> findByIds(long organizationId, Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT * FROM data2flow_sim.space_physics WHERE organization_id = :org AND space_id IN (:ids) ORDER BY space_id")
                .param("org", organizationId).param("ids", ids).query(this::row).list();
    }

    public List<SpaceRow> findAll(long organizationId) {
        return jdbc.sql("SELECT * FROM data2flow_sim.space_physics WHERE organization_id = :org ORDER BY space_id")
                .param("org", organizationId).query(this::row).list();
    }

    public int delete(long organizationId, long spaceId) {
        return jdbc.sql("DELETE FROM data2flow_sim.space_physics WHERE space_id = :id AND organization_id = :org")
                .param("id", spaceId).param("org", organizationId).update();
    }

    private SpaceRow row(ResultSet rs, int n) throws SQLException {
        Map<String, Double> std = Json.read(rs.getString("noise_std"), new TypeReference<Map<String, Double>>() {
        });
        double win = rs.getDouble("window_m2");
        Double window = rs.wasNull() ? null : win;
        SpacePhysics p = new SpacePhysics(SpacePreset.valueOf(rs.getString("preset")), rs.getDouble("area_m2"), rs.getDouble("height_m"),
                rs.getDouble("u_value"), rs.getDouble("envelope_m2"), window, rs.getString("window_orientation"),
                rs.getDouble("solar_gain_factor"), Json.read(rs.getString("initial_state"), SpacePhysics.InitialState.class),
                rs.getBoolean("outdoor_linked"), rs.getDouble("outdoor_co2_ppm"),
                Json.read(rs.getString("per_person"), SpacePhysics.PerPerson.class), rs.getDouble("background_noise_db"), std);
        return new SpaceRow(rs.getLong("space_id"), rs.getLong("organization_id"), "공간 " + rs.getLong("space_id"), p,
                rs.getBoolean("sandbox"), rs.getInt("version"), rs.getTimestamp("updated_at").toInstant());
    }
}

package net.java21.data2flow.sim.catalog.repository;

import net.java21.data2flow.sim.common.Json;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 조직 프로필({@code profiles}, SIM-09.02): 바꾼 특성만 저장 */
@Repository
public class ProfileRepository {

    private final JdbcClient jdbc;

    public ProfileRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record ProfileRow(long id, long organizationId, String name, long typeId, Map<String, Object> overrides, int version) {
    }

    public long insert(long organizationId, String name, long typeId, Map<String, Object> overrides, Long userId) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO data2flow_sim.profiles (organization_id, name, type_id, overrides, created_by, updated_by)
                VALUES (:org, :name, :type, CAST(:overrides AS jsonb), :user, :user)
                """).param("org", organizationId).param("name", name).param("type", typeId)
                .param("overrides", Json.write(overrides)).param("user", userId).update(keys, "id");
        return keys.getKey().longValue();
    }

    /** 낙관적 잠금으로 고친다. 바뀐 행 수(0이면 버전 충돌) */
    public int update(long organizationId, long id, String name, Map<String, Object> overrides, int baseVersion, Long userId) {
        return jdbc.sql("""
                UPDATE data2flow_sim.profiles SET name = :name, overrides = CAST(:overrides AS jsonb), version = version + 1,
                    updated_by = :user, updated_at = now()
                WHERE id = :id AND organization_id = :org AND version = :base
                """).param("name", name).param("overrides", Json.write(overrides)).param("user", userId).param("id", id)
                .param("org", organizationId).param("base", baseVersion).update();
    }

    public Optional<ProfileRow> findById(long organizationId, long id) {
        return jdbc.sql("SELECT * FROM data2flow_sim.profiles WHERE id = :id AND organization_id = :org")
                .param("id", id).param("org", organizationId).query(this::row).optional();
    }

    public List<ProfileRow> findAll(long organizationId) {
        return jdbc.sql("SELECT * FROM data2flow_sim.profiles WHERE organization_id = :org ORDER BY name")
                .param("org", organizationId).query(this::row).list();
    }

    public int delete(long organizationId, long id) {
        return jdbc.sql("DELETE FROM data2flow_sim.profiles WHERE id = :id AND organization_id = :org")
                .param("id", id).param("org", organizationId).update();
    }

    public long countDevices(long organizationId, long id) {
        return jdbc.sql("SELECT count(*) FROM data2flow_sim.devices WHERE profile_id = :id AND organization_id = :org")
                .param("id", id).param("org", organizationId).query(Long.class).single();
    }

    private ProfileRow row(ResultSet rs, int n) throws SQLException {
        return new ProfileRow(rs.getLong("id"), rs.getLong("organization_id"), rs.getString("name"), rs.getLong("type_id"),
                Json.map(rs.getString("overrides")), rs.getInt("version"));
    }
}

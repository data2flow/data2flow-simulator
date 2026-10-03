package net.java21.data2flow.sim.catalog.repository;

import net.java21.data2flow.contracts.tenancy.OrganizationScopeExempt;
import net.java21.data2flow.sim.catalog.domain.DeviceTypeDef;
import net.java21.data2flow.sim.catalog.domain.KitDef;
import net.java21.data2flow.sim.catalog.domain.MetricDef;
import net.java21.data2flow.sim.catalog.domain.PhysicsEffect;
import net.java21.data2flow.sim.catalog.domain.PropertyDef;
import net.java21.data2flow.sim.common.Json;
import net.java21.data2flow.sim.device.domain.PayloadFormat;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** 가상 기기 유형·키트({@code device_types}, {@code kits}). 기본 유형은 조직 0(erd/sim.md §1) */
@Repository
public class CatalogRepository {

    public static final long PLATFORM_ORG = 0;

    private final JdbcClient jdbc;

    public CatalogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 유형 행: ID와 정의 */
    public record TypeRow(long id, long organizationId, boolean builtin, DeviceTypeDef def, int version) {
    }

    /** 기본 유형을 코드 정의에 맞춘다(시작할 때, 멱등) */
    @OrganizationScopeExempt("플랫폼 기본 유형(조직 0) 동기화")
    public void upsertBuiltinType(DeviceTypeDef t) {
        jdbc.sql("""
                INSERT INTO data2flow_sim.device_types (organization_id, key, name, category, builtin, metrics, capabilities,
                    property_defs, physics_effects, linked_model_code, default_payload_format)
                VALUES (0, :key, :name, :category, true, CAST(:metrics AS jsonb), CAST(:caps AS text[]), CAST(:defs AS jsonb),
                    CAST(:effects AS jsonb), :model, :format)
                ON CONFLICT (organization_id, key) DO UPDATE SET name = EXCLUDED.name, category = EXCLUDED.category,
                    metrics = EXCLUDED.metrics, capabilities = EXCLUDED.capabilities, property_defs = EXCLUDED.property_defs,
                    physics_effects = EXCLUDED.physics_effects, linked_model_code = EXCLUDED.linked_model_code,
                    default_payload_format = EXCLUDED.default_payload_format, updated_at = now()
                WHERE (data2flow_sim.device_types.metrics, data2flow_sim.device_types.property_defs, data2flow_sim.device_types.capabilities)
                    IS DISTINCT FROM (EXCLUDED.metrics, EXCLUDED.property_defs, EXCLUDED.capabilities)
                """)
                .param("key", t.key()).param("name", t.name()).param("category", t.category())
                .param("metrics", Json.write(new MetricsColumn(t.metrics(), t.defaultReportIntervalSec(), t.reportOnChange())))
                .param("caps", "{" + String.join(",", t.capabilities().stream().map(c -> "\"" + c + "\"").toList()) + "}")
                .param("defs", Json.write(t.propertyDefs())).param("effects", Json.write(t.physicsEffects()))
                .param("model", t.linkedModelCode()).param("format", t.defaultPayloadFormat().name())
                .update();
    }

    @OrganizationScopeExempt("플랫폼 기본 키트(조직 0) 동기화")
    public void upsertBuiltinKit(KitDef k) {
        jdbc.sql("""
                INSERT INTO data2flow_sim.kits (organization_id, key, name, builtin, items, suggested_flow_templates)
                VALUES (0, :key, :name, true, CAST(:items AS jsonb), CAST(:flows AS text[]))
                ON CONFLICT (organization_id, key) DO UPDATE SET name = EXCLUDED.name, items = EXCLUDED.items,
                    suggested_flow_templates = EXCLUDED.suggested_flow_templates, updated_at = now()
                """)
                .param("key", k.key()).param("name", k.name()).param("items", Json.write(k.items()))
                .param("flows", "{" + String.join(",", k.suggestedFlowTemplates()) + "}")
                .update();
    }

    /** 조직이 쓸 수 있는 유형(기본 + 조직 정의) */
    public List<TypeRow> findTypes(long organizationId) {
        return jdbc.sql("""
                SELECT * FROM data2flow_sim.device_types WHERE organization_id IN (0, :org) ORDER BY builtin DESC, key
                """).param("org", organizationId).query(this::type).list();
    }

    public Optional<TypeRow> findType(long organizationId, long typeId) {
        return jdbc.sql("SELECT * FROM data2flow_sim.device_types WHERE id = :id AND organization_id IN (0, :org)")
                .param("id", typeId).param("org", organizationId).query(this::type).optional();
    }

    public Optional<TypeRow> findTypeByKey(long organizationId, String key) {
        return jdbc.sql("""
                SELECT * FROM data2flow_sim.device_types WHERE key = :key AND organization_id IN (0, :org)
                ORDER BY organization_id DESC LIMIT 1
                """).param("key", key).param("org", organizationId).query(this::type).optional();
    }

    @OrganizationScopeExempt("기기 행이 이미 조직으로 걸러졌고 유형 ID로 정의만 읽음")
    public Optional<TypeRow> loadType(long typeId) {
        return jdbc.sql("SELECT * FROM data2flow_sim.device_types WHERE id = :id").param("id", typeId).query(this::type).optional();
    }

    /** {@code device_types.metrics} 열 모양: 측정 항목 + 보고 기본값 */
    public record MetricsColumn(List<MetricDef> metrics, int defaultReportIntervalSec, boolean reportOnChange) {
    }

    private TypeRow type(ResultSet rs, int n) throws SQLException {
        MetricsColumn mc = Json.read(rs.getString("metrics"), MetricsColumn.class);
        List<PropertyDef> defs = Json.read(rs.getString("property_defs"), new TypeReference<List<PropertyDef>>() {
        });
        String effects = rs.getString("physics_effects");
        List<PhysicsEffect> fx = effects == null ? List.of() : Json.read(effects, new TypeReference<List<PhysicsEffect>>() {
        });
        DeviceTypeDef def = new DeviceTypeDef(rs.getString("key"), rs.getString("name"), rs.getString("category"),
                mc == null ? List.of() : mc.metrics(), strings(rs.getArray("capabilities")), defs, fx, rs.getString("linked_model_code"),
                PayloadFormat.valueOf(rs.getString("default_payload_format")), mc == null ? 60 : mc.defaultReportIntervalSec(),
                mc != null && mc.reportOnChange());
        return new TypeRow(rs.getLong("id"), rs.getLong("organization_id"), rs.getBoolean("builtin"), def, rs.getInt("version"));
    }

    static List<String> strings(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        return Arrays.stream((Object[]) array.getArray()).map(String::valueOf).toList();
    }
}

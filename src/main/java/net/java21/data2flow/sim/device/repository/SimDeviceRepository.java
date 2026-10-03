package net.java21.data2flow.sim.device.repository;

import net.java21.data2flow.contracts.tenancy.OrganizationScopeExempt;
import net.java21.data2flow.sim.actuator.domain.ActuatorState;
import net.java21.data2flow.sim.common.Json;
import net.java21.data2flow.sim.device.domain.MetricSource;
import net.java21.data2flow.sim.device.domain.OutputPath;
import net.java21.data2flow.sim.device.domain.PayloadFormat;
import net.java21.data2flow.sim.device.domain.ReportMode;
import net.java21.data2flow.sim.device.domain.ResponseSettings;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 가상 기기 시뮬레이션 설정({@code devices}). 기기 기준 정보(이름·모델·virtual)는 core가 가진다 */
@Repository
public class SimDeviceRepository {

    private final JdbcClient jdbc;

    public SimDeviceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record DeviceRow(long deviceId, long organizationId, long typeId, Long profileId, long spaceId, String name,
                            String externalId, long sourceId, Map<String, Object> overrides, ReportMode reportMode,
                            Map<String, MetricSource> metricSources, int reportIntervalSec, double jitterPct, Double batteryPct,
                            Double batteryDrainPerReport, PayloadFormat payloadFormat, OutputPath outputPath, long frameCounter,
                            String gatewayEui, ResponseSettings response, ActuatorState actuatorState, Long seed, int version) {
    }

    public void insert(DeviceRow d) {
        jdbc.sql("""
                INSERT INTO data2flow_sim.devices (device_id, organization_id, type_id, profile_id, space_id, name, external_id, source_id,
                    overrides, report_mode, metric_sources, report_interval_sec, jitter_pct, battery_pct, battery_drain_per_report,
                    payload_format, output_path, frame_counter, virtual_gateway_eui, response, actuator_state, seed)
                VALUES (:id, :org, :type, :profile, :space, :name, :ext, :source, CAST(:overrides AS jsonb), :mode,
                    CAST(:sources AS jsonb), :interval, :jitter, :battery, :drain, :format, :output, :fcnt, :gw,
                    CAST(:response AS jsonb), CAST(:state AS jsonb), :seed)
                """).paramSource(params(d)).update();
    }

    /** 설정 전체를 바꾼다(낙관적 잠금 없이, 서비스가 버전을 확인). 바뀐 행 수 */
    public int update(DeviceRow d) {
        return jdbc.sql("""
                UPDATE data2flow_sim.devices SET profile_id = :profile, overrides = CAST(:overrides AS jsonb), report_mode = :mode,
                    metric_sources = CAST(:sources AS jsonb), report_interval_sec = :interval, jitter_pct = :jitter,
                    battery_drain_per_report = :drain, payload_format = :format, output_path = :output,
                    response = CAST(:response AS jsonb), seed = :seed, virtual_gateway_eui = :gw, version = version + 1, updated_at = now()
                WHERE device_id = :id AND organization_id = :org
                """).paramSource(params(d)).update();
    }

    private static Map<String, Object> params(DeviceRow d) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", d.deviceId());
        m.put("org", d.organizationId());
        m.put("type", d.typeId());
        m.put("profile", d.profileId());
        m.put("space", d.spaceId());
        m.put("name", d.name());
        m.put("ext", d.externalId());
        m.put("source", d.sourceId());
        m.put("overrides", Json.write(d.overrides()));
        m.put("mode", d.reportMode().name());
        m.put("sources", d.metricSources() == null || d.metricSources().isEmpty() ? null : Json.write(d.metricSources()));
        m.put("interval", d.reportIntervalSec());
        m.put("jitter", d.jitterPct());
        m.put("battery", d.batteryPct());
        m.put("drain", d.batteryDrainPerReport());
        m.put("format", d.payloadFormat().name());
        m.put("output", d.outputPath().name());
        m.put("fcnt", d.frameCounter());
        m.put("gw", d.gatewayEui());
        m.put("response", d.response() == null ? null : Json.write(d.response()));
        m.put("state", d.actuatorState() == null ? null : Json.write(d.actuatorState()));
        m.put("seed", d.seed());
        return m;
    }

    /** 상시 환경이 진행한 런타임 값(fCnt·배터리·장비 상태)을 남긴다 */
    @OrganizationScopeExempt("실행기가 자기가 시뮬레이션하는 기기의 런타임 값을 기기 ID로 갱신")
    public void saveRuntime(long deviceId, long frameCounter, Double batteryPct, ActuatorState state) {
        jdbc.sql("""
                UPDATE data2flow_sim.devices SET frame_counter = GREATEST(frame_counter, :fcnt), battery_pct = COALESCE(:battery, battery_pct),
                    actuator_state = COALESCE(CAST(:state AS jsonb), actuator_state)
                WHERE device_id = :id
                """).param("fcnt", frameCounter).param("battery", batteryPct).param("state", state == null ? null : Json.write(state))
                .param("id", deviceId).update();
    }

    public Optional<DeviceRow> findById(long organizationId, long deviceId) {
        return jdbc.sql("SELECT * FROM data2flow_sim.devices WHERE device_id = :id AND organization_id = :org")
                .param("id", deviceId).param("org", organizationId).query(this::row).optional();
    }

    /** 내부 API(action virtual 드라이버, API-SIM-30·32)는 기기 ID만 안다 */
    @OrganizationScopeExempt("action virtual 드라이버의 내부 호출: 기기 ID(전역 고유)로 찾고 응답에 조직을 담는다")
    public Optional<DeviceRow> loadById(long deviceId) {
        return jdbc.sql("SELECT * FROM data2flow_sim.devices WHERE device_id = :id").param("id", deviceId).query(this::row).optional();
    }

    public List<DeviceRow> findBySpaces(long organizationId, Collection<Long> spaceIds) {
        if (spaceIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT * FROM data2flow_sim.devices WHERE organization_id = :org AND space_id IN (:spaces) ORDER BY device_id")
                .param("org", organizationId).param("spaces", spaceIds).query(this::row).list();
    }

    public List<DeviceRow> findAlways(long organizationId) {
        return jdbc.sql("SELECT * FROM data2flow_sim.devices WHERE organization_id = :org AND report_mode = 'ALWAYS' ORDER BY device_id")
                .param("org", organizationId).query(this::row).list();
    }

    public List<DeviceRow> findAll(long organizationId) {
        return jdbc.sql("SELECT * FROM data2flow_sim.devices WHERE organization_id = :org ORDER BY device_id")
                .param("org", organizationId).query(this::row).list();
    }

    public long countByOrganization(long organizationId) {
        return jdbc.sql("SELECT count(*) FROM data2flow_sim.devices WHERE organization_id = :org")
                .param("org", organizationId).query(Long.class).single();
    }

    public long countBySpace(long organizationId, long spaceId) {
        return jdbc.sql("SELECT count(*) FROM data2flow_sim.devices WHERE organization_id = :org AND space_id = :space")
                .param("org", organizationId).param("space", spaceId).query(Long.class).single();
    }

    public long countByType(long organizationId, long typeId) {
        return jdbc.sql("SELECT count(*) FROM data2flow_sim.devices WHERE organization_id = :org AND type_id = :type")
                .param("org", organizationId).param("type", typeId).query(Long.class).single();
    }

    public int delete(long organizationId, long deviceId) {
        return jdbc.sql("DELETE FROM data2flow_sim.devices WHERE device_id = :id AND organization_id = :org")
                .param("id", deviceId).param("org", organizationId).update();
    }

    /** 상시 보고 기기가 있는 조직(실행기가 상시 환경을 띄울 대상) */
    @OrganizationScopeExempt("실행기가 조직별 상시 환경을 띄우려고 조직 목록만 읽음(배포 조직 필터는 서비스가 적용)")
    public List<Long> listOrganizationsWithAlwaysDevices() {
        return jdbc.sql("SELECT DISTINCT organization_id FROM data2flow_sim.devices WHERE report_mode = 'ALWAYS' ORDER BY 1")
                .query(Long.class).list();
    }

    private DeviceRow row(ResultSet rs, int n) throws SQLException {
        long profile = rs.getLong("profile_id");
        Long profileId = rs.wasNull() ? null : profile;
        double battery = rs.getDouble("battery_pct");
        Double batteryPct = rs.wasNull() ? null : battery;
        double drain = rs.getDouble("battery_drain_per_report");
        Double drainPer = rs.wasNull() ? null : drain;
        long seed = rs.getLong("seed");
        Long seedValue = rs.wasNull() ? null : seed;
        String sources = rs.getString("metric_sources");
        String response = rs.getString("response");
        String state = rs.getString("actuator_state");
        return new DeviceRow(rs.getLong("device_id"), rs.getLong("organization_id"), rs.getLong("type_id"), profileId,
                rs.getLong("space_id"), rs.getString("name"), rs.getString("external_id"), rs.getLong("source_id"),
                Json.map(rs.getString("overrides")), ReportMode.valueOf(rs.getString("report_mode")),
                sources == null ? Map.of() : Json.read(sources, new TypeReference<Map<String, MetricSource>>() {
                }), rs.getInt("report_interval_sec"), rs.getDouble("jitter_pct"), batteryPct, drainPer,
                PayloadFormat.valueOf(rs.getString("payload_format")), OutputPath.valueOf(rs.getString("output_path")),
                rs.getLong("frame_counter"), rs.getString("virtual_gateway_eui"),
                response == null ? null : Json.read(response, ResponseSettings.class),
                state == null ? null : Json.read(state, ActuatorState.class), seedValue, rs.getInt("version"));
    }
}

package net.java21.data2flow.sim.command.repository;

import net.java21.data2flow.contracts.tenancy.OrganizationScopeExempt;
import net.java21.data2flow.sim.common.Json;
import net.java21.data2flow.sim.engine.WorldState;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 가상 장비 명령 수신함({@code device_commands}). 장비를 시뮬레이션하는 인스턴스가 다음 틱에 가져간다 */
@Repository
@OrganizationScopeExempt("action virtual 드라이버 명령: 기기 ID(전역 고유)로 넣고, 그 기기를 돌리는 실행기가 기기 ID로 가져감")
public class CommandInboxRepository {

    private final JdbcClient jdbc;

    public CommandInboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long organizationId, long deviceId, String commandId, String capability, String command, Map<String, Object> args,
                       Long desiredVersion, Instant receivedAt) {
        jdbc.sql("""
                INSERT INTO data2flow_sim.device_commands (organization_id, device_id, command_id, capability, command, args,
                    desired_version, received_at)
                VALUES (:org, :device, :cid, :cap, :cmd, CAST(:args AS jsonb), :dv, :at)
                """).param("org", organizationId).param("device", deviceId).param("cid", commandId).param("cap", capability)
                .param("cmd", command).param("args", Json.write(args == null ? Map.of() : args)).param("dv", desiredVersion)
                .param("at", Timestamp.from(receivedAt)).update();
    }

    /** 이 기기들의 대기 명령을 가져간다(다른 인스턴스와 겹치지 않게 SKIP LOCKED) */
    public List<WorldState.InboundCommand> take(Collection<Long> deviceIds) {
        if (deviceIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                UPDATE data2flow_sim.device_commands SET status = 'TAKEN', processed_at = now()
                WHERE id IN (SELECT id FROM data2flow_sim.device_commands WHERE status = 'PENDING' AND device_id IN (:ids)
                             ORDER BY id FOR UPDATE SKIP LOCKED)
                RETURNING id, device_id, command_id, capability, command, args
                """).param("ids", deviceIds).query((rs, n) -> {
                    WorldState.InboundCommand c = new WorldState.InboundCommand();
                    c.commandId = rs.getString("command_id");
                    c.deviceId = rs.getLong("device_id");
                    c.capability = rs.getString("capability");
                    c.command = rs.getString("command");
                    c.args = new TreeMap<>(Json.map(rs.getString("args")));
                    c.source = "API";
                    return c;
                }).list().stream().sorted(java.util.Comparator.comparing((WorldState.InboundCommand c) -> c.commandId)).toList();
    }

    /** 오래된 명령 정리(7일) */
    public int purgeBefore(Instant before) {
        return jdbc.sql("DELETE FROM data2flow_sim.device_commands WHERE created_at < :before")
                .param("before", Timestamp.from(before)).update();
    }
}

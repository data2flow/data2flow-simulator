package net.java21.data2flow.sim.run.service;

import net.java21.data2flow.sim.catalog.repository.CatalogRepository;
import net.java21.data2flow.sim.common.Json;
import net.java21.data2flow.sim.common.SimProperties;
import net.java21.data2flow.sim.device.repository.SimDeviceRepository;
import net.java21.data2flow.sim.physics.domain.SpaceState;
import net.java21.data2flow.sim.run.domain.RunPlan;
import net.java21.data2flow.sim.run.domain.RunStatus;
import net.java21.data2flow.sim.run.repository.RunRepository;
import net.java21.data2flow.sim.scenario.service.ScenarioService;
import net.java21.data2flow.sim.space.repository.SpacePhysicsRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 가상 환경 요약(API-SIM-01의 simulator 쪽) */
@Service
public class OverviewService {

    private final SimDeviceRepository devices;
    private final SpacePhysicsRepository spaces;
    private final CatalogRepository catalog;
    private final RunRepository runs;
    private final ScenarioService scenarios;
    private final SimulationExecutor executor;
    private final SimProperties properties;

    public OverviewService(SimDeviceRepository devices, SpacePhysicsRepository spaces, CatalogRepository catalog, RunRepository runs,
                           ScenarioService scenarios, SimulationExecutor executor, SimProperties properties) {
        this.devices = devices;
        this.spaces = spaces;
        this.catalog = catalog;
        this.runs = runs;
        this.scenarios = scenarios;
        this.executor = executor;
        this.properties = properties;
    }

    public Map<String, Object> overview(long organizationId) {
        List<SimDeviceRepository.DeviceRow> all = devices.findAll(organizationId);
        List<RunRepository.RunRow> recent = runs.listRecent(organizationId, 50);
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("devices", all.size());
        usage.put("devicesLimit", properties.limits().devicesPerOrganization());
        usage.put("runningRuns", recent.stream().filter(r -> r.status().active()).count());
        usage.put("runsLimit", properties.limits().concurrentRuns());
        List<Map<String, Object>> runViews = new ArrayList<>();
        Set<Long> busy = new HashSet<>(runs.findBusySpaces(organizationId));
        for (RunRepository.RunRow r : recent) {
            if (!r.status().active()) {
                continue;
            }
            RunPlan plan = Json.read(r.plan(), RunPlan.class);
            var view = executor.view(r.id());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("runId", Long.toString(r.id()));
            m.put("scenarioName", plan.scenarioName());
            m.put("spaces", plan.scenario().spaceIds().stream().map(String::valueOf).toList());
            m.put("accelerationEffective", view.map(SimulationExecutor.LiveView::accelerationEffective).orElse(r.accelerationEffective()));
            m.put("progressPct", view.map(SimulationExecutor.LiveView::progressPct).orElse(r.progressPct()));
            m.put("simClock", view.map(SimulationExecutor.LiveView::simClock).orElse(r.simClock()));
            m.put("status", r.status());
            runViews.add(m);
        }
        List<Map<String, Object>> spaceViews = new ArrayList<>();
        for (SpacePhysicsRepository.SpaceRow s : spaces.findAll(organizationId)) {
            long sensors = 0;
            long actuators = 0;
            for (SimDeviceRepository.DeviceRow d : all) {
                if (d.spaceId() == s.spaceId()) {
                    boolean actuator = catalog.loadType(d.typeId()).map(t -> t.def().actuator()).orElse(false);
                    if (actuator) {
                        actuators++;
                    } else {
                        sensors++;
                    }
                }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("spaceId", Long.toString(s.spaceId()));
            m.put("sensors", sensors);
            m.put("actuators", actuators);
            SpaceState current = executor.spaceState(organizationId, s.spaceId()).orElse(null);
            m.put("current", current == null ? null : Map.of("temperature", Math.round(current.temperature * 10) / 10.0,
                    "co2", Math.round(current.co2)));
            m.put("running", busy.contains(s.spaceId()));
            m.put("sandbox", s.sandbox());
            spaceViews.add(m);
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (RunRepository.RunRow r : recent) {
            if (r.status() != RunStatus.COMPLETED || r.result() == null || results.size() >= 5) {
                continue;
            }
            Object list = Json.map(r.result()).get("expectations");
            long total = list instanceof List<?> l ? l.size() : 0;
            long passed = list instanceof List<?> l ? l.stream().filter(x -> x instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("passed"))).count() : 0;
            results.add(Map.of("runId", Long.toString(r.id()), "passed", passed, "total", total, "finishedAt", r.finishedAt()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("usage", usage);
        out.put("presets", scenarios.presets(organizationId));
        out.put("runs", runViews);
        out.put("spaces", spaceViews);
        out.put("recentResults", results);
        return out;
    }
}

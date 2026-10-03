package net.java21.data2flow.sim.run.service;

import net.java21.data2flow.sim.catalog.domain.PropertyResolver;
import net.java21.data2flow.sim.catalog.repository.CatalogRepository;
import net.java21.data2flow.sim.catalog.repository.ProfileRepository;
import net.java21.data2flow.sim.device.repository.SimDeviceRepository;
import net.java21.data2flow.sim.engine.SimulationWorld;
import net.java21.data2flow.sim.engine.WorldDevice;
import net.java21.data2flow.sim.engine.WorldSpace;
import net.java21.data2flow.sim.space.repository.SpacePhysicsRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** DB 행 → 엔진 입력(유효 특성까지 푼 기기, 공간) */
@Component
public class WorldFactory {

    private final CatalogRepository catalog;
    private final ProfileRepository profiles;
    private final SimDeviceRepository devices;
    private final SpacePhysicsRepository spaces;

    public WorldFactory(CatalogRepository catalog, ProfileRepository profiles, SimDeviceRepository devices,
                        SpacePhysicsRepository spaces) {
        this.catalog = catalog;
        this.profiles = profiles;
        this.devices = devices;
        this.spaces = spaces;
    }

    public WorldDevice device(SimDeviceRepository.DeviceRow d) {
        CatalogRepository.TypeRow type = catalog.loadType(d.typeId())
                .orElseThrow(() -> new IllegalStateException("유형이 없습니다: " + d.typeId()));
        Map<String, Object> profile = d.profileId() == null ? Map.of()
                : profiles.findById(d.organizationId(), d.profileId()).map(ProfileRepository.ProfileRow::overrides).orElse(Map.of());
        String gateway = d.gatewayEui() == null ? SimulationWorld.defaultGateway(d.organizationId()) : d.gatewayEui();
        return new WorldDevice(d.deviceId(), d.organizationId(), d.name(), d.externalId(), d.sourceId(), d.spaceId(), type.def(),
                PropertyResolver.effective(type.def(), profile, d.overrides()), d.metricSources(), d.reportIntervalSec(), d.jitterPct(),
                d.batteryDrainPerReport(), d.payloadFormat(), gateway, d.response(), d.seed(), d.frameCounter(), d.batteryPct(),
                d.actuatorState());
    }

    public List<WorldDevice> devicesIn(long organizationId, Collection<Long> spaceIds) {
        List<WorldDevice> list = new ArrayList<>();
        for (SimDeviceRepository.DeviceRow d : devices.findBySpaces(organizationId, spaceIds)) {
            list.add(device(d));
        }
        return list;
    }

    public List<WorldSpace> spaces(long organizationId, Collection<Long> spaceIds) {
        return spaces.findByIds(organizationId, spaceIds).stream()
                .map(s -> new WorldSpace(s.spaceId(), s.name(), s.physics(), s.sandbox())).toList();
    }
}

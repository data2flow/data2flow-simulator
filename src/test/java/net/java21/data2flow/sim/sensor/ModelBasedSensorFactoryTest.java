package net.java21.data2flow.sim.sensor;

import net.java21.data2flow.sim.catalog.domain.BuiltinCatalog;
import net.java21.data2flow.sim.catalog.domain.DeviceTypeDef;
import net.java21.data2flow.sim.catalog.domain.MetricDef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ModelBasedSensorFactoryTest {

    /** DEV-03 모델 정의(core-api BuiltinCatalog) 그대로: 측정 항목·단위, 보고 주기 600초 */
    static Stream<Arguments> models() {
        return Stream.of(
                Arguments.of("EM300-TH", List.of("temperature", "humidity")),
                Arguments.of("EM320-TH", List.of("temperature", "humidity", "battery")),
                Arguments.of("EM500-CO2", List.of("co2", "pressure", "temperature", "humidity")),
                Arguments.of("AM103", List.of("co2", "temperature", "humidity", "battery")),
                Arguments.of("AM107", List.of("co2", "tvoc", "pressure", "illumination", "infrared", "activity", "temperature", "humidity")),
                Arguments.of("WS302", List.of("LAeq", "LAI", "LAImax", "battery")));
    }

    static final Map<String, String> UNITS = Map.ofEntries(Map.entry("temperature", "℃"), Map.entry("humidity", "%"),
            Map.entry("co2", "ppm"), Map.entry("battery", "%"), Map.entry("pressure", "hPa"), Map.entry("illumination", "lux"),
            Map.entry("LAeq", "dB"), Map.entry("LAI", "dB"), Map.entry("LAImax", "dB"));

    @ParameterizedTest
    @MethodSource("models")
    @DisplayName("[SIM-02.01][AT-SIM-01.4][TC-SIM-014] 아카데미 실측 6종의 측정 항목·단위·보고 주기가 DEV-03 모델 정의와 같다")
    void academyModels(String code, List<String> metrics) {
        DeviceTypeDef type = BuiltinCatalog.type(BuiltinCatalog.academyTypeKey(code)).orElseThrow();
        assertThat(type.linkedModelCode()).isEqualTo(code);
        assertThat(type.metrics().stream().map(MetricDef::key).toList()).containsExactlyElementsOf(metrics);
        assertThat(type.defaultReportIntervalSec()).isEqualTo(600);
        for (MetricDef m : type.metrics()) {
            assertThat(m.unit()).as(m.key()).isEqualTo(UNITS.get(m.key()));
        }
    }
}

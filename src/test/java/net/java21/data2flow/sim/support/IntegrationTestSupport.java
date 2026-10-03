package net.java21.data2flow.sim.support;

import net.java21.data2flow.contracts.identity.DataflowHeaders;
import net.java21.data2flow.contracts.message.MessageCodec;
import net.java21.data2flow.contracts.messaging.MessagingNames;
import net.java21.data2flow.sim.run.service.SimulationExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 통합 시험 기반(design/testing/backend.md "서비스 전체 *IT"): 실제 PostgreSQL 18·RabbitMQ 3.13 Stream으로 서비스 전체를 띄운다.
 * 시험마다 시뮬레이터 테이블을 비우고(기본 카탈로그 제외), 시계를 T0로 되돌리고, 실행기를 비운다. 실행기는 시험이 직접 돌린다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@ActiveProfiles("test")
@Import(TestBeans.class)
public abstract class IntegrationTestSupport {

    protected static final long ORG = 1;
    protected static final long USER = 7;
    protected static final MessageCodec CODEC = MessageCodec.create();

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestInfrastructure.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", TestInfrastructure.POSTGRES::getUsername);
        registry.add("spring.datasource.password", TestInfrastructure.POSTGRES::getPassword);
        registry.add("spring.rabbitmq.host", TestInfrastructure::rabbitHost);
        registry.add("spring.rabbitmq.port", TestInfrastructure::amqpPort);
        registry.add("spring.rabbitmq.username", () -> "guest");
        registry.add("spring.rabbitmq.password", () -> "guest");
        registry.add("data2flow.sim.stream.host", TestInfrastructure::rabbitHost);
        registry.add("data2flow.sim.stream.port", TestInfrastructure::streamPort);
        registry.add("data2flow.sim.stream.use-configured-address", () -> "true");
    }

    @Autowired
    protected MutableClock clock;
    @Autowired
    protected JdbcClient jdbc;
    @Autowired
    protected RabbitTemplate rabbit;
    @Autowired
    protected RabbitAdmin rabbitAdmin;
    @Autowired
    protected SimulationExecutor executor;
    @Autowired
    protected RecordingRawOutput raw;
    @LocalServerPort
    protected int port;

    protected String eventsQueue;
    private final List<JsonNode> received = new ArrayList<>();

    @BeforeEach
    void resetState() {
        executor.reset();
        clock.set(MutableClock.T0);
        jdbc.sql("""
                TRUNCATE data2flow_sim.device_commands, data2flow_sim.leases, data2flow_sim.faults, data2flow_sim.replays,
                    data2flow_sim.whatif_studies, data2flow_sim.runs, data2flow_sim.scenarios, data2flow_sim.devices,
                    data2flow_sim.profiles, data2flow_sim.space_physics
                """).update();
        jdbc.sql("DELETE FROM data2flow_sim.device_types WHERE NOT builtin").update();
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(60)).until(raw::ready);
        raw.failing(false);
        raw.clear();
        Queue queue = new Queue("test.sim.events." + UUID.randomUUID(), false, false, true);
        rabbitAdmin.declareQueue(queue);
        rabbitAdmin.declareBinding(BindingBuilder.bind(queue).to(new TopicExchange(MessagingNames.EXCHANGE_EVENTS, true, false)).with("#"));
        eventsQueue = queue.getName();
        received.clear();
    }

    @AfterEach
    void cleanup() {
        if (eventsQueue != null) {
            rabbitAdmin.deleteQueue(eventsQueue);
        }
    }

    /** 내부 API 호출(core가 부르는 모양: X-CALLER-SERVICE + 신원 헤더) */
    protected RestClient api() {
        return RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultHeader(DataflowHeaders.CALLER_SERVICE, "data2flow-core-api")
                .defaultHeader(DataflowHeaders.USER_ID, Long.toString(USER))
                .defaultHeader(DataflowHeaders.ORG_ID, Long.toString(ORG))
                .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> {
                })
                .build();
    }

    protected RestClient api(long organizationId) {
        return RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultHeader(DataflowHeaders.CALLER_SERVICE, "data2flow-core-api")
                .defaultHeader(DataflowHeaders.USER_ID, Long.toString(USER))
                .defaultHeader(DataflowHeaders.ORG_ID, Long.toString(organizationId))
                .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> {
                })
                .build();
    }

    /** action virtual 드라이버가 부르는 모양(신원 헤더 없음) */
    protected RestClient driver() {
        return RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultHeader(DataflowHeaders.CALLER_SERVICE, "data2flow-action")
                .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> {
                })
                .build();
    }

    protected Result post(String path, Object body) {
        return exchange(api().post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body));
    }

    protected Result post(RestClient client, String path, Object body) {
        return exchange(client.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body));
    }

    protected Result put(String path, Object body) {
        return exchange(api().put().uri(path).contentType(MediaType.APPLICATION_JSON).body(body));
    }

    protected Result patch(String path, Object body) {
        return exchange(api().patch().uri(path).contentType(MediaType.APPLICATION_JSON).body(body));
    }

    protected Result get(String path) {
        return exchange(api().get().uri(path));
    }

    protected Result delete(String path) {
        return exchange(api().delete().uri(path));
    }

    protected Result exchange(RestClient.RequestHeadersSpec<?> spec) {
        return spec.exchange((req, res) -> {
            byte[] bytes = res.getBody().readAllBytes();
            JsonNode body = bytes.length == 0 ? null : CODEC.mapper().readTree(bytes);
            return new Result(res.getStatusCode().value(), body);
        });
    }

    /** 응답 */
    public record Result(int status, JsonNode body) {
        public JsonNode response() {
            return body.get("response");
        }

        public String code() {
            return body.path("header").path("resultCode").asString();
        }
    }

    /** 지금까지 받은 도메인 이벤트(라우팅 키로 거름, null이면 전부) */
    protected List<JsonNode> events(String type) {
        org.springframework.amqp.core.Message m;
        while ((m = rabbit.receive(eventsQueue, 100)) != null) {
            received.add(CODEC.mapper().readTree(m.getBody()));
        }
        return received.stream().filter(e -> type == null || type.equals(e.path("type").asString())).toList();
    }

    /** 실행기 n회: 매번 시계를 step만큼 움직인다 */
    protected void rounds(int n, Duration step) {
        for (int i = 0; i < n; i++) {
            clock.advance(step);
            executor.round();
        }
    }

    // ───────────── 준비 도우미 ─────────────

    protected void space(long spaceId, String preset) {
        Result r = put("/internal/sim/spaces/" + spaceId, java.util.Map.of("name", "공간 " + spaceId, "preset", preset));
        org.assertj.core.api.Assertions.assertThat(r.status()).as(String.valueOf(r.body())).isEqualTo(200);
    }

    protected void device(long deviceId, String typeKey, long spaceId, String name) {
        Result r = post("/internal/sim/devices", java.util.Map.of("deviceId", deviceId, "typeKey", typeKey, "spaceId", spaceId, "name", name));
        org.assertj.core.api.Assertions.assertThat(r.status()).as(String.valueOf(r.body())).isEqualTo(201);
    }

    protected long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}

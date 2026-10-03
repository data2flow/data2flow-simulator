# data2flow-simulator

가상 공간 물리 모델, 가상 센서·장비, 시나리오·시간 가속, 장애 주입, 결정적 재현, 하트비트 카나리를 맡는 서비스입니다. simulator를 개발·운영하는 백엔드 개발자와, core-api·action에서 이 서비스를 부르는 개발자가 읽습니다. 다 읽으면 로컬에서 띄우고, 내부 API로 가상 강의실을 만들어 "폭염 오후"를 x60으로 돌리고, 같은 시드로 결과를 재현할 수 있습니다.

- 관련 스펙: SIM(M3), ING-07.05·NFR-02.10(하트비트). 정본은 비공개 저장소 `data2flow-docs`의 `spec/SIM-virtual-environment.md`, `design/api/SIM-api.md`, `design/erd/sim.md`
- 패키지: `net.java21.data2flow.sim` · Spring Boot 4.1.1 · Java 21 · Maven Wrapper
- 포트: API 8080(내부 전용, ClusterIP), actuator 8081(프로브·지표 전용)
- 저장: PostgreSQL 스키마 `data2flow_sim`(Flyway, staging만 migrate·prod·로컬은 validate, ADR-030)

## 1. 하는 일

```
시나리오(재실·외기·문/창문·장비 조작·장애) ─▶ 공간 물리 모델(온도·CO2·습도·PM2.5·조도·소음)
                 ▲                                          │ 보고 주기·지터·잡음·분해능·지연·드리프트
   API-SIM-30 명령(action virtual 드라이버)                   ▼
   → 반응 지연 뒤 물리에 반영                    가상 센서 payload(ChirpStack v4 / generic JSON / single-value)
   → device.command.ack · device.state.reported            │
                                                            ▼
                                        data2flow.raw(RawEnvelope, sourceType=SIMULATION, virtual=true, simRunId)
```

- **출력 경로는 내부 직접 주입뿐**입니다(SIM-02.07, BR-SIM-01). 공용 MQTT 브로커(`iot-data.java21.net`)와 공용 ChirpStack에는 어떤 경우에도 연결·발행하지 않습니다(CLAUDE.md §5). MQTT 클라이언트 의존성 자체가 없고 ArchUnit으로 막습니다. 출력 경로 `PLATFORM_MQTT`는 `SIM_PLATFORM_BROKER_UNAVAILABLE`로 거부합니다(M7).
- **결정적 재현(SIM-08.03):** 난수는 (시드, 기기 이름, 용도, 순번)으로만 뽑습니다(`SimRandom`). 생성 값은 (기기, 측정 키, 시뮬레이션 시각, 값)의 연쇄 SHA-256(`runs.result.dataSha256`)으로 남고, 같은 시드면 가속(x10·x60)·실행기 주기·인스턴스 교체(kill -9 뒤 체크포인트 이어 실행)와 상관없이 같습니다. 실행은 언제나 장비 OFF·배터리 100%에서 시작합니다.
- **실행기:** 1초마다 RUNNING 실행을 소유(`leases`)하고 가속 기준점에서 목표 틱(기본 10초 틱, x60이면 실제 1초에 6틱)까지 엔진을 돌립니다. 원본은 publisher confirm을 모두 받은 뒤에만 다음으로 가고, 실패하면 마지막 정상 상태에서 같은 메시지를 다시 만듭니다(수집 단계가 dedupKey로 거름). 10틱마다 체크포인트(`runs.checkpoint`).
- **상시 환경:** 보고 방식 ALWAYS 기기는 조직별로 실제 시간(x1)으로 돌고, 시나리오가 쓰는 공간은 그 실행이 맡습니다(BR-SIM-17).
- **배포 조직만(ADR-030):** staging·prod가 DB 하나를 함께 쓰므로 상시 환경·실행 넘겨받기·하트비트는 `DATA2FLOW_SIM_ORGANIZATION_IDS` 또는 core 소스 실행 설정(API-DSC-50)의 SIMULATION 소스 조직만 돌립니다. core에 닿지 않으면 아무 조직도 돌리지 않습니다.

## 2. 내부 API

외부(BFF) 경로는 core-api가 `/api/v1/core/sim/**`로 노출하고 권한을 확인한 뒤 아래를 부릅니다(SIM-api 머리말). 모든 요청에 `X-CALLER-SERVICE`가 필요하고(없으면 401, ADR-021), 조직은 `X-ORG-ID`(+`X-USER-ID`) 또는 `organizationId` 쿼리로 줍니다. 응답은 공통 봉투 `{header, response}`입니다.

| API | 경로 |
|---|---|
| API-SIM-30·32 (action) | `POST /internal/sim/devices/{device-id}/commands` → 202, `GET /internal/sim/devices/{device-id}/state` |
| API-SIM-33 | `POST /internal/sim/devices`, `POST /internal/sim/devices/batch-create`, `GET·PATCH·DELETE /internal/sim/devices/{device-id}` |
| API-SIM-34 | `POST /internal/sim/runs`, `GET·PATCH /internal/sim/runs/{run-id}`, `POST …/{run-id}/start·pause·resume·stop·reset`, `GET·PATCH …/{run-id}/report` |
| API-SIM-01·02·06·08 | `GET /internal/sim/overview`, `GET /internal/sim/catalog`, `POST /internal/sim/kits/{kit-key}/place`, `/internal/sim/profiles/**` |
| API-SIM-10·11·24 | `GET /internal/sim/spaces`, `PUT·GET·DELETE /internal/sim/spaces/{space-id}`, `PUT …/{space-id}/sandbox`, `POST /internal/sim/preview` |
| API-SIM-12·13·18·26·27 | `/internal/sim/scenarios/**`(복제 `…/clone`, 내보내기 `…/export?format=json|yaml`), `GET /internal/sim/presets`, `POST /internal/sim/presets/{preset-key}/prepare`, `POST /internal/sim/imports`(multipart `file`, `idMap`) |
| API-SIM-20·21 | `POST·GET /internal/sim/faults`, `POST /internal/sim/faults/{fault-id}/cancel` |

공간·기기의 기준 정보(이름·계층·모델·`virtual=true`)는 core가 먼저 만들고 그 ID로 여기를 부릅니다(사가 2단계). 기기 생성 요청은 문서 필드에 더해 `name`, `externalId`(없으면 `5a1d` + 기기 ID 16진수 12자리), `sourceId`(없으면 배포 조직의 SIM 소스)를 받습니다.

## 3. 메시지

| 채널 | 종류 | 페이로드 |
|---|---|---|
| Super Stream `data2flow.raw` | EVT-ING-01 `RawEnvelope` v1 | `sourceType=SIMULATION`, `virtual=true`, `simRunId`(상시 환경·하트비트는 null), `ingressInstance`=파드 이름, `receivedAt`=시뮬레이션 전송 시각(SIMULATED 정책), dedupKey `chirpstack:{deduplicationId}` 등 |
| `data2flow.events` `sim.run.*` | EVT-SIM-01 | `{organizationId, runId, scenarioId, status, simClock, accelerationEffective, partial?, failureReason?, at}` |
| `data2flow.events` `sim.fault.started·ended` | EVT-SIM-02 | `{organizationId, runId?, faultId, kind, targetType, targetId, simFrom, simTo?, params}` |
| `data2flow.events` `device.command.ack` | EVT-SIM-03 = EVT-ACT-06 | `{commandId, deviceId, result: ACKED|FAILED, reason?, at, virtual: true}` |
| `data2flow.events` `device.state.reported` | EVT-SIM-03 = EVT-ACT-07 | `{deviceId, version, capabilities:{capability:{attr:value}}, reportedAt, virtual: true}` |

이벤트 봉투는 contracts `DomainEvent`, 종류는 contracts `EventType`(`SIM_RUN_*`·`SIM_FAULT_*`·`DEVICE_COMMAND_ACK`·`DEVICE_STATE_REPORTED`), 페이로드는 contracts `message.event`의 `SimRunChanged`·`SimFaultLabel`·`DeviceCommandAck`·`DeviceStateReported`입니다(JSON 모양은 위 표 그대로). 초기화(reset)는 `sim.run.reset`으로 냅니다.

## 4. 빌드와 실행

```bash
./mvnw verify                 # 단위·통합 테스트(Testcontainers PostgreSQL 18·RabbitMQ 3.13 Stream) + 커버리지 80% 검사
./mvnw spring-boot:run        # 로컬 실행(프로필 local, 실행기 꺼짐)
```

로컬은 s3 공용 DB·s4 RabbitMQ 터널에 붙으므로(`design/deployment.md` §8.2) 실행기(`data2flow.sim.executor.enabled`)를 기본으로 끕니다. 켤 때는 `DATA2FLOW_SIM_ORGANIZATION_IDS`에 개발용 조직만 넣어 운영 실행을 넘겨받지 않게 합니다.

| 환경변수 | 기본값 | 설명 |
|---|---|---|
| `DATA2FLOW_DB_*`, `DATA2FLOW_RABBITMQ_*` | – | DB·RabbitMQ(AMQP 5672·Stream 5552) 접속(공통 ConfigMap·Secret) |
| `DATA2FLOW_SIM_ORGANIZATION_IDS` | 비움 | 시뮬레이션할 조직. 비우면 core 배포 조직 |
| `DATA2FLOW_CORE_URI` | `http://data2flow-core-api` | core 내부 API |
| `DATA2FLOW_SIM_HEARTBEAT_ENABLED` | `false` | 하트비트 카나리. core에 시스템 가상 기기 `__heartbeat__`가 등록된 뒤 켠다 |

공통 라이브러리 `data2flow-contracts`는 GitHub Packages에 있어서 읽기에도 토큰이 필요합니다. `~/.m2/settings.xml`에 서버 `github`(사용자 이름 + `read:packages` 권한 토큰)를 넣거나, `data2flow-contracts`를 받아 `./mvnw install`로 로컬 저장소에 설치합니다.

## 5. 작업 규칙

스펙 ID에서 시작하고(인수 테스트 → 테스트 케이스 → 구현), 브랜치·PR·테스트 이름에 스펙 ID를 남깁니다(`[SIM-xx.xx][AT-SIM-xx.x][TC-SIM-nnn]`). 1.0 전에는 `main` + `feat/<스펙ID>-<요약>`, 1.0 뒤에는 버전 브랜치 `feature/vX.Y`를 씁니다(ADR-039). 시험은 `Thread.sleep` 대신 `MutableClock`을 움직이고 실행기 `round()`를 직접 부릅니다.

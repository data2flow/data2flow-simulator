-- data2flow_sim 초기 스키마(소유: data2flow-simulator). 정본 초안: data2flow-docs design/erd/ddl/32-sim.sql
-- 초안과 다른 점(erd/sim.md §4에 반영): devices.name·external_id·source_id(수집 경로가 기기를 찾는 키), runs.plan(시작 때 고정한 실행 계획),
-- leases(실행·상시 환경 소유 인스턴스), device_commands(가상 장비 명령 수신함, API-SIM-30)
-- 근거: design/erd/sim.md, spec/detail/SIM/domain-model.md
-- 가상 공간·기기의 기준 정보(이름, 공간 계층, 모델, 기능)는 data2flow_core(virtual=true)에 있고, 여기에는 시뮬레이션 전용 설정·상태만 둔다.

CREATE SCHEMA IF NOT EXISTS data2flow_sim;

-- ───────────── SimCatalog ─────────────
CREATE TABLE data2flow_sim.device_types (
  id                     bigint GENERATED ALWAYS AS IDENTITY,
  organization_id        bigint      NOT NULL DEFAULT 0,
  key                    varchar(40) NOT NULL,
  name                   varchar(80) NOT NULL,
  category               varchar(8)  NOT NULL,
  builtin                boolean     NOT NULL DEFAULT false,
  metrics                jsonb,
  capabilities           text[],
  property_defs          jsonb       NOT NULL DEFAULT '[]'::jsonb,
  physics_effects        jsonb,
  linked_model_code      varchar(64),
  default_payload_format varchar(16) NOT NULL,
  version                integer     NOT NULL DEFAULT 0,
  created_by             bigint,
  updated_by             bigint,
  created_at             timestamptz NOT NULL DEFAULT now(),
  updated_at             timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_device_types PRIMARY KEY (id),
  CONSTRAINT uq_device_types_organization_id_key UNIQUE (organization_id, key),
  CONSTRAINT ck_device_types_category CHECK (category IN ('SENSOR','ACTUATOR')),
  CONSTRAINT ck_device_types_default_payload_format CHECK (default_payload_format IN ('CHIRPSTACK_V4','GENERIC_JSON','SINGLE_VALUE')),
  CONSTRAINT ck_device_types_property_defs CHECK (jsonb_array_length(property_defs) <= 50),
  CONSTRAINT ck_device_types_builtin_org CHECK (NOT builtin OR organization_id = 0)
);
COMMENT ON COLUMN data2flow_sim.device_types.organization_id IS '플랫폼 기본 유형(builtin)은 0(erd/README §5). 도메인 문서의 NULL을 0으로 바꿈';

CREATE TABLE data2flow_sim.profiles (
  id              bigint GENERATED ALWAYS AS IDENTITY,
  organization_id bigint      NOT NULL,
  name            varchar(80) NOT NULL,
  type_id         bigint      NOT NULL,
  overrides       jsonb       NOT NULL DEFAULT '{}'::jsonb,
  version         integer     NOT NULL DEFAULT 0,
  created_by      bigint,
  updated_by      bigint,
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_profiles PRIMARY KEY (id),
  CONSTRAINT fk_profiles_type_id FOREIGN KEY (type_id) REFERENCES data2flow_sim.device_types (id) ON DELETE RESTRICT,
  CONSTRAINT uq_profiles_organization_id_name UNIQUE (organization_id, name)
);
COMMENT ON COLUMN data2flow_sim.profiles.overrides IS '바꾼 특성만 저장(BR-SIM-02)';

CREATE TABLE data2flow_sim.kits (
  id                       bigint GENERATED ALWAYS AS IDENTITY,
  organization_id          bigint      NOT NULL DEFAULT 0,
  key                      varchar(40) NOT NULL,
  name                     varchar(80) NOT NULL,
  builtin                  boolean     NOT NULL DEFAULT false,
  items                    jsonb       NOT NULL,
  suggested_flow_templates text[],
  created_at               timestamptz NOT NULL DEFAULT now(),
  updated_at               timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_kits PRIMARY KEY (id),
  CONSTRAINT uq_kits_organization_id_key UNIQUE (organization_id, key)
);

-- ───────────── SimSpace ─────────────
CREATE TABLE data2flow_sim.space_physics (
  space_id            bigint      NOT NULL,
  organization_id     bigint      NOT NULL,
  preset              varchar(16) NOT NULL,
  area_m2             real        NOT NULL,
  height_m            real        NOT NULL,
  u_value             real        NOT NULL,
  envelope_m2         real        NOT NULL,
  window_m2           real,
  window_orientation  varchar(8),
  solar_gain_factor   real        NOT NULL,
  initial_state       jsonb       NOT NULL,
  outdoor_linked      boolean     NOT NULL DEFAULT false,
  outdoor_co2_ppm     real        NOT NULL DEFAULT 420,
  per_person          jsonb       NOT NULL,
  background_noise_db real        NOT NULL DEFAULT 30,
  noise_std           jsonb       NOT NULL DEFAULT '{}'::jsonb,
  sandbox             boolean     NOT NULL DEFAULT false,
  version             integer     NOT NULL DEFAULT 0,
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_space_physics PRIMARY KEY (space_id),
  CONSTRAINT ck_space_physics_preset CHECK (preset IN ('CLASSROOM','OFFICE','MEETING','CUSTOM')),
  CONSTRAINT ck_space_physics_area_m2 CHECK (area_m2 BETWEEN 1 AND 5000),
  CONSTRAINT ck_space_physics_height_m CHECK (height_m BETWEEN 2 AND 20),
  CONSTRAINT ck_space_physics_u_value CHECK (u_value BETWEEN 0.1 AND 6.0),
  CONSTRAINT ck_space_physics_solar_gain_factor CHECK (solar_gain_factor BETWEEN 0 AND 1)
);
CREATE INDEX ix_space_physics_organization_id ON data2flow_sim.space_physics (organization_id);
COMMENT ON COLUMN data2flow_sim.space_physics.space_id IS 'data2flow_core.spaces.id(virtual=true) 값을 그대로 기본 키로 씀(FK 없음)';

CREATE TABLE data2flow_sim.devices (
  device_id                bigint      NOT NULL,
  organization_id          bigint      NOT NULL,
  type_id                  bigint      NOT NULL,
  profile_id               bigint,
  space_id                 bigint      NOT NULL,
  overrides                jsonb       NOT NULL DEFAULT '{}'::jsonb,
  report_mode              varchar(16) NOT NULL DEFAULT 'ALWAYS',
  metric_sources           jsonb,
  report_interval_sec      integer     NOT NULL,
  jitter_pct               real        NOT NULL DEFAULT 0,
  battery_pct              real,
  battery_drain_per_report real,
  name                     varchar(200) NOT NULL,
  external_id              varchar(64) NOT NULL,
  source_id                bigint      NOT NULL,
  payload_format           varchar(16) NOT NULL,
  output_path              varchar(16) NOT NULL DEFAULT 'INTERNAL',
  frame_counter            bigint      NOT NULL DEFAULT 0,
  virtual_gateway_eui      varchar(32),
  response                 jsonb,
  actuator_state           jsonb,
  seed                     bigint,
  version                  integer     NOT NULL DEFAULT 0,
  created_at               timestamptz NOT NULL DEFAULT now(),
  updated_at               timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_devices PRIMARY KEY (device_id),
  CONSTRAINT fk_devices_type_id FOREIGN KEY (type_id) REFERENCES data2flow_sim.device_types (id) ON DELETE RESTRICT,
  CONSTRAINT fk_devices_profile_id FOREIGN KEY (profile_id) REFERENCES data2flow_sim.profiles (id) ON DELETE RESTRICT,
  CONSTRAINT fk_devices_space_id FOREIGN KEY (space_id) REFERENCES data2flow_sim.space_physics (space_id) ON DELETE RESTRICT,
  CONSTRAINT ck_devices_report_mode CHECK (report_mode IN ('ALWAYS','RUN_ONLY')),
  CONSTRAINT ck_devices_report_interval_sec CHECK (report_interval_sec BETWEEN 5 AND 86400),
  CONSTRAINT ck_devices_jitter_pct CHECK (jitter_pct BETWEEN 0 AND 50),
  CONSTRAINT ck_devices_payload_format CHECK (payload_format IN ('CHIRPSTACK_V4','GENERIC_JSON','SINGLE_VALUE')),
  CONSTRAINT ck_devices_output_path CHECK (output_path IN ('INTERNAL','PLATFORM_MQTT'))
);
CREATE INDEX ix_devices_organization_id_space_id ON data2flow_sim.devices (organization_id, space_id);
CREATE UNIQUE INDEX uq_devices_source_id_external_id ON data2flow_sim.devices (source_id, external_id);
CREATE INDEX ix_devices_virtual_gateway_eui ON data2flow_sim.devices (virtual_gateway_eui) WHERE virtual_gateway_eui IS NOT NULL;
COMMENT ON COLUMN data2flow_sim.devices.device_id IS 'data2flow_core.devices.id(virtual=true) 값을 그대로 기본 키로 씀(FK 없음)';
COMMENT ON COLUMN data2flow_sim.devices.output_path IS 'PLATFORM_MQTT는 테스트 환경 전용. 운영·staging은 INTERNAL만(ADR-031, SIM-02.07)';

-- ───────────── Scenario ─────────────
CREATE TABLE data2flow_sim.scenarios (
  id              bigint GENERATED ALWAYS AS IDENTITY,
  organization_id bigint      NOT NULL,
  name            varchar(80) NOT NULL,
  space_ids       bigint[]    NOT NULL,
  sim_start_at    timestamptz NOT NULL,
  duration_sec    integer     NOT NULL,
  seed            bigint,
  use_calendar    boolean     NOT NULL DEFAULT false,
  outdoor         jsonb       NOT NULL,
  events          jsonb       NOT NULL DEFAULT '[]'::jsonb,
  expectations    jsonb,
  preset_key      varchar(40),
  schema_version  integer     NOT NULL DEFAULT 1,
  version         integer     NOT NULL DEFAULT 0,
  created_by      bigint,
  updated_by      bigint,
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_scenarios PRIMARY KEY (id),
  CONSTRAINT uq_scenarios_organization_id_name UNIQUE (organization_id, name),
  CONSTRAINT ck_scenarios_duration_sec CHECK (duration_sec BETWEEN 3600 AND 604800),
  CONSTRAINT ck_scenarios_events CHECK (jsonb_array_length(events) <= 2000)
);

-- ───────────── SimRun ─────────────
CREATE TABLE data2flow_sim.runs (
  id                     bigint GENERATED ALWAYS AS IDENTITY,
  organization_id        bigint       NOT NULL,
  scenario_id            bigint,
  kind                   varchar(16)  NOT NULL,
  status                 varchar(16)  NOT NULL DEFAULT 'CREATED',
  acceleration_requested smallint     NOT NULL DEFAULT 1,
  acceleration_effective smallint     NOT NULL DEFAULT 1,
  timestamp_policy       varchar(16)  NOT NULL DEFAULT 'SIMULATED',
  seed                   bigint       NOT NULL,
  notification_policy    varchar(16)  NOT NULL DEFAULT 'PREFIX',
  sim_clock              timestamptz  NOT NULL,
  started_at             timestamptz,
  paused_at              timestamptz,
  finished_at            timestamptz,
  progress_pct           real         NOT NULL DEFAULT 0,
  partial                boolean      NOT NULL DEFAULT false,
  plan                   jsonb,
  checkpoint             jsonb,
  checkpoint_at          timestamptz,
  result                 jsonb,
  retain_until           timestamptz  NOT NULL,
  requested_by           bigint       NOT NULL,
  failure_reason         varchar(500),
  created_at             timestamptz  NOT NULL DEFAULT now(),
  updated_at             timestamptz  NOT NULL DEFAULT now(),
  CONSTRAINT pk_runs PRIMARY KEY (id),
  CONSTRAINT fk_runs_scenario_id FOREIGN KEY (scenario_id) REFERENCES data2flow_sim.scenarios (id) ON DELETE SET NULL,
  CONSTRAINT ck_runs_kind CHECK (kind IN ('SCENARIO','REPLAY','WHATIF')),
  CONSTRAINT ck_runs_status CHECK (status IN ('CREATED','RUNNING','PAUSED','EVALUATING','COMPLETED','STOPPED','FAILED','PURGED')),
  CONSTRAINT ck_runs_acceleration CHECK (acceleration_requested BETWEEN 1 AND 60 AND acceleration_effective BETWEEN 1 AND 60),
  CONSTRAINT ck_runs_timestamp_policy CHECK (timestamp_policy IN ('SIMULATED','WALL_CLOCK')),
  CONSTRAINT ck_runs_notification_policy CHECK (notification_policy IN ('PREFIX','SUPPRESS'))
);
CREATE INDEX ix_runs_organization_id_status_active ON data2flow_sim.runs (organization_id, status) WHERE status IN ('RUNNING','PAUSED','EVALUATING');
CREATE INDEX ix_runs_retain_until ON data2flow_sim.runs (retain_until) WHERE status IN ('COMPLETED','STOPPED','FAILED');
COMMENT ON COLUMN data2flow_sim.runs.checkpoint IS '10 시뮬레이션 틱마다 저장하는 이어 실행 지점(엔진 상태 JSON + 실행 시각 기준점)';
COMMENT ON COLUMN data2flow_sim.runs.plan IS '시작 때 고정한 실행 계획(시나리오·공간 물리·기기 설정). 실행 중 시나리오를 고쳐도 이어 실행 결과가 같다';
COMMENT ON COLUMN data2flow_sim.runs.retain_until IS '기본 종료 + 30일, 연장 최대 1년(BR-SIM-14)';

CREATE TABLE data2flow_sim.faults (
  id              bigint GENERATED ALWAYS AS IDENTITY,
  organization_id bigint      NOT NULL,
  run_id          bigint,
  target_type     varchar(8)  NOT NULL,
  target_id       varchar(64) NOT NULL,
  kind            varchar(24) NOT NULL,
  params          jsonb       NOT NULL DEFAULT '{}'::jsonb,
  sim_from        timestamptz NOT NULL,
  sim_to          timestamptz,
  status          varchar(12) NOT NULL DEFAULT 'SCHEDULED',
  ground_truth    boolean     NOT NULL DEFAULT true,
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_faults PRIMARY KEY (id),
  CONSTRAINT fk_faults_run_id FOREIGN KEY (run_id) REFERENCES data2flow_sim.runs (id) ON DELETE CASCADE,
  CONSTRAINT ck_faults_target_type CHECK (target_type IN ('DEVICE','GATEWAY')),
  CONSTRAINT ck_faults_kind CHECK (kind IN ('STUCK','SPIKE','DRIFT','DROPOUT','INTERMITTENT','BATTERY_DRAIN','OUT_OF_RANGE','GATEWAY_DOWN','DUPLICATE','REORDER','DELAY','MALFORMED')),
  CONSTRAINT ck_faults_status CHECK (status IN ('SCHEDULED','ACTIVE','ENDED','CANCELLED'))
);
CREATE INDEX ix_faults_organization_id_run_id ON data2flow_sim.faults (organization_id, run_id);
COMMENT ON TABLE data2flow_sim.faults IS '가상 대상에만 주입, 정답 라벨 기록(BR-SIM-15)';

CREATE TABLE data2flow_sim.replays (
  run_id          bigint      NOT NULL,
  organization_id bigint      NOT NULL,
  source          jsonb       NOT NULL,
  time_shift      jsonb       NOT NULL,
  clone_map       jsonb       NOT NULL DEFAULT '{}'::jsonb,
  total           bigint      NOT NULL DEFAULT 0,
  sent            bigint      NOT NULL DEFAULT 0,
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_replays PRIMARY KEY (run_id),
  CONSTRAINT fk_replays_run_id FOREIGN KEY (run_id) REFERENCES data2flow_sim.runs (id) ON DELETE CASCADE
);
COMMENT ON COLUMN data2flow_sim.replays.clone_map IS '원래 기기 → 가상 복제 기기(BR-SIM-16)';

CREATE TABLE data2flow_sim.whatif_studies (
  id               bigint GENERATED ALWAYS AS IDENTITY,
  organization_id  bigint      NOT NULL,
  name             varchar(80) NOT NULL,
  base_scenario_id bigint      NOT NULL,
  seed             bigint      NOT NULL,
  acceleration     smallint    NOT NULL DEFAULT 60,
  variants         jsonb       NOT NULL,
  comparison       jsonb,
  version          integer     NOT NULL DEFAULT 0,
  created_by       bigint,
  updated_by       bigint,
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_whatif_studies PRIMARY KEY (id),
  CONSTRAINT fk_whatif_studies_base_scenario_id FOREIGN KEY (base_scenario_id) REFERENCES data2flow_sim.scenarios (id) ON DELETE RESTRICT,
  CONSTRAINT ck_whatif_studies_acceleration CHECK (acceleration BETWEEN 1 AND 60),
  CONSTRAINT ck_whatif_studies_variants CHECK (jsonb_array_length(variants) BETWEEN 2 AND 5)
);
COMMENT ON COLUMN data2flow_sim.whatif_studies.variants IS '변형 2~5개(SIM-api What-if 요청 규칙)';

-- ───────────── 실행 소유(인스턴스 간 넘겨받기) ─────────────
CREATE TABLE data2flow_sim.leases (
  name        varchar(80) NOT NULL,
  owner       varchar(120) NOT NULL,
  lease_until timestamptz NOT NULL,
  updated_at  timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_leases PRIMARY KEY (name)
);
COMMENT ON TABLE data2flow_sim.leases IS '실행(run:{id})·상시 환경(ambient:{org})을 돌리는 인스턴스. 기한이 지나면 다른 인스턴스가 체크포인트에서 이어 실행(TC-SIM-045)';

-- ───────────── 가상 장비 명령 수신함(API-SIM-30) ─────────────
CREATE TABLE data2flow_sim.device_commands (
  id              bigint GENERATED ALWAYS AS IDENTITY,
  organization_id bigint      NOT NULL,
  device_id       bigint      NOT NULL,
  command_id      varchar(64) NOT NULL,
  capability      varchar(64) NOT NULL,
  command         varchar(64) NOT NULL,
  args            jsonb       NOT NULL DEFAULT '{}'::jsonb,
  desired_version bigint,
  status          varchar(12) NOT NULL DEFAULT 'PENDING',
  received_at     timestamptz NOT NULL,
  processed_at    timestamptz,
  created_at      timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_device_commands PRIMARY KEY (id),
  CONSTRAINT ck_device_commands_status CHECK (status IN ('PENDING','TAKEN','APPLIED'))
);
CREATE INDEX ix_device_commands_device_id_status ON data2flow_sim.device_commands (device_id, status) WHERE status = 'PENDING';
CREATE INDEX ix_device_commands_created_at ON data2flow_sim.device_commands (created_at);
COMMENT ON TABLE data2flow_sim.device_commands IS 'action virtual 드라이버가 보낸 명령. 장비를 시뮬레이션하는 인스턴스가 다음 틱에 가져가 적용하고 ack·reported를 발행한다. 7일 뒤 정리';

-- ============================================================
-- ICU 多床位监护与早期预警系统 - 数据库初始化
-- 13 个核心实体 + TimescaleDB 连续聚合（原始波形 / 衍生指标双管道）
-- ============================================================

CREATE EXTENSION IF NOT EXISTS timescaledb;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- 1. 医院
CREATE TABLE IF NOT EXISTS hospital (
    id           BIGSERIAL PRIMARY KEY,
    code         VARCHAR(64) UNIQUE NOT NULL,
    name         VARCHAR(128) NOT NULL,
    created_at   TIMESTAMPTZ DEFAULT now()
);

-- 2. 病区
CREATE TABLE IF NOT EXISTS ward (
    id           BIGSERIAL PRIMARY KEY,
    hospital_id  BIGINT NOT NULL REFERENCES hospital(id),
    code         VARCHAR(64) NOT NULL,
    name         VARCHAR(128) NOT NULL,
    UNIQUE(hospital_id, code)
);

-- 3. 床位
CREATE TABLE IF NOT EXISTS bed (
    id           BIGSERIAL PRIMARY KEY,
    ward_id      BIGINT NOT NULL REFERENCES ward(id),
    code         VARCHAR(64) NOT NULL,
    status       VARCHAR(16) DEFAULT 'IDLE', -- IDLE/OCCUPIED/CLEANING
    patient_id   BIGINT,
    UNIQUE(ward_id, code)
);
CREATE INDEX idx_bed_patient ON bed(patient_id);

-- 4. 患者档案（敏感字段加密/脱敏）
CREATE TABLE IF NOT EXISTS patient (
    id            BIGSERIAL PRIMARY KEY,
    hospital_id   BIGINT NOT NULL REFERENCES hospital(id),
    mrn           VARCHAR(64) UNIQUE NOT NULL,        -- 病案号
    name_enc      BYTEA NOT NULL,                     -- 加密姓名
    name_mask     VARCHAR(32) NOT NULL,               -- 脱敏展示，如 张*
    gender        VARCHAR(8),
    birth_date    DATE,
    id_card_enc   BYTEA,                              -- 身份证加密
    admission_at  TIMESTAMPTZ,
    diagnosis     TEXT,
    created_at    TIMESTAMPTZ DEFAULT now()
);

-- 5. 监护设备
CREATE TABLE IF NOT EXISTS monitor_device (
    id           BIGSERIAL PRIMARY KEY,
    bed_id       BIGINT REFERENCES bed(id),
    vendor       VARCHAR(32) NOT NULL,                -- MINDRAY/PHILIPS/GE/OTHER
    model        VARCHAR(64),
    serial_no    VARCHAR(128) UNIQUE NOT NULL,
    protocol     VARCHAR(32) NOT NULL,                -- HL7_V2 / IHE_PCD / PRIVATE_TCP
    ip           VARCHAR(64),
    online       BOOLEAN DEFAULT FALSE,
    last_seen_at TIMESTAMPTZ
);

-- 6. 设备通道（一个监护仪有多个通道：ECG、SPO2、IBP、NIBP、TEMP 等）
CREATE TABLE IF NOT EXISTS device_channel (
    id            BIGSERIAL PRIMARY KEY,
    device_id     BIGINT NOT NULL REFERENCES monitor_device(id),
    code          VARCHAR(32) NOT NULL,               -- ECG_II / SPO2 / IBP_M / NIBP / TEMP / RESP / HR
    display_name  VARCHAR(64) NOT NULL,
    unit          VARCHAR(16),
    sample_hz     INT DEFAULT 1,                      -- 采样频率
    UNIQUE(device_id, code)
);

-- 7a. 原始波形管道（TimescaleDB hypertable，1Hz~高频）
-- 注意：原始波形量大，独立 schema；列存压缩
CREATE TABLE IF NOT EXISTS sample_raw (
    time         TIMESTAMPTZ NOT NULL,
    channel_id   BIGINT NOT NULL,
    bed_id       BIGINT NOT NULL,
    value_num    DOUBLE PRECISION,                    -- 数值型通道
    value_wav    BYTEA,                               -- 波形通道（二进制块）
    quality      SMALLINT DEFAULT 100                 -- 信号质量 0-100
);
SELECT create_hypertable('sample_raw', 'time', if_not_exists => TRUE, chunk_time_interval => INTERVAL '1 hour');
CREATE INDEX IF NOT EXISTS idx_sample_raw_bed_ch_time ON sample_raw (bed_id, channel_id, time DESC);

-- 7b. 衍生指标管道（连续聚合视图 - 1min / 5min / 1hour）
CREATE TABLE IF NOT EXISTS sample_metric (
    time         TIMESTAMPTZ NOT NULL,
    channel_id   BIGINT NOT NULL,
    bed_id       BIGINT NOT NULL,
    patient_id   BIGINT,
    avg_value    DOUBLE PRECISION,
    min_value    DOUBLE PRECISION,
    max_value    DOUBLE PRECISION,
    last_value   DOUBLE PRECISION,
    sample_count INT
);
SELECT create_hypertable('sample_metric', 'time', if_not_exists => TRUE, chunk_time_interval => INTERVAL '1 day');
CREATE UNIQUE INDEX IF NOT EXISTS uq_sample_metric ON sample_metric (time, channel_id, bed_id);
CREATE INDEX IF NOT EXISTS idx_sample_metric_bed_ch_time ON sample_metric (bed_id, channel_id, time DESC);

-- 连续聚合：1 分钟粒度（用于趋势/告警/评分）
CREATE MATERIALIZED VIEW IF NOT EXISTS sample_metric_1min
WITH (timescaledb.continuous) AS
SELECT
    time_bucket('1 minute', time) AS bucket,
    channel_id,
    bed_id,
    patient_id,
    AVG(avg_value)    AS avg_value,
    MIN(min_value)    AS min_value,
    MAX(max_value)    AS max_value,
    AVG(last_value)   AS last_value,
    SUM(sample_count) AS sample_count
FROM sample_metric
GROUP BY bucket, channel_id, bed_id, patient_id
WITH NO DATA;

SELECT add_continuous_aggregate_policy('sample_metric_1min',
    start_offset => INTERVAL '2 hours',
    end_offset   => INTERVAL '1 minute',
    schedule_interval => INTERVAL '1 minute',
    if_not_exists => TRUE);

-- 8. 评分规则（Drools 规则动态加载）
CREATE TABLE IF NOT EXISTS scoring_rule (
    id           BIGSERIAL PRIMARY KEY,
    hospital_id  BIGINT NOT NULL REFERENCES hospital(id),
    code         VARCHAR(64) NOT NULL,                -- MEWS / SOFA / CUSTOM_xxx
    name         VARCHAR(128) NOT NULL,
    drl_content  TEXT NOT NULL,                       -- 规则 DRL 文本，支持医院自定义
    version      INT DEFAULT 1,
    enabled      BOOLEAN DEFAULT TRUE,
    created_at   TIMESTAMPTZ DEFAULT now(),
    UNIQUE(hospital_id, code, version)
);

-- 评分结果历史
CREATE TABLE IF NOT EXISTS scoring_result (
    time         TIMESTAMPTZ NOT NULL,
    patient_id   BIGINT NOT NULL,
    bed_id       BIGINT,
    rule_code    VARCHAR(64) NOT NULL,
    score        DOUBLE PRECISION,
    level        VARCHAR(16),                          -- NORMAL/WARN/CRITICAL
    detail       JSONB
);
SELECT create_hypertable('scoring_result', 'time', if_not_exists => TRUE, chunk_time_interval => INTERVAL '7 days');
CREATE INDEX IF NOT EXISTS idx_scoring_result_patient ON scoring_result (patient_id, time DESC);

-- 9. 告警事件
CREATE TABLE IF NOT EXISTS alert_event (
    id            BIGSERIAL PRIMARY KEY,
    time          TIMESTAMPTZ NOT NULL,
    bed_id        BIGINT NOT NULL,
    patient_id    BIGINT,
    channel_code  VARCHAR(32),
    level         VARCHAR(16) NOT NULL,               -- INFO / WARN / CRITICAL
    alert_type    VARCHAR(64) NOT NULL,               -- HR_LOW / SPO2_LOW / TEMP_HIGH ...
    value         DOUBLE PRECISION,
    message       TEXT,
    status        VARCHAR(16) DEFAULT 'OPEN',         -- OPEN / ACK / CLOSED / SUPPRESSED
    dedup_count   INT DEFAULT 1,
    parent_id     BIGINT,                             -- 升级关联父告警
    created_at    TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX idx_alert_bed_time ON alert_event(bed_id, time DESC);
CREATE INDEX idx_alert_status ON alert_event(status);
CREATE INDEX idx_alert_level ON alert_event(level);

SELECT create_hypertable('alert_event', 'time', if_not_exists => TRUE, chunk_time_interval => INTERVAL '7 days');

-- 10. 告警升级策略（可配置）
CREATE TABLE IF NOT EXISTS alert_escalation_policy (
    id                  BIGSERIAL PRIMARY KEY,
    hospital_id         BIGINT NOT NULL REFERENCES hospital(id),
    name                VARCHAR(64) NOT NULL,
    dedup_window_sec    INT DEFAULT 60,                -- 去重窗口
    escalation_count    INT DEFAULT 3,                 -- 触发升级次数
    escalation_sec      INT DEFAULT 120,               -- 升级时长
    silence_enabled     BOOLEAN DEFAULT TRUE,          -- 是否启用智能静默（多指标交叉验证）
    cross_check_metric  VARCHAR(256),                  -- 交叉验证的指标列表（逗号分隔）
    created_at          TIMESTAMPTZ DEFAULT now()
);

-- 11. 护理记录
CREATE TABLE IF NOT EXISTS nursing_record (
    id           BIGSERIAL PRIMARY KEY,
    time         TIMESTAMPTZ NOT NULL,
    patient_id   BIGINT NOT NULL,
    bed_id       BIGINT,
    nurse_id     VARCHAR(64),
    category     VARCHAR(32),                         -- OBSERVATION / INTERVENTION / MEDICATION
    content      TEXT,
    created_at   TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX idx_nursing_patient_time ON nursing_record(patient_id, time DESC);

-- 12. 医嘱执行记录
CREATE TABLE IF NOT EXISTS order_execution (
    id            BIGSERIAL PRIMARY KEY,
    time          TIMESTAMPTZ NOT NULL,
    patient_id    BIGINT NOT NULL,
    bed_id        BIGINT,
    order_no      VARCHAR(64) NOT NULL,
    order_type    VARCHAR(32),                        -- MED / INSPECT / NURSE
    item_name     VARCHAR(128),
    status        VARCHAR(16),                        -- PENDING / DONE / CANCELLED
    executor      VARCHAR(64),
    created_at    TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX idx_order_patient_time ON order_execution(patient_id, time DESC);

-- 13. 抢救事件回放会话
CREATE TABLE IF NOT EXISTS playback_session (
    id              BIGSERIAL PRIMARY KEY,
    bed_id          BIGINT NOT NULL,
    patient_id      BIGINT,
    trigger_alert_id BIGINT,
    start_at        TIMESTAMPTZ NOT NULL,             -- 触发前 30 分钟
    end_at          TIMESTAMPTZ NOT NULL,             -- 触发后 30 分钟
    status          VARCHAR(16) DEFAULT 'ACTIVE',     -- ACTIVE / ARCHIVED
    summary         TEXT,
    created_at      TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX idx_playback_bed ON playback_session(bed_id, start_at DESC);

-- 回放数据条目（用于时间轴串联展示）
CREATE TABLE IF NOT EXISTS playback_item (
    id           BIGSERIAL PRIMARY KEY,
    session_id   BIGINT NOT NULL REFERENCES playback_session(id) ON DELETE CASCADE,
    time         TIMESTAMPTZ NOT NULL,
    source_type  VARCHAR(16) NOT NULL,                -- WAVEFORM / VITAL / ALERT / ORDER / NURSING
    ref_id       BIGINT,                              -- 引用 ID
    payload      JSONB,                               -- 展开内容
    UNIQUE(session_id, time, source_type, ref_id)
);
CREATE INDEX idx_playback_item_session ON playback_item(session_id, time);

-- ============================================================
-- 初始数据
-- ============================================================
INSERT INTO hospital (code, name) VALUES ('H001', '示范医院')
ON CONFLICT (code) DO NOTHING;

INSERT INTO ward (hospital_id, code, name)
SELECT id, 'ICU-1', '重症医学科一病区' FROM hospital WHERE code='H001'
ON CONFLICT (hospital_id, code) DO NOTHING;

INSERT INTO bed (ward_id, code, status)
SELECT w.id, 'B' || lpad(g::text, 2, '0'), 'OCCUPIED'
FROM ward w, generate_series(1, 50) g
WHERE w.code='ICU-1'
ON CONFLICT (ward_id, code) DO NOTHING;

-- 默认升级策略
INSERT INTO alert_escalation_policy (hospital_id, name, dedup_window_sec, escalation_count, escalation_sec, silence_enabled, cross_check_metric)
SELECT id, '默认策略-去重60s-3次升级-智能静默', 60, 3, 120, TRUE, 'HR,SPO2,RR,NIBP'
FROM hospital WHERE code='H001'
ON CONFLICT DO NOTHING;

-- 默认评分规则：MEWS（Modified Early Warning Score）
-- 5 项指标：收缩压、心率、呼吸、体温、AVPU
INSERT INTO scoring_rule (hospital_id, code, name, drl_content, version, enabled)
SELECT id, 'MEWS', '改良早期预警评分',
$$
import com.icu.monitor.scoring.ScoreContext
import com.icu.monitor.scoring.MEWSResult

rule "MEWS 计算"
    salience 100
    when
        $ctx: ScoreContext(ruleCode == "MEWS")
    then
        double score = 0;
        // 收缩压
        if ($ctx.getSbp() != null) {
            double sbp = $ctx.getSbp();
            if (sbp < 70) score += 3;
            else if (sbp < 80) score += 2;
            else if (sbp < 100) score += 1;
            else if (sbp <= 199) score += 0;
            else score += 2;
        }
        // 心率
        if ($ctx.getHr() != null) {
            double hr = $ctx.getHr();
            if (hr < 40) score += 2;
            else if (hr < 50) score += 1;
            else if (hr <= 100) score += 0;
            else if (hr <= 110) score += 1;
            else if (hr <= 130) score += 2;
            else score += 3;
        }
        // 呼吸
        if ($ctx.getRr() != null) {
            double rr = $ctx.getRr();
            if (rr < 9) score += 2;
            else if (rr <= 14) score += 0;
            else if (rr <= 20) score += 1;
            else if (rr <= 29) score += 2;
            else score += 3;
        }
        // 体温
        if ($ctx.getTemp() != null) {
            double t = $ctx.getTemp();
            if (t < 35) score += 2;
            else if (t <= 38.4) score += 0;
            else score += 2;
        }
        // AVPU
        if ("V".equals($ctx.getAvpu()) || "P".equals($ctx.getAvpu())) score += 2;
        else if ("U".equals($ctx.getAvpu())) score += 3;

        String level;
        if (score >= 5) level = "CRITICAL";
        else if (score >= 3) level = "WARN";
        else level = "NORMAL";

        insert(new MEWSResult(score, level));
end
$$, 1, TRUE
FROM hospital WHERE code='H001'
ON CONFLICT (hospital_id, code, version) DO NOTHING;

-- 默认评分规则：SOFA（6 个器官系统）
INSERT INTO scoring_rule (hospital_id, code, name, drl_content, version, enabled)
SELECT id, 'SOFA', '序贯器官衰竭评分',
$$
import com.icu.monitor.scoring.ScoreContext
import com.icu.monitor.scoring.SOFAResult

rule "SOFA 计算"
    salience 100
    when
        $ctx: ScoreContext(ruleCode == "SOFA")
    then
        double total = 0;
        // 呼吸系统：PaO2/FiO2
        if ($ctx.getPfRatio() != null) {
            double p = $ctx.getPfRatio();
            if (p >= 400) total += 0;
            else if (p >= 300) total += 1;
            else if (p >= 200) total += 2;
            else if (p >= 100) total += 3;
            else total += 4;
        }
        // 凝血：血小板
        if ($ctx.getPlt() != null) {
            double p = $ctx.getPlt();
            if (p >= 150) total += 0;
            else if (p >= 100) total += 1;
            else if (p >= 50)  total += 2;
            else if (p >= 20)  total += 3;
            else total += 4;
        }
        // 肝脏：胆红素
        if ($ctx.getBilirubin() != null) {
            double b = $ctx.getBilirubin();
            if (b < 1.2) total += 0;
            else if (b < 2.0) total += 1;
            else if (b < 6.0) total += 2;
            else if (b < 12.0) total += 3;
            else total += 4;
        }
        // 心血管：升压药剂量
        if ($ctx.getDopamine() != null && $ctx.getDopamine() > 5) total += 3;
        else if ($ctx.getDobutamine() != null && $ctx.getDobutamine() > 0) total += 2;
        else if ($ctx.getMap() != null && $ctx.getMap() < 70) total += 1;
        // 神经：GCS
        if ($ctx.getGcs() != null) {
            int g = $ctx.getGcs();
            if (g >= 15) total += 0;
            else if (g >= 13) total += 1;
            else if (g >= 10) total += 2;
            else if (g >= 6)  total += 3;
            else total += 4;
        }
        // 肾脏：肌酐
        if ($ctx.getCreatinine() != null) {
            double c = $ctx.getCreatinine();
            if (c < 1.2) total += 0;
            else if (c < 2.0) total += 1;
            else if (c < 3.5) total += 2;
            else if (c < 5.0) total += 3;
            else total += 4;
        }
        String level = total >= 8 ? "CRITICAL" : (total >= 4 ? "WARN" : "NORMAL");
        insert(new SOFAResult(total, level));
end
$$, 1, TRUE
FROM hospital WHERE code='H001'
ON CONFLICT (hospital_id, code, version) DO NOTHING;

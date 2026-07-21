-- ============================================================
-- ICU 联合质控（多院区）扩展 - 7 个新实体 + 初始化
-- ============================================================

-- 1. 医院联盟（多医院组成的质控网络）
CREATE TABLE IF NOT EXISTS hospital_alliance (
    id           BIGSERIAL PRIMARY KEY,
    code         VARCHAR(64) UNIQUE NOT NULL,
    name         VARCHAR(128) NOT NULL,
    description  TEXT,
    deid_enabled BOOLEAN DEFAULT TRUE,                -- 是否启用脱敏共享
    created_at   TIMESTAMPTZ DEFAULT now()
);

-- 联盟成员（多对多）
CREATE TABLE IF NOT EXISTS alliance_member (
    id           BIGSERIAL PRIMARY KEY,
    alliance_id  BIGINT NOT NULL REFERENCES hospital_alliance(id) ON DELETE CASCADE,
    hospital_id  BIGINT NOT NULL REFERENCES hospital(id),
    role         VARCHAR(16) DEFAULT 'MEMBER',         -- LEADER/MEMBER
    joined_at    TIMESTAMPTZ DEFAULT now(),
    UNIQUE(alliance_id, hospital_id)
);

-- 2. 院间共享池（脱敏后只含生命体征/医嘱/转归）
CREATE TABLE IF NOT EXISTS shared_case (
    id                BIGSERIAL PRIMARY KEY,
    alliance_id       BIGINT NOT NULL REFERENCES hospital_alliance(id),
    source_hospital   BIGINT NOT NULL REFERENCES hospital(id),
    pool_patient_key  VARCHAR(64) NOT NULL,            -- 跨院区脱敏标识（hash 化）
    drg_code          VARCHAR(16) NOT NULL,            -- DRG 编码，如 "E11A"
    mdc_code          VARCHAR(8),                       -- MDC 主诊断大类
    age_band          VARCHAR(16),                      -- 18-29/30-44/45-59/60-74/75+
    gender            VARCHAR(8),
    sofa_admission    DOUBLE PRECISION,                 -- 入院 24h SOFA
    sofa_daily_curve  JSONB,                            -- 真实每日 SOFA，数组下标=Day 0..7（与 sofa_admission 一致为入院当天）
    apache_admission  DOUBLE PRECISION,                 -- 入院 24h APACHE II（可选）
    diagnosis_text    TEXT,
    vitals_summary    JSONB,                            -- 入院 24h 生命体征统计（min/avg/max）
    lab_summary       JSONB,                            -- 关键化验摘要
    treatment_path    JSONB,                            -- 治疗路径（医嘱时序）
    rescue_events     JSONB,                            -- 抢救事件列表
    outcome           VARCHAR(16),                      -- SURVIVED/TRANSFERRED/DECEASED
    los_days          INT,                              -- 住院天数
    infection_flag    BOOLEAN DEFAULT FALSE,            -- 是否发生院内感染
    admission_at      TIMESTAMPTZ NOT NULL,
    shared_at         TIMESTAMPTZ DEFAULT now(),
    UNIQUE(alliance_id, source_hospital, pool_patient_key)
);
CREATE INDEX idx_shared_drg ON shared_case(alliance_id, drg_code);
CREATE INDEX idx_shared_age ON shared_case(alliance_id, age_band);
CREATE INDEX idx_shared_sofa ON shared_case(alliance_id, sofa_admission);
CREATE INDEX idx_shared_outcome ON shared_case(alliance_id, outcome);

-- 兼容已部署库：缺列时补齐（联合质控 SOFA 真实曲线）
ALTER TABLE shared_case ADD COLUMN IF NOT EXISTS sofa_daily_curve JSONB;

-- 3. 相似病例索引（基于生命体征时序+化验向量化）
-- 向量以 JSON 数组存储 16 维特征：
-- [hr_avg, hr_std, sbp_avg, sbp_std, spo2_avg, spo2_std, temp_avg, resp_avg,
--  cr_avg, plt_avg, bili_avg, dopa, lactate, wbc, pf_ratio, sofa]
-- 在 service 层做余弦相似度。
CREATE TABLE IF NOT EXISTS similar_index (
    id              BIGSERIAL PRIMARY KEY,
    shared_case_id  BIGINT NOT NULL REFERENCES shared_case(id) ON DELETE CASCADE,
    feature_vector  JSONB NOT NULL,                    -- 16 维向量
    norm            DOUBLE PRECISION,                  -- L2 范数（预计算，加速）
    built_at        TIMESTAMPTZ DEFAULT now(),
    UNIQUE(shared_case_id)
);
CREATE INDEX idx_similar_drg ON similar_index USING GIN ((feature_vector));

-- 4. 指南库（按 DRG 索引到指南条目）
CREATE TABLE IF NOT EXISTS guideline (
    id           BIGSERIAL PRIMARY KEY,
    drg_code     VARCHAR(16),                          -- 关联 DRG（NULL 表示通用）
    mdc_code     VARCHAR(8),                           -- 关联 MDC
    title        VARCHAR(256) NOT NULL,
    evidence_level VARCHAR(8) NOT NULL,                -- A/B/C（GRADE）
    source       VARCHAR(128),                         -- 期刊/学会，如 "ESICM 2021"
    url          TEXT,                                 -- 证据链接
    summary      TEXT,                                 -- 推荐摘要
    key_actions  JSONB,                                -- 关键动作列表
    created_at   TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX idx_guideline_drg ON guideline(drg_code);
CREATE INDEX idx_guideline_level ON guideline(evidence_level);

-- 5. 治疗方案模板（按相似度评分排序）
CREATE TABLE IF NOT EXISTS plan_template (
    id             BIGSERIAL PRIMARY KEY,
    alliance_id    BIGINT NOT NULL REFERENCES hospital_alliance(id),
    drg_code       VARCHAR(16),
    title          VARCHAR(256) NOT NULL,
    steps          JSONB NOT NULL,                     -- 时间轴步骤
    evidence_level VARCHAR(8) NOT NULL,                -- A=高（指南）/B=中（RCT）/C=低（兄弟院区相似病例）
    based_on       VARCHAR(64),                        -- GUIDELINE/RCT/SIMILAR_CASE
    source_url     TEXT,
    support_count  INT DEFAULT 0,                      -- 支撑病例数（SIMILAR_CASE 类型）
    success_rate   DOUBLE PRECISION,                   -- 支撑病例的成功率
    created_at     TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX idx_plan_drg ON plan_template(alliance_id, drg_code);

-- 6. 跨院区质控指标（按 DRG 分组聚合）
CREATE TABLE IF NOT EXISTS qc_metric (
    id                BIGSERIAL PRIMARY KEY,
    alliance_id       BIGINT NOT NULL REFERENCES hospital_alliance(id),
    hospital_id       BIGINT NOT NULL REFERENCES hospital(id),
    drg_code          VARCHAR(16) NOT NULL,
    period_quarter    VARCHAR(8) NOT NULL,              -- 2026Q3
    case_count        INT DEFAULT 0,
    death_count       INT DEFAULT 0,
    mortality_rate    DOUBLE PRECISION,                 -- 死亡率
    avg_los_days      DOUBLE PRECISION,                 -- 平均住院日
    infection_count   INT DEFAULT 0,
    infection_rate    DOUBLE PRECISION,                 -- 感染率
    avg_sofa_curve    JSONB,                            -- SOFA 评分变化曲线（Day0~Day7）
    updated_at        TIMESTAMPTZ DEFAULT now(),
    UNIQUE(alliance_id, hospital_id, drg_code, period_quarter)
);
CREATE INDEX idx_qc_metric_lookup ON qc_metric(alliance_id, drg_code, period_quarter);

-- 7. 联合质控报告
CREATE TABLE IF NOT EXISTS joint_report (
    id             BIGSERIAL PRIMARY KEY,
    alliance_id    BIGINT NOT NULL REFERENCES hospital_alliance(id),
    period_quarter VARCHAR(8) NOT NULL,
    title          VARCHAR(256) NOT NULL,
    summary        TEXT,
    highlights     JSONB,                              -- 重点发现
    drg_breakdown  JSONB,                              -- 各 DRG 跨院区对比
    action_items   JSONB,                              -- 改进建议
    generated_at   TIMESTAMPTZ DEFAULT now(),
    UNIQUE(alliance_id, period_quarter)
);

-- 8. 事后回放（"如果当时采用推荐方案"）
CREATE TABLE IF NOT EXISTS whatif_session (
    id                BIGSERIAL PRIMARY KEY,
    source_patient_id BIGINT,                          -- 原始患者（用于回放真实数据）
    shared_case_id    BIGINT REFERENCES shared_case(id),
    plan_template_id  BIGINT NOT NULL REFERENCES plan_template(id),
    alliance_id       BIGINT NOT NULL REFERENCES hospital_alliance(id),
    actual_outcome    VARCHAR(16),                     -- 实际转归
    predicted_outcome VARCHAR(16),                     -- 推演转归
    mortality_delta   DOUBLE PRECISION,                -- 死亡概率差
    timeline_delta    JSONB,                           -- 时间轴变化
    evidence_chain    JSONB,                           -- 证据链
    created_at        TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX idx_whatif_alliance ON whatif_session(alliance_id);

-- ============================================================
-- 初始化数据
-- ============================================================

-- 创建示例联盟：包含 3 个院区
INSERT INTO hospital (code, name) VALUES ('H002', '示范医院-分院A'), ('H003', '示范医院-分院B')
ON CONFLICT (code) DO NOTHING;

INSERT INTO hospital_alliance (code, name, description, deid_enabled)
VALUES ('ALLIANCE-DEMO', '省级ICU质控联盟（演示）', '3 个院区联合质控网络，演示用', TRUE)
ON CONFLICT (code) DO NOTHING;

-- 加入成员（包含原 H001）
INSERT INTO alliance_member (alliance_id, hospital_id, role)
SELECT a.id, h.id, CASE WHEN h.code = 'H001' THEN 'LEADER' ELSE 'MEMBER' END
FROM hospital_alliance a, hospital h
WHERE a.code = 'ALLIANCE-DEMO' AND h.code IN ('H001', 'H002', 'H003')
ON CONFLICT (alliance_id, hospital_id) DO NOTHING;

-- 为每个院区建 ICU-1 病区
INSERT INTO ward (hospital_id, code, name)
SELECT h.id, 'ICU-1', h.name || ' 重症医学科'
FROM hospital h
WHERE h.code IN ('H001', 'H002', 'H003')
  AND NOT EXISTS (SELECT 1 FROM ward w WHERE w.hospital_id = h.id AND w.code = 'ICU-1');

-- 为新院区各建 50 张床
INSERT INTO bed (ward_id, code, status)
SELECT w.id, 'B' || lpad(g::text, 2, '0'), 'IDLE'
FROM ward w, generate_series(1, 50) g
WHERE w.code = 'ICU-1'
  AND NOT EXISTS (SELECT 1 FROM bed b WHERE b.ward_id = w.id AND b.code = 'B' || lpad(g::text, 2, '0'));

-- 初始指南数据（10 条覆盖常见 DRG）
INSERT INTO guideline (drg_code, mdc_code, title, evidence_level, source, url, summary, key_actions)
VALUES
('E11A', 'MDC04', '急性呼吸窘迫综合征俯卧位通气推荐', 'A', 'ESICM 2021', 'https://www.esicm.org/ards-2021',
 '重度 ARDS 患者应每日俯卧位通气 ≥16h', '["prone_ventilation_16h", "low_tidal_volume_6mlkg"]'::jsonb),
('F15A', 'MDC05', 'ST 段抬高型心肌梗死直接 PCI 时间窗', 'A', 'ESC 2023', 'https://www.escardio.org/stemi-2023',
 'Door-to-Balloon ≤90 分钟', '["primary_pci_90min", "dual_antiplatelet"]'::jsonb),
('A11A', 'MDC01', '脑卒中静脉溶栓时间窗', 'A', 'AHA/ASA 2021', 'https://www.ahajournals.org/stroke-2021',
 '发病 4.5h 内符合条件者推荐阿替普酶', '["iv_tPA_4.5h", "NIHSS_baseline"]'::jsonb),
('G11A', 'MDC06', '脓毒症 1 小时 bundle', 'A', 'SSC 2021', 'https://www.sccm.org/sepsis-2021',
 '1h 内完成：乳酸、培养、广谱抗生素、液体复苏、血管活性药', '["lactate", "blood_culture", "abx_1h", "crystalloid_30mlkg"]'::jsonb),
('H11A', 'MDC07', '急性胰腺炎液体复苏策略', 'B', 'WSES 2020', 'https://www.wses.org/pancreatitis-2020',
 'Ringer 乳酸积极液体复苏，警惕腹腔间隔综合征', '["ringer_lactate", "agi_monitoring"]'::jsonb),
('B11A', 'MDC01', '颅脑外伤 ICP 监测阈值', 'B', 'BTF 2016', 'https://www.braintrauma.org/btf-2016',
 'ICP > 22 mmHg 需干预', '["icp_monitoring", "cpp_target_60"]'::jsonb),
('D11A', 'MDC04', 'COPD 急性加重无创通气指征', 'A', 'GOLD 2023', 'https://goldcopd.org/2023',
 'pH 7.25-7.35、PaCO2>45 首选 NIV', '["niv_biPAP", "abx_if_infectious"]'::jsonb),
('I11A', 'MDC08', '多发伤大输血方案', 'A', 'EAST 2020', 'https://www.east.org/mtp-2020',
 '1:1:1（血浆:血小板:RBC）MTP 激活', '["mtp_activate", "txa_3h"]'::jsonb),
('J11A', 'MDC09', '急性肾损伤 CRRT 启动时机', 'B', 'KDIGO 2021', 'https://kdigo.org/aki-2021',
 '危及生命指征：AEIOU', '["crrt_aeiou", "dose_25mlkgh"]'::jsonb),
('K11A', 'MDC10', '院内感染集束化预防', 'A', 'CDC 2022', 'https://www.cdc.gov/bundle-2022',
 'CLABSI/CAUTI/VAP 集束化', '["chlorhexidine_bath", "head_elevated_30"]'::jsonb)
ON CONFLICT DO NOTHING;

-- 初始方案模板（基于指南+本院案例）
INSERT INTO plan_template (alliance_id, drg_code, title, steps, evidence_level, based_on, source_url, support_count, success_rate)
SELECT a.id, g.drg_code, '标准化方案: ' || g.title,
       g.key_actions,
       g.evidence_level,
       'GUIDELINE',
       g.url,
       0, 0.85
FROM hospital_alliance a, guideline g
WHERE a.code = 'ALLIANCE-DEMO'
  AND g.drg_code IS NOT NULL
ON CONFLICT DO NOTHING;

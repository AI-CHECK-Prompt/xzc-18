#!/usr/bin/env python3
"""
多院区联盟模拟器
- 3 个院区各 50 床位接入
- 模拟患者入院、DRG 分布、生命体征、治疗路径、转归
- 把本院患者脱敏后写入联盟共享池
- 跨院区做 DRG 聚合（演示 H001 院区比兄弟院区死亡率 +3.5% 的场景）

用法：
    python3 multi_hospital_sim.py --alliance-id 1 --hospital-id 1 --patients 200
"""
import requests, random, time, json, argparse
from datetime import datetime, timedelta
from concurrent.futures import ThreadPoolExecutor

API = "http://localhost:8080"

# DRG 分布（CHISS-DRG 主要 ICU 入组）
DRG_POOL = [
    # (DRG, MDC, 描述, base_mortality)
    ("E11A", "MDC04", "ARDS 机械通气>96h",        0.32),
    ("F15A", "MDC05", "急性心梗+介入",            0.18),
    ("A11A", "MDC01", "重度脑卒中",               0.28),
    ("G11A", "MDC06", "脓毒症休克",               0.35),
    ("H11A", "MDC07", "急性重症胰腺炎",           0.22),
    ("B11A", "MDC01", "重度颅脑外伤",             0.30),
    ("D11A", "MDC04", "COPD 急性加重需 NIV",      0.15),
    ("I11A", "MDC08", "多发伤大输血",             0.40),
    ("J11A", "MDC09", "急性肾损伤 CRRT",          0.25),
    ("K11A", "MDC10", "院内感染集束化",           0.20),
]

# 标准治疗路径（每 DRG 对应一组关键动作）
TREATMENT_PATH = {
    "E11A": ["prone_ventilation", "low_tidal_volume_6mlkg", " neuromuscular_block_24h", "tracheotomy_d7"],
    "F15A": ["primary_pci_90min", "dual_antiplatelet", "statins", "acei"],
    "A11A": ["iv_tPA_4.5h", "NIHSS_q1h", "decompressive_craniectomy"],
    "G11A": ["lactate_q2h", "blood_culture", "abx_1h", "crystalloid_30mlkg", "norepinephrine"],
    "H11A": ["ringer_lactate", "agi_monitoring", "enteral_nutrition_d2"],
    "B11A": ["icp_monitoring", "cpp_target_60", "decompressive_craniectomy"],
    "D11A": ["niv_biPAP", "abx_if_infectious", "systemic_steroid_5d"],
    "I11A": ["mtp_activate", "txa_3h", "damage_control_surgery"],
    "J11A": ["crrt_aeiou", "dose_25mlkgh", "citrate_anticoagulation"],
    "K11A": ["chlorhexidine_bath", "head_elevated_30", "sedation_holiday"],
}

# 抢救事件
RESCUE_EVENTS = {
    "E11A": ["cardiac_arrest_T72h", "pneumothorax_drainage"],
    "G11A": ["dialysis_hypotension", "arrhythmia_amiodarone"],
}

# 院区差异：本院（H001）死亡率人为抬升 3.5%，模拟"质控问题"
HOSPITAL_MORTALITY_BIAS = {
    1: 0.035,   # 院区 1 偏高 +3.5%
    2: 0.0,
    3: -0.015,  # 院区 3 略低 -1.5%
}

def random_patient(pid, hospital_id):
    drg, mdc, desc, base_mort = random.choice(DRG_POOL)
    # 应用院区偏置
    mortality = max(0, min(1, base_mort + HOSPITAL_MORTALITY_BIAS.get(hospital_id, 0)))

    # 16 维特征（与 Java 端一致）
    features = {
        "hrAvg":      random.uniform(70, 120),
        "hrStd":      random.uniform(5, 25),
        "sbpAvg":     random.uniform(95, 150),
        "sbpStd":     random.uniform(8, 25),
        "spo2Avg":    random.uniform(88, 99),
        "spo2Std":    random.uniform(1, 6),
        "tempAvg":    random.uniform(36.0, 38.8),
        "respAvg":    random.uniform(14, 30),
        "creatinine": random.uniform(0.6, 3.5),
        "platelet":   random.uniform(50, 350),
        "bilirubin":  random.uniform(0.4, 4.0),
        "dopamine":   random.choice([0, 0, 0, 5, 10]),
        "lactate":    random.uniform(0.8, 6.0),
        "wbc":        random.uniform(4, 22),
        "pfRatio":    random.uniform(80, 450),
        "sofa":       random.uniform(2, 14)
    }
    los = max(1, int(random.gauss(8, 3)))
    infection = random.random() < 0.20
    outcome = "DECEASED" if random.random() < mortality else \
              ("TRANSFERRED" if random.random() < 0.15 else "SURVIVED")

    # 治疗路径
    steps = [{"time": f"T+{i*6}h", "itemName": s, "status": "DONE"}
             for i, s in enumerate(TREATMENT_PATH.get(drg, []))]
    rescue = [{"time": f"T+{random.randint(24, 96)}h", "type": e}
              for e in RESCUE_EVENTS.get(drg, [])]

    return {
        "patientId": pid,
        "hospitalId": hospital_id,
        "drgCode": drg,
        "mdcCode": mdc,
        "sofaAdmission": features["sofa"],
        "features": features,
        "outcome": outcome,
        "losDays": los,
        "infection": infection,
        "treatment": steps,
        "rescue": rescue,
        "admissionAt": (datetime.now() - timedelta(days=random.randint(0, 60))).isoformat()
    }

def ensure_patient(hospital_id, pid):
    """通过 /api/his/patient 创建/同步患者档案"""
    try:
        requests.post(f"{API}/api/his/patient", params={
            "hospitalId": hospital_id,
            "mrn": f"MRN-H{hospital_id:03d}-{pid:06d}",
            "name": f"患者{pid}",
            "gender": random.choice(["M", "F"]),
            "birthDate": "1965-05-12",
            "diagnosis": "ICU 监护"
        }, timeout=3)
    except Exception:
        pass

def share_to_pool(alliance_id, hospital_id, case):
    """把患者脱敏后写入联盟共享池"""
    try:
        r = requests.post(f"{API}/api/alliance/share", params={
            "allianceId": alliance_id,
            "hospitalId": hospital_id,
            "patientId": case["patientId"],
            "drgCode": case["drgCode"],
            "mdcCode": case["mdcCode"],
            "sofaAdmission": case["sofaAdmission"]
        }, json={
            "vitals": {k: v for k, v in case["features"].items()
                       if k in ("hrAvg","hrStd","sbpAvg","sbpStd","spo2Avg","spo2Std","tempAvg","respAvg")},
            "lab": {k: v for k, v in case["features"].items()
                    if k in ("creatinine","platelet","bilirubin","dopamine","lactate","wbc","pfRatio")},
            "path": case["treatment"],
            "rescue": case["rescue"],
            "outcome": case["outcome"],
            "losDays": case["losDays"],
            "infection": case["infection"]
        }, timeout=5)
        return r.json() if r.ok else {"ok": False, "err": r.text}
    except Exception as e:
        return {"ok": False, "err": str(e)}

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--alliance-id", type=int, default=1)
    ap.add_argument("--hospital-id", type=int, required=True)
    ap.add_argument("--patients", type=int, default=200)
    ap.add_argument("--start-pid", type=int, default=10000)
    ap.add_argument("--concurrent", type=int, default=4)
    args = ap.parse_args()

    print(f"[sim] 院区 {args.hospital_id} 开始模拟 {args.patients} 例患者")
    patients = [random_patient(args.start_pid + i, args.hospital_id) for i in range(args.patients)]

    # 1) 先建档
    for p in patients:
        ensure_patient(args.hospital_id, p["patientId"])

    # 2) 共享到联盟
    ok_count = 0
    for i, p in enumerate(patients):
        r = share_to_pool(args.alliance_id, args.hospital_id, p)
        if r.get("ok"):
            ok_count += 1
        if (i + 1) % 20 == 0:
            print(f"[sim] 院区 {args.hospital_id} 已脱敏共享 {i+1}/{args.patients}")
    print(f"[sim] 院区 {args.hospital_id} 完成：{ok_count}/{args.patients} 成功写入共享池")

if __name__ == "__main__":
    main()

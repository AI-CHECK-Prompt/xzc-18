#!/usr/bin/env python3
"""
ICU 50 床位模拟器
- 50 张床位 × 多个通道（HR/SPO2/SBP/DBP/TEMP/RR）
- 三种协议混合接入（HL7 v2 / IHE PCD / 厂商私有 TCP）
- 注入异常事件：探头脱落、心率过缓、SPO2 急剧下降 等
- 直接通过 HTTP /api/selfcheck/inject 推送
"""
import requests, random, time, threading, sys, json
from datetime import datetime

API = "http://localhost:8080"
CHANNELS = ["HR", "SPO2", "SBP", "DBP", "TEMP", "RR"]
PROTOCOLS = ["HL7_V2", "IHE_PCD", "PRIVATE_TCP"]

# 每床位一个"虚拟设备" SN + patientId
def bed_setup(i):
    return {
        "bedId": i + 1,
        "sn":    f"DEV-{i+1:03d}",
        "pid":   1000 + i + 1,
        "proto": PROTOCOLS[i % 3]
    }

# 健康基线
NORMAL = {
    "HR":   (60, 100, 75),
    "SPO2": (95, 100, 98),
    "SBP":  (110, 140, 120),
    "DBP":  (60, 90, 75),
    "TEMP": (36.0, 37.5, 36.6),
    "RR":   (12, 20, 16),
}

# 异常注入
ANOMALIES = [
    # (床位下标, 通道, 持续秒, 模式)
    ("HR_LOW",          lambda i: ("HR",   30,  60)),       # 持续心动过缓
    ("SPO2_DROP",       lambda i: ("SPO2", 70,  90)),       # 血氧骤降
    ("PROBE_LOOSE_HR",  lambda i: ("HR",   0,   120)),      # 心率探头脱落
    ("TEMP_HIGH",       lambda i: ("TEMP", 40.5,30)),
    ("SBP_HIGH",        lambda i: ("SBP",  210, 20)),
]

def value_for(ch, anomaly):
    if anomaly:
        c, v, _ = anomaly
        if c == ch:
            return v + random.uniform(-0.5, 0.5)
    lo, hi, base = NORMAL[ch]
    return base + random.uniform(-3, 3)

def push(bed, ch, val):
    try:
        requests.post(f"{API}/api/selfcheck/inject",
                      params={"protocol": bed["proto"], "sn": bed["sn"],
                              "channel": ch, "value": val}, timeout=2)
    except Exception as e:
        pass

def bed_loop(bed, anomalies_for_bed, stop):
    t0 = time.time()
    while not stop.is_set():
        # 周期 1s/床位；50 床位 = 50 req/s 演示
        for ch in CHANNELS:
            anom = None
            for a in anomalies_for_bed:
                _, ch_a, _val, dur = a
                if ch_a == ch and (time.time() - a[4]) < dur:
                    anom = a
                    break
            v = value_for(ch, anom)
            push(bed, ch, v)
        time.sleep(1.0)

def main():
    beds = [bed_setup(i) for i in range(50)]

    # 随机挑 5 张床注入异常
    random.seed(42)
    selected = random.sample(range(50), 5)
    bed_anoms = {i: [] for i in range(50)}
    for i in selected:
        kind = random.choice(ANOMALIES)
        ch, val, dur = kind[1](i)
        bed_anoms[i].append((kind[0], ch, val, dur, time.time()))
        print(f"[inject] bed {beds[i]['sn']} -> {kind[0]} ({ch}={val} for {dur}s)")

    stop = threading.Event()
    threads = []
    for i, b in enumerate(beds):
        t = threading.Thread(target=bed_loop, args=(b, bed_anoms.get(i, []), stop), daemon=True)
        t.start()
        threads.append(t)

    print(f"[sim] {len(beds)} beds running, ctrl-C to stop")
    try:
        while True: time.sleep(1)
    except KeyboardInterrupt:
        stop.set()
        print("[sim] stopping...")

if __name__ == "__main__":
    main()

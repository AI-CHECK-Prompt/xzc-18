package com.icu.monitor.alliance;

import java.util.*;

/**
 * "高死亡率 DRG" 选择的纯逻辑回归校验。
 * <p>
 * 不依赖 Spring / DB / 共享池，只验证 selectHighMortalityDrg 的选 DRG 规则：
 *  1) 按 alliance_mortality DESC 选 max（不再是 breakdown.get(0)）
 *  2) 要求 total_cases >= minSample，过滤小样本极端值（1/2=50% 等）
 *  3) 死亡率相同时，优先选 total_cases 较大的（更可靠的信号）
 *  4) 用户场景：高病例数但低死亡率的"肺部感染 DRG"不再掩盖真正高死亡率的"急性胰腺炎重症"
 *  5) breakdown 为空 / null 时返回 null（不抛 NPE）
 *  6) 全部 DRG 都不满足 minSample 时返回 null（由调用方决定兜底）
 *  7) MIN_SAMPLE_FOR_HIGH_MORTALITY 常量值校验（防止误改）
 */
public class HighMortalityDrgLogicTest {

    private static int pass = 0;
    private static int fail = 0;

    private static void check(String name, boolean cond) {
        if (cond) { System.out.println("  PASS  " + name); pass++; }
        else      { System.out.println("  FAIL  " + name); fail++; }
    }

    private static Map<String, Object> drg(String code, int cases, int deaths) {
        Map<String, Object> m = new HashMap<>();
        m.put("drg_code", code);
        m.put("total_cases", cases);
        m.put("total_deaths", deaths);
        m.put("alliance_mortality", cases > 0 ? Math.round(deaths * 10000.0 / cases) / 10000.0 : 0);
        return m;
    }

    public static void main(String[] args) {
        // ---------- 0) 常量校验 ----------
        check("MIN_SAMPLE_FOR_HIGH_MORTALITY == 10", JointQcService.MIN_SAMPLE_FOR_HIGH_MORTALITY == 10);

        // ---------- 1) 用户场景：高病例数低死亡率 DRG 不再掩盖低病例数高死亡率 DRG ----------
        // 肺部感染：200 例，4 死 → 死亡率 2%（病例最多）
        // 急性胰腺炎重症：12 例，2 死 → 死亡率 16.7%（真正高死亡率）
        // 期望 highMortDrg = 急性胰腺炎重症（H11A），而不是 肺部感染
        List<Map<String, Object>> breakdown = new ArrayList<>();
        breakdown.add(drg("FEI_GAN",   200, 4));    // 2% 病例最多
        breakdown.add(drg("YI_XIAN",    80, 5));    // 6.25%
        breakdown.add(drg("HUAN_YI",    30, 4));    // 13.3%
        breakdown.add(drg("H11A",       12, 2));    // 16.7% ← 真正最高
        breakdown.add(drg("XIN_JIAO",   25, 1));    // 4%
        Map<String, Object> picked = JointQcService.selectHighMortalityDrg(breakdown, 10);
        check("[场景-1] 高病例数低死亡率 DRG 不掩盖低病例数高死亡率 DRG",
              picked != null && "H11A".equals(picked.get("drg_code")));
        check("[场景-1] 选中的不是病例最多的",
              picked != null && !"FEI_GAN".equals(picked.get("drg_code")));

        // ---------- 2) 旧 bug 反向验证：若仍用 breakdown.get(0)（按 cases DESC），会选 FEI_GAN ----------
        // 这里用相同 breakdown 模拟"DAO 按 total_cases DESC 排序，首项 = 肺部感染"
        // 验证：在不修复的场景下，breakdown.get(0) 选到的是"病例最多"而不是"死亡率最高"
        Map<String, Object> buggyPicked = breakdown.get(0);
        check("[场景-1] 反向验证：旧 bug 行为（breakdown.get(0)）确实选到 FEI_GAN",
              "FEI_GAN".equals(buggyPicked.get("drg_code"))
                && Math.abs(((Number) buggyPicked.get("alliance_mortality")).doubleValue() - 0.02) < 1e-6);
        // 证明：旧行为会把 2% 死亡率的"肺部感染"当成"高死亡率 DRG"，掩盖 16.7% 的 H11A
        check("[场景-1] 反向验证：旧 bug 选中的 DRG 死亡率显著低于真正最高的",
              ((Number) buggyPicked.get("alliance_mortality")).doubleValue() < 0.10
                && ((Number) picked.get("alliance_mortality")).doubleValue() > 0.10);

        // ---------- 3) 小样本极端值过滤：1/2 = 50% 死亡率不能成为"高死亡率 DRG" ----------
        List<Map<String, Object>> bd2 = new ArrayList<>();
        bd2.add(drg("BIG",  200, 4));   // 2%  病例最多
        bd2.add(drg("SMALL", 2, 1));    // 50% 但 total_cases=2 < minSample=10，必须被过滤
        bd2.add(drg("MID",  20, 2));    // 10% 真正最高（满足 minSample）
        Map<String, Object> picked2 = JointQcService.selectHighMortalityDrg(bd2, 10);
        check("[场景-2] 1/2=50% 小样本被过滤，未成为 highMortDrg",
              picked2 != null && !"SMALL".equals(picked2.get("drg_code")));
        check("[场景-2] 真正高死亡率 DRG = MID",
              picked2 != null && "MID".equals(picked2.get("drg_code")));

        // ---------- 4) 边界：minSample=0 等价于不设阈值（兼容旧 DAO 演示） ----------
        Map<String, Object> picked3 = JointQcService.selectHighMortalityDrg(bd2, 0);
        check("[场景-3] minSample=0 时小样本可入选（SMALL 50%）",
              picked3 != null && "SMALL".equals(picked3.get("drg_code")));

        // ---------- 5) 死亡率相同时，选 total_cases 较大的（更可靠的信号） ----------
        List<Map<String, Object>> bd4 = new ArrayList<>();
        bd4.add(drg("A", 100, 10));  // 10% 病例多
        bd4.add(drg("B",  20, 2));   // 10% 病例少
        Map<String, Object> picked4 = JointQcService.selectHighMortalityDrg(bd4, 10);
        check("[场景-4] 死亡率相同时选病例更多的（更可靠信号）",
              picked4 != null && "A".equals(picked4.get("drg_code")));

        // ---------- 6) breakdown 为空 / null ----------
        check("[场景-5] breakdown = null 返回 null（不抛 NPE）",
              JointQcService.selectHighMortalityDrg(null, 10) == null);
        check("[场景-5] breakdown = [] 返回 null（不抛 NPE）",
              JointQcService.selectHighMortalityDrg(new ArrayList<>(), 10) == null);

        // ---------- 7) 全部 DRG 都不满足 minSample → 返回 null（调用方决定兜底） ----------
        List<Map<String, Object>> bd5 = new ArrayList<>();
        bd5.add(drg("A", 3, 1));
        bd5.add(drg("B", 5, 2));
        check("[场景-6] 全部 DRG 不满足 minSample 时返回 null",
              JointQcService.selectHighMortalityDrg(bd5, 10) == null);

        // ---------- 8) 演示数据：完整 10 个 DRG 模拟 ----------
        // 10 个 DRG，每个 60 例，base_mortality 0.15-0.40，模拟器
        // H001 偏置 +3.5%，H003 偏置 -1.5%
        // 预期：3 院区 × 60 例 = 180 例/DRG，mortality ≈ base_mort + (3.5+0-1.5)/3 ≈ base_mort + 0.67%
        // 关键断言：高 mortality 的 DRG（I11A=0.40, G11A=0.35, E11A=0.32）能正确被识别
        List<Map<String, Object>> bd6 = new ArrayList<>();
        bd6.add(drg("E11A", 180, 58));   // base 0.32 + 0.0067 ≈ 32.2%
        bd6.add(drg("F15A", 180, 33));   // base 0.18 + 0.0067 ≈ 18.3%
        bd6.add(drg("A11A", 180, 52));   // base 0.28 + 0.0067 ≈ 28.9%
        bd6.add(drg("G11A", 180, 64));   // base 0.35 + 0.0067 ≈ 35.6%
        bd6.add(drg("H11A", 180, 40));   // base 0.22 + 0.0067 ≈ 22.2%
        bd6.add(drg("B11A", 180, 55));   // base 0.30 + 0.0067 ≈ 30.6%
        bd6.add(drg("D11A", 180, 28));   // base 0.15 + 0.0067 ≈ 15.6%
        bd6.add(drg("I11A", 180, 73));   // base 0.40 + 0.0067 ≈ 40.6%  ← 真正最高
        bd6.add(drg("J11A", 180, 46));   // base 0.25 + 0.0067 ≈ 25.6%
        bd6.add(drg("K11A", 180, 37));   // base 0.20 + 0.0067 ≈ 20.6%
        Map<String, Object> picked6 = JointQcService.selectHighMortalityDrg(bd6, 10);
        check("[场景-7] 10 DRG 演示数据中 highMortDrg = I11A (40%)",
              picked6 != null && "I11A".equals(picked6.get("drg_code")));

        // ---------- 9) 单元覆盖：assert 关键字段（高死亡率 DRG 必须满足 minSample） ----------
        // 用户期望："重点关注高死亡率 DRG 必有足够样本量"，且 mortality 必须是真正最高的
        if (picked != null) {
            int cases = ((Number) picked.get("total_cases")).intValue();
            double mort = ((Number) picked.get("alliance_mortality")).doubleValue();
            check("[综合] 选中的 highMortDrg.total_cases >= minSample (10)", cases >= 10);
            // 选中的死亡率必须是 breakdown 中 max
            double maxMort = breakdown.stream()
                .mapToDouble(d -> ((Number) d.get("alliance_mortality")).doubleValue())
                .max().orElse(0);
            check("[综合] 选中的 highMortDrg.mortality == breakdown 中 max", Math.abs(mort - maxMort) < 1e-6);
        }

        System.out.println("--------------------------------------------------");
        System.out.println("HighMortalityDrgLogicTest  pass=" + pass + "  fail=" + fail);
        if (fail > 0) System.exit(1);
    }
}

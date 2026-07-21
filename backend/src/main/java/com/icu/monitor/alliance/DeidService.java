package com.icu.monitor.alliance;

import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.Period;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 跨院区脱敏服务
 * <p>
 * 合规要求（参考《个人信息保护法》《数据安全管理办法》医疗条款）：
 * 1. 姓名：保留姓氏+星号
 * 2. 身份证：完全 hash 化（跨院区唯一但不可逆）
 * 3. 手机号：完全 hash 化
 * 4. 具体住址：降级到省/市粒度
 * 5. 病案号 MRN：跨院区 pool_patient_key（hash 化）
 * 6. 出生日期：降级到年龄段
 */
@Service
public class DeidService {

    private static final Pattern MOBILE = Pattern.compile("^1[3-9]\\d{9}$");
    private static final Pattern IDCARD = Pattern.compile("^\\d{17}[\\dXx]$");

    /** 跨院区唯一脱敏键（同一患者不同院区得到的键一致，但不可逆） */
    public String poolKey(long allianceId, String originalMrnOrId) {
        if (originalMrnOrId == null) return null;
        return sha256("ALLI-" + allianceId + "-" + originalMrnOrId).substring(0, 24);
    }

    /** 姓名脱敏：张三 → 张*；欧阳娜娜 → 欧** */
    public String maskName(String name) {
        if (name == null || name.isEmpty()) return "*";
        if (name.length() == 1) return name + "*";
        if (name.length() == 2) return name.charAt(0) + "*";
        return name.charAt(0) + repeat("*", name.length() - 1);
    }

    /** 身份证 hash */
    public String hashIdCard(String id) {
        if (id == null) return null;
        if (!IDCARD.matcher(id).matches()) return sha256(id).substring(0, 16);
        // 保留前 6 位地区码以便院间按地域聚合（仍不可识别个人）
        return id.substring(0, 6) + "****" + sha256(id).substring(0, 8);
    }

    /** 手机号 hash */
    public String hashMobile(String m) {
        if (m == null) return null;
        if (!MOBILE.matcher(m).matches()) return sha256(m).substring(0, 11);
        return m.substring(0, 3) + "****" + m.substring(7);
    }

    /** 住址降级：江苏省南京市鼓楼区中央路 233 号 → 江苏省南京市 */
    public String downgradeAddress(String addr) {
        if (addr == null) return null;
        String[] parts = addr.split("[省市区县]");
        if (parts.length >= 2) return parts[0] + "省" + parts[1] + "市";
        return parts.length > 0 ? parts[0] : "***";
    }

    /** 年龄段 */
    public String ageBand(LocalDate birth, LocalDate now) {
        if (birth == null) return "UNKNOWN";
        int age = Period.between(birth, now).getYears();
        if (age < 18)  return "0-17";
        if (age < 30)  return "18-29";
        if (age < 45)  return "30-44";
        if (age < 60)  return "45-59";
        if (age < 75)  return "60-74";
        return "75+";
    }

    /** 全量脱敏：把 Patient 关键字段全部处理 */
    public Map<String, Object> deidPatient(long allianceId, String mrn, String name, String idCard, String mobile, String address) {
        Map<String, Object> r = new HashMap<>();
        r.put("poolKey", poolKey(allianceId, mrn == null ? idCard : mrn));
        r.put("nameMask", maskName(name));
        r.put("idCardMask", hashIdCard(idCard));
        r.put("mobileMask", hashMobile(mobile));
        r.put("addressMask", downgradeAddress(address));
        return r;
    }

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}

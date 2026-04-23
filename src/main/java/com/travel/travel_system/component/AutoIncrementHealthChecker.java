package com.travel.travel_system.component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class AutoIncrementHealthChecker implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AutoIncrementHealthChecker.class);

    @Value("${auto-increment-checker.threshold:10000}")
    private long threshold;

    @Value("${auto-increment-checker.auto-fix:true}")
    private boolean autoFixEnabled;

    @Value("${auto-increment-checker.max-gap-ratio:10.0}")
    private double maxGapRatio;

    @Value("${auto-increment-checker.max-table-rows:1000}")
    private long maxTableRows;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        checkAndFixAutoIncrementHealth();
    }

    public void checkAndFixAutoIncrementHealth() {
        try {
            List<Map<String, Object>> tables = jdbcTemplate.queryForList(
                    "SELECT TABLE_NAME, AUTO_INCREMENT, TABLE_ROWS " +
                            "FROM INFORMATION_SCHEMA.TABLES " +
                            "WHERE TABLE_SCHEMA = DATABASE() " +
                            "AND AUTO_INCREMENT IS NOT NULL"
            );

            int fixedCount = 0;
            for (Map<String, Object> table : tables) {
                String tableName = (String) table.get("TABLE_NAME");
                Long autoIncrement = ((Number) table.get("AUTO_INCREMENT")).longValue();
                Long tableRows = ((Number) table.get("TABLE_ROWS")).longValue();

                Object maxIdObj = jdbcTemplate.queryForObject(
                        "SELECT MAX(id) FROM " + tableName,
                        Object.class
                );

                Long maxId = maxIdObj != null ? ((Number) maxIdObj).longValue() : 0L;
                long gap = autoIncrement - maxId;

                if (gap > threshold) {
                    double gapRatio = tableRows > 0 ? (double) gap / tableRows : Double.MAX_VALUE;

                    boolean shouldFix = gapRatio > maxGapRatio && tableRows < maxTableRows;

                    if (shouldFix) {
                        log.warn("[自增健康检查] 表 {} 的 AUTO_INCREMENT 异常: " +
                                        "AUTO_INCREMENT={}, MAX(id)={}, 差值={}, 行数={}, 差值倍数={}",
                                tableName, autoIncrement, maxId, gap, tableRows,
                                String.format("%.2f", gapRatio));

                        if (autoFixEnabled) {
                            fixAutoIncrement(tableName, maxId, autoIncrement);
                            fixedCount++;
                        }
                    } else {
                        log.debug("[自增健康检查] 表 {} 跳过修复: 差值虽大但属于正常范围 " +
                                        "(差值={}, 行数={}, 差值倍数={})",
                                tableName, gap, tableRows,
                                tableRows > 0 ? String.format("%.2f", (double) gap / tableRows) : "N/A");
                    }
                } else {
                    log.debug("[自增健康检查] 表 {} 正常: AUTO_INCREMENT={}, MAX(id)={}, 差值={}",
                            tableName, autoIncrement, maxId, gap);
                }
            }

            if (fixedCount > 0) {
                log.info("[自增健康检查] 完成，共检查 {} 张表，自动修复 {} 张表", tables.size(), fixedCount);
            } else {
                log.info("[自增健康检查] 完成，共检查 {} 张表，所有表正常", tables.size());
            }
        } catch (Exception e) {
            log.error("[自增健康检查] 执行失败", e);
        }
    }

    private void fixAutoIncrement(String tableName, Long maxId, Long oldAutoIncrement) {
        try {
            long newAutoIncrement = maxId + 1;

            jdbcTemplate.execute((java.sql.Connection conn) -> {
                try (java.sql.Statement stmt = conn.createStatement()) {
                    stmt.execute("SET FOREIGN_KEY_CHECKS = 0");
                    stmt.execute(String.format("ALTER TABLE `%s` AUTO_INCREMENT = %d", tableName, newAutoIncrement));
                    stmt.execute("SET FOREIGN_KEY_CHECKS = 1");
                }
                return null;
            });

            log.info("[自增修复] 表 {} 已修复: AUTO_INCREMENT 从 {} 重置为 {}",
                    tableName, oldAutoIncrement, newAutoIncrement);
        } catch (Exception e) {
            log.error("[自增修复] 表 {} 修复失败: {}", tableName, e.getMessage());
        }
    }
}

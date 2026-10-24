package com.medbox.server.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.medbox.server.common.exception.BizException;
import com.medbox.server.common.exception.ErrorCode;
import com.medbox.server.common.security.AuthContext;
import com.medbox.server.domain.User;
import com.medbox.server.domain.enums.UserStatus;
import com.medbox.server.dto.PageResult;
import com.medbox.server.dto.admin.AdminUserRow;
import com.medbox.server.mapper.AdminCleanupMapper;
import com.medbox.server.mapper.AdminUserMapper;
import com.medbox.server.mapper.UserMapper;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理后台测试数据清理（见文档 02 第 10 章）。
 *
 * <p>仅由 {@code ADMIN} 角色经 {@code AdminAuthInterceptor} 进入，本类不再判角色。
 * 硬删前有两道护栏：目标不存在 → 40401；目标 {@code ACTIVE} → 40902（防止误删活跃真账号）。
 */
@Service
public class AdminUserService {

    private static final Logger log = LoggerFactory.getLogger(AdminUserService.class);

    private static final int MAX_SIZE = 100;
    private static final int DEFAULT_SIZE = 20;

    private final UserMapper userMapper;
    private final AdminUserMapper adminUserMapper;
    private final AdminCleanupMapper cleanupMapper;

    public AdminUserService(UserMapper userMapper, AdminUserMapper adminUserMapper, AdminCleanupMapper cleanupMapper) {
        this.userMapper = userMapper;
        this.adminUserMapper = adminUserMapper;
        this.cleanupMapper = cleanupMapper;
    }

    /**
     * 列出用户（供挑测试号）；支持 status / role 精确 + keyword 模糊 + 分页（上限 100）。
     */
    public PageResult<AdminUserRow> list(String status, String role, String keyword, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = size <= 0 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        int offset = (safePage - 1) * safeSize;
        long total = adminUserMapper.countByFilter(status, role, keyword);
        List<User> rows = adminUserMapper.selectByFilter(status, role, keyword, safeSize, offset);
        return PageResult.of(rows.stream().map(AdminUserRow::from).toList(), total, safePage, safeSize);
    }

    /**
     * 硬删 + 级联清理（见文档 02 第 10.2 / 10.3）。
     *
     * <p>护栏：目标不存在 → 40401；目标 {@code ACTIVE} → 40902。通过后在单事务内按依赖顺序（先子后父）级联硬删其孤儿数据。
     */
    @Transactional
    public void purge(String userId) {
        User user = userMapper.selectOne(new QueryWrapper<User>().eq("user_id", userId));
        if (user == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "目标用户不存在");
        }
        if (user.getStatus() == UserStatus.ACTIVE) {
            throw BizException.of(ErrorCode.ADMIN_ACTIVE_CONFLICT, "活跃账号禁止经管理接口硬删");
        }

        String operator = AuthContext.current() == null ? "unknown" : AuthContext.current().userId();
        log.info("ADMIN purge test-data: operator={}, target={}, role={}", operator, userId,
                user.getRole() == null ? null : user.getRole().name());

        cleanupMapper.deleteMedPlanItems(userId);
        cleanupMapper.deleteMedPlans(userId);
        cleanupMapper.deleteGuardianRelations(userId);
        cleanupMapper.deleteDeviceEvents(userId);
        cleanupMapper.deleteDevices(userId);
        cleanupMapper.deleteMedicineStocks(userId);
        cleanupMapper.deleteMedicines(userId);
        cleanupMapper.deleteMedRecords(userId);
        cleanupMapper.deleteCaptures(userId);
        cleanupMapper.deleteEnvSamples(userId);
        cleanupMapper.deleteAlarms(userId);
        cleanupMapper.deleteAlarmSettings(userId);
        cleanupMapper.deleteRefreshTokens(userId);
        cleanupMapper.deleteUser(userId);
    }
}

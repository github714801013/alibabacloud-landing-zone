package com.aliyun.autowonder.integration.wecom;

import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class WecomRobotBindingDao {
    private final JdbcTemplate jdbc;
    private final BeanPropertyRowMapper<WecomRobotBindingDO> mapper =
            new BeanPropertyRowMapper<>(WecomRobotBindingDO.class);

    public WecomRobotBindingDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<WecomRobotBindingDO> list(Long tenantId) {
        return jdbc.query("SELECT * FROM wecom_robot_binding WHERE tenant_id=? AND is_deleted=0"
                + " ORDER BY id DESC", mapper, tenantId);
    }

    public WecomRobotBindingDO find(Long tenantId, Long id) {
        return jdbc.query("SELECT * FROM wecom_robot_binding WHERE tenant_id=? AND id=? AND is_deleted=0",
                mapper, tenantId, id).stream().findFirst().orElse(null);
    }

    /** 投递围栏使用的行锁读：投递期间阻塞绑定编辑，避免用已撤销的路由发消息。 */
    public WecomRobotBindingDO findForUpdate(Long tenantId, Long id) {
        return jdbc.query("SELECT * FROM wecom_robot_binding WHERE tenant_id=? AND id=? AND is_deleted=0"
                + " FOR UPDATE", mapper, tenantId, id).stream().findFirst().orElse(null);
    }

    // 仅已鉴权的回调路由与后台投递可以使用全局读取。
    public WecomRobotBindingDO findGlobal(Long id) {
        return jdbc.query("SELECT * FROM wecom_robot_binding WHERE id=? AND is_deleted=0",
                mapper, id).stream().findFirst().orElse(null);
    }

    public WecomRobotBindingDO findSharedByConfigAndGroup(Long platformConfigId, String groupId) {
        return jdbc.query("SELECT * FROM wecom_robot_binding WHERE platform_config_id=? AND group_id=?"
                + " AND is_deleted=0 LIMIT 1", mapper, platformConfigId, groupId)
                .stream().findFirst().orElse(null);
    }

    public void insert(WecomRobotBindingDO row) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement(
                    "INSERT INTO wecom_robot_binding (tenant_id,platform_config_id,group_id,agent_id,"
                            + "route_version,status,creator_id,modifier_id) VALUES (?,?,?,?,?,?,?,?)",
                    new String[]{"id"});
            ps.setLong(1, row.getTenantId());
            ps.setLong(2, row.getPlatformConfigId());
            ps.setString(3, row.getGroupId());
            ps.setLong(4, row.getAgentId());
            ps.setLong(5, row.getRouteVersion() == null ? 0L : row.getRouteVersion());
            ps.setString(6, row.getStatus());
            ps.setLong(7, row.getCreatorId());
            ps.setLong(8, row.getModifierId());
            return ps;
        }, keys);
        row.setId(keys.getKey().longValue());
        row.setVersion(0);
        if (row.getRouteVersion() == null) {
            row.setRouteVersion(0L);
        }
    }

    public int update(WecomRobotBindingDO row) {
        return jdbc.update("UPDATE wecom_robot_binding SET group_id=?,agent_id=?,status=?,route_version=?,"
                        + "modifier_id=?,version=version+1 WHERE tenant_id=? AND id=? AND version=?"
                        + " AND is_deleted=0",
                row.getGroupId(), row.getAgentId(), row.getStatus(), row.getRouteVersion(),
                row.getModifierId(), row.getTenantId(), row.getId(), row.getVersion());
    }

    public void delete(Long tenantId, Long id) {
        jdbc.update("DELETE FROM wecom_robot_binding WHERE tenant_id=? AND id=?", tenantId, id);
    }

    public void health(WecomRobotBindingDO row, String error) {
        if (error == null) {
            jdbc.update("UPDATE wecom_robot_binding SET last_success_at=CURRENT_TIMESTAMP,last_error=NULL"
                    + " WHERE tenant_id=? AND id=?", row.getTenantId(), row.getId());
        } else {
            jdbc.update("UPDATE wecom_robot_binding SET last_error=? WHERE tenant_id=? AND id=?",
                    error, row.getTenantId(), row.getId());
        }
    }
}

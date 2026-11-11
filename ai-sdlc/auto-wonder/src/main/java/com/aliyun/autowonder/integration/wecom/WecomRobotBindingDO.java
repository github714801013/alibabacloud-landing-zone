package com.aliyun.autowonder.integration.wecom;

import lombok.Data;

import java.util.Date;

/** 企业微信共享群绑定；群 ID 与企业微信机器人各自一个会话入口。 */
@Data
public class WecomRobotBindingDO {
    private Long id;
    private Long tenantId;
    private Long platformConfigId;
    private String groupId;
    private Long agentId;
    private Long routeVersion;
    private String status;
    private Date lastSuccessAt;
    private String lastError;
    private Long creatorId;
    private Long modifierId;
    private Integer version;
}

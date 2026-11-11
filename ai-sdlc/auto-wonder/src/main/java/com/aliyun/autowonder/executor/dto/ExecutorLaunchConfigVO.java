package com.aliyun.autowonder.executor.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * The launch config shared by the page's 启动命令 dialog and the MCP build_executor_launch_command tool.
 * A never-configured executor reports every field null at version 1, so the first write can carry version=1.
 */
@Getter
@Setter
public class ExecutorLaunchConfigVO {
    private String model;
    private String reasoningEffort;
    private String contextWindow;
    private String memoryMode;
    private Integer maxConcurrentDispatches;
    private String runtimeVersion;
    private Boolean runtimeVersionPinned;
    private String workspaceRoot;
    private String daemonName;
    private Boolean debug;
    private com.aliyun.autowonder.executor.ModelUpgradePolicy modelUpgradePolicy;
    /** 手输的扩展命令参数；从未保存过时为 null。与客户端类型无关。 */
    private String extraArgs;
    /** 前置命令；从未保存过或仅空白时为 null。与客户端类型无关。 */
    private String prefixCommand;
    /** 执行器级平台服务地址；未配置时为 null，表示使用全局品牌地址。 */
    private String serverBaseUrl;
    private Integer version;
}

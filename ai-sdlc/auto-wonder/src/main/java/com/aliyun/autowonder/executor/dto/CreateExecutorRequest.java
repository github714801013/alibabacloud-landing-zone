package com.aliyun.autowonder.executor.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * Create-executor payload. The launch options are optional on the wire but never optional in the database: the
 * server resolves every omitted value to the same default the create dialog pre-fills and persists the result
 * inside the create transaction, so a brand new executor already has a complete, command-ready config.
 */
@Getter
@Setter
public class CreateExecutorRequest {
    private String name;
    private String clientKind;
    private String memoryMode;
    private Integer maxConcurrentDispatches;
    /** Qoder model id; ignored for non-Qoder client kinds. */
    private String model;
    private String reasoningEffort;
    private String contextWindow;
    /** 手输的扩展命令参数，追加到启动命令末尾；空白表示不使用扩展参数。与客户端类型无关。 */
    private String extraArgs;
    /** 前置命令，原样放在运行时命令之前；空白表示未配置。与客户端类型无关。 */
    private String prefixCommand;
}

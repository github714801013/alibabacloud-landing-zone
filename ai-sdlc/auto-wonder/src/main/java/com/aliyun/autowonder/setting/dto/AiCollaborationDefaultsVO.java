package com.aliyun.autowonder.setting.dto;

import com.aliyun.autowonder.setting.ClarificationPromptTemplateCatalog;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AiCollaborationDefaultsVO {
    private boolean owner;
    private Clarify clarify;
    private Delivery delivery;

    @Getter
    @Setter
    public static class Clarify {
        private Long defaultSquadId;
        private Long defaultAgentId;
        private boolean autoSendFirstPrompt;
        /** 空间级提示词模板目录；未配置或数据损坏时为 null。 */
        private ClarificationPromptTemplateCatalog.Catalog promptTemplates;
        /** 已配置组合是否仍可用；未配置时为 true。 */
        private boolean valid;
    }

    @Getter
    @Setter
    public static class Delivery {
        private Long defaultSquadId;
        private Long defaultAgentId;
        /** 已配置组合是否仍可用；未配置时为 true。 */
        private boolean valid;
    }
}

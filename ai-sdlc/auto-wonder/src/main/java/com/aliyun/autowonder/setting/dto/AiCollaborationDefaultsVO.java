package com.aliyun.autowonder.setting.dto;

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

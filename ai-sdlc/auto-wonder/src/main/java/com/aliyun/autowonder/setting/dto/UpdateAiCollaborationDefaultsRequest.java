package com.aliyun.autowonder.setting.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class UpdateAiCollaborationDefaultsRequest {
    private List<Item> items;

    @Getter
    @Setter
    public static class Item {
        private String key;
        /** 十进制 ID、true/false；空串表示清除该键。 */
        private String value;
    }
}

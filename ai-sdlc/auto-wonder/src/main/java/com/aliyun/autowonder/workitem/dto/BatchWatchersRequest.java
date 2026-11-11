package com.aliyun.autowonder.workitem.dto;

import lombok.Getter;
import lombok.Setter;

/** 批量代管关注人请求体。 */
@Getter
@Setter
public class BatchWatchersRequest {
    /**
     * userIds 的原始 JSON 形状：刻意不声明为 List&lt;Long&gt;，避免 Jackson 绑定把 77.9 截断成 77、
     * 把 "77" 强转成 77；严格校验（非空数组、逐元素原生正整数）统一在服务层写入前执行。
     */
    private Object userIds;
}

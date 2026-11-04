package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.json.JSONArray;
import com.aliyun.autowonder.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 解析工单正文任务同步块中按任务划分的负责人。
 * 只认 aw_user_id（AutoWonder 用户 ID），与任务来源无关；当前载体块名为 autowonder-jira-status。
 */
public final class WorkitemTaskOwners {

    private static final Logger log = LoggerFactory.getLogger(WorkitemTaskOwners.class);
    private static final String TASK_STATUS_START = "<!-- autowonder-jira-status:start";
    private static final String TASK_STATUS_END = "autowonder-jira-status:end -->";

    private WorkitemTaskOwners() {
    }

    /**
     * 按任务顺序返回 included 且 aw_user_id 有效的负责人，已去重。
     * 无同步块、无任务或解析失败时返回空列表，由调用方沿用原有转交规则。
     */
    public static List<Long> orderedOwnerIds(String contentMd) {
        if (contentMd == null || contentMd.isBlank()) {
            return List.of();
        }
        int start = contentMd.indexOf(TASK_STATUS_START);
        int end = start < 0 ? -1 : contentMd.indexOf(TASK_STATUS_END, start);
        if (start < 0 || end < 0) {
            return List.of();
        }
        try {
            JSONObject block = JSON.parseObject(
                    contentMd.substring(start + TASK_STATUS_START.length(), end).trim());
            JSONArray tasks = block == null ? null : block.getJSONArray("tasks");
            if (tasks == null || tasks.size() == 0) {
                return List.of();
            }
            Set<Long> owners = new LinkedHashSet<>();
            for (int i = 0; i < tasks.size(); i++) {
                JSONObject task = tasks.getJSONObject(i);
                if (task == null || Boolean.FALSE.equals(task.getBoolean("included"))) {
                    continue;
                }
                Object rawOwner = task.get("aw_user_id");
                if (rawOwner instanceof Number number && number.longValue() > 0) {
                    owners.add(number.longValue());
                }
            }
            return new ArrayList<>(owners);
        } catch (RuntimeException e) {
            log.warn("workitem task status block owners unparsable: {}", e.getMessage());
            return List.of();
        }
    }
}

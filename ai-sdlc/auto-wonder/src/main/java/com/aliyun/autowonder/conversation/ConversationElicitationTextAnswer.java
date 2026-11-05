package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.json.JSONArray;
import com.aliyun.autowonder.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 把群里的一条文本映射成单题问答卡片的答案 JSON。
 *
 * <p>只在能无歧义作答时返回答案：表单必须恰好一道题；选择题支持 1 基序号、
 * 选项值或选项标题；多选题支持逗号分隔（中英文逗号）的序号/标签；布尔与数字
 * 按字面值解析。其余情况返回 {@code null}，由调用方中断等待，绝不猜测。
 */
final class ConversationElicitationTextAnswer {

    private ConversationElicitationTextAnswer() {}

    /** @return 形如 {@code {"q0": value}} 的答案 JSON；无法映射时为 null。 */
    static String map(String text, String schemaJson) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty() || schemaJson == null || schemaJson.isBlank()) {
            return null;
        }
        JSONObject field;
        String name;
        try {
            JSONObject schema = JSON.parseObject(schemaJson);
            JSONObject properties = schema == null ? null : schema.getJSONObject("properties");
            if (properties == null || properties.size() != 1) {
                return null;
            }
            name = properties.keySet().iterator().next();
            Object raw = properties.get(name);
            field = raw instanceof JSONObject object ? object : null;
        } catch (RuntimeException e) {
            return null;
        }
        if (field == null) {
            return null;
        }
        Object answer = answerValue(field, value);
        if (answer == null) {
            return null;
        }
        return new JSONObject().fluentPut(name, answer).toJSONString();
    }

    private static Object answerValue(JSONObject field, String text) {
        String type = field.getString("type");
        if (type == null) {
            return null;
        }
        switch (type) {
            case "string": {
                List<Option> options = options(field.get("oneOf"));
                if (options == null) options = options(field.get("enum"));
                return options == null ? text : single(options, text);
            }
            case "boolean":
                return bool(text);
            case "integer":
                return integer(text);
            case "number":
                return number(text);
            case "array": {
                JSONObject items = field.getJSONObject("items");
                if (items == null) return null;
                List<Option> options = options(items.get("anyOf"));
                if (options == null) options = options(items.get("oneOf"));
                if (options == null) options = options(items.get("enum"));
                return options == null ? null : multiple(options, text);
            }
            default:
                return null;
        }
    }

    private static String single(List<Option> options, String text) {
        Integer index = index(text);
        if (index != null) {
            return index >= 1 && index <= options.size() ? options.get(index - 1).value() : null;
        }
        Option matched = match(options, text);
        return matched == null ? null : matched.value();
    }

    private static JSONArray multiple(List<Option> options, String text) {
        List<String> tokens = tokens(text);
        if (tokens == null) {
            return null;
        }
        JSONArray selected = new JSONArray();
        for (String token : tokens) {
            Integer index = index(token);
            Option option = index != null
                    ? index >= 1 && index <= options.size() ? options.get(index - 1) : null
                    : match(options, token);
            if (option == null) {
                return null;
            }
            if (!selected.contains(option.value())) {
                selected.add(option.value());
            }
        }
        return selected.isEmpty() ? null : selected;
    }

    /**
     * 逗号分隔视为多选列表；纯数字的空格分隔（如 {@code "1 3"}）同样视为列表；
     * 其余整体当作一个标签，避免把带空格的选项标题拆坏。
     */
    private static List<String> tokens(String text) {
        String normalized = text.replace('，', ',');
        if (normalized.contains(",")) {
            List<String> parts = new ArrayList<>();
            for (String part : normalized.split(",")) {
                if (!part.isBlank()) {
                    parts.add(part.trim());
                }
            }
            return parts.isEmpty() ? null : parts;
        }
        String[] parts = normalized.split("\\s+");
        if (parts.length > 1 && allDigits(parts)) {
            return List.of(parts);
        }
        return List.of(normalized.trim());
    }

    private static boolean allDigits(String[] parts) {
        for (String part : parts) {
            if (index(part) == null) {
                return false;
            }
        }
        return true;
    }

    /** 1 基序号；不是整数时返回 null。 */
    private static Integer index(String text) {
        String digits = text.startsWith("-") ? text.substring(1) : text;
        if (digits.isEmpty() || !digits.chars().allMatch(Character::isDigit)) {
            return null;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Option match(List<Option> options, String text) {
        String wanted = text.toLowerCase(Locale.ROOT);
        for (Option option : options) {
            if (option.value().toLowerCase(Locale.ROOT).equals(wanted)
                    || option.label().toLowerCase(Locale.ROOT).equals(wanted)) {
                return option;
            }
        }
        return null;
    }

    private static Boolean bool(String text) {
        switch (text.toLowerCase(Locale.ROOT)) {
            case "true":
            case "yes":
            case "1":
            case "是":
                return Boolean.TRUE;
            case "false":
            case "no":
            case "0":
            case "否":
                return Boolean.FALSE;
            default:
                return null;
        }
    }

    private static Long integer(String text) {
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal number(String text) {
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<Option> options(Object raw) {
        if (!(raw instanceof List<?> entries) || entries.isEmpty()) {
            return null;
        }
        List<Option> options = new ArrayList<>();
        for (Object entry : entries) {
            Option option = entry instanceof JSONObject object ? option(object) : scalar(entry);
            if (option == null) {
                return null;
            }
            options.add(option);
        }
        return options;
    }

    /** oneOf / anyOf 选项：const 是提交值，title 是用户看到的标签。 */
    private static Option option(JSONObject entry) {
        Object constant = entry.get("const");
        if (constant == null) {
            return null;
        }
        String value = String.valueOf(constant);
        Object title = entry.get("title");
        return new Option(value, title == null ? value : String.valueOf(title));
    }

    /** enum 选项只有值，没有标签。 */
    private static Option scalar(Object entry) {
        return entry == null ? null : new Option(String.valueOf(entry), String.valueOf(entry));
    }

    private record Option(String value, String label) {}
}

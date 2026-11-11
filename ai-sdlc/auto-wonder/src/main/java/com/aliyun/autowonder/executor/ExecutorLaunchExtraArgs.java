package com.aliyun.autowonder.executor;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 扩展命令参数：操作者在「启动命令」弹窗手输、被追加到启动命令末尾的自由参数文本。保存时归一化并校验，
 * 生成命令时按同一套规则分词，因此落库的值一定能被成功分词。
 *
 * <p>扩展参数一律追加在平台参数之后，因此与平台参数同名时以扩展参数为准——这是有意的：平台界面上没有的
 * 取值（新模型、新档位、临时调试开关）可以就地覆盖，不必先改平台代码。连接参数
 * --token/--ws-url/--executor-id 由平台签发，扩展参数覆盖会把令牌导向外部端点，因此直接拒绝。
 */
public final class ExecutorLaunchExtraArgs {

    static final int MAX_LENGTH = 1000;
    /** 连接类平台参数：只能由平台生成，扩展参数不得覆盖。 */
    static final Set<String> RESERVED_FLAGS = Set.of("--ws-url", "--token", "--executor-id");

    private ExecutorLaunchExtraArgs() {
    }

    /**
     * 校验并归一化待落库的文本；空白返回 null，表示该执行器没有扩展参数。保存时就跑一遍分词，
     * 让「引号不闭合」这类错误在操作者点击保存时暴露，而不是等到生成命令才发现。
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        if (value.length() > MAX_LENGTH) {
            throw invalid("扩展命令参数最多 " + MAX_LENGTH + " 个字符");
        }
        for (String flag : flagNames(tokenize(value))) {
            if (RESERVED_FLAGS.contains(flag)) {
                throw invalid("扩展命令参数不能覆盖平台连接参数：" + flag);
            }
        }
        return value;
    }

    /** 按 shell 规则分词：空白分隔，引号成组，反斜杠转义下一个字符。 */
    public static List<String> tokenize(String value) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean started = false;
        char quote = 0;
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (ch < ' ' && ch != '\t') {
                throw invalid("扩展命令参数不能包含换行等控制字符");
            }
            if (quote != 0) {
                if (ch == quote) {
                    quote = 0;
                } else {
                    current.append(ch);
                }
                continue;
            }
            if (ch == '\'' || ch == '"') {
                quote = ch;
                started = true;
                continue;
            }
            if (ch == '\\') {
                if (index + 1 >= value.length()) {
                    throw invalid("扩展命令参数不能以反斜杠结尾");
                }
                current.append(value.charAt(++index));
                started = true;
                continue;
            }
            if (Character.isWhitespace(ch)) {
                if (started) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    started = false;
                }
                continue;
            }
            current.append(ch);
            started = true;
        }
        if (quote != 0) {
            throw invalid("扩展命令参数存在未闭合的引号");
        }
        if (started) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    /**
     * 取出每个 token 的参数名（{@code --flag=value} 只取等号前的 {@code --flag}），用于判断扩展参数覆盖了
     * 哪些平台参数。裸值会原样返回，它们不会与平台参数同名，因此不会误判。
     */
    public static Set<String> flagNames(List<String> tokens) {
        Set<String> names = new LinkedHashSet<>();
        for (String token : tokens) {
            int separator = token.indexOf('=');
            names.add(separator < 0 ? token : token.substring(0, separator));
        }
        return names;
    }

    private static BizException invalid(String message) {
        return new BizException(ErrorCode.EXECUTOR_LAUNCH_CONFIG_EXTRA_ARGS_INVALID, message);
    }
}

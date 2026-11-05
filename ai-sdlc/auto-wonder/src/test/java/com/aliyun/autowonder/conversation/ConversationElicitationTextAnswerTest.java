package com.aliyun.autowonder.conversation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ConversationElicitationTextAnswerTest {

    @Test
    void freeTextAnswersTheOnlyQuestion() {
        assertEquals("{\"q0\":\"明天上线\"}", answer("明天上线",
                schema("\"q0\":{\"type\":\"string\",\"title\":\"上线时间\"}")));
    }

    @Test
    void selectAcceptsOneBasedIndexConstAndTitle() {
        String schema = schema("\"q0\":{\"type\":\"string\",\"oneOf\":["
                + "{\"const\":\"nextjs\",\"title\":\"Next.js 全栈\"},"
                + "{\"const\":\"vite\",\"title\":\"Vite + Spring\"}]}");

        assertEquals("{\"q0\":\"vite\"}", answer("2", schema));
        assertEquals("{\"q0\":\"vite\"}", answer("vite", schema));
        assertEquals("{\"q0\":\"nextjs\"}", answer("next.js 全栈", schema));
    }

    @Test
    void selectRejectsOutOfRangeIndexAndProse() {
        String schema = schema("\"q0\":{\"type\":\"string\",\"oneOf\":["
                + "{\"const\":\"a\",\"title\":\"甲\"}]}");

        assertNull(answer("2", schema));
        assertNull(answer("随便说点什么", schema));
    }

    @Test
    void selectAcceptsEnumValues() {
        String schema = schema("\"q0\":{\"type\":\"string\",\"enum\":[\"dev\",\"prod\"]}");

        assertEquals("{\"q0\":\"prod\"}", answer("PROD", schema));
        assertEquals("{\"q0\":\"dev\"}", answer("1", schema));
    }

    @Test
    void multiselectAcceptsCommaLabelsAndNumericSpaceLists() {
        String schema = schema("\"q0\":{\"type\":\"array\",\"items\":{\"anyOf\":["
                + "{\"const\":\"api\",\"title\":\"接口\"},"
                + "{\"const\":\"web\",\"title\":\"页面\"},"
                + "{\"const\":\"db\",\"title\":\"数据库\"}]}}");

        assertEquals("{\"q0\":[\"api\",\"db\"]}", answer("接口，数据库", schema));
        assertEquals("{\"q0\":[\"web\",\"db\"]}", answer("2 3", schema));
        assertEquals("{\"q0\":[\"api\"]}", answer("api", schema));
    }

    @Test
    void multiselectRejectsTheWholeReplyWhenAnyTokenIsUnknown() {
        String schema = schema("\"q0\":{\"type\":\"array\",\"items\":{\"oneOf\":["
                + "{\"const\":\"a\",\"title\":\"甲\"},"
                + "{\"const\":\"b\",\"title\":\"乙\"}]}}");

        assertNull(answer("1,9", schema));
        assertNull(answer("甲,丙", schema));
    }

    @Test
    void booleanAndNumberTypesAreParsedLiterally() {
        assertEquals("{\"q0\":true}", answer("是",
                schema("\"q0\":{\"type\":\"boolean\"}")));
        assertEquals("{\"q0\":false}", answer("no",
                schema("\"q0\":{\"type\":\"boolean\"}")));
        assertEquals("{\"q0\":3}", answer("3",
                schema("\"q0\":{\"type\":\"integer\"}")));
        assertEquals("{\"q0\":1.5}", answer("1.5",
                schema("\"q0\":{\"type\":\"number\"}")));
        assertNull(answer("很多", schema("\"q0\":{\"type\":\"number\"}")));
    }

    @Test
    void multipleQuestionsAreNeverGuessed() {
        assertNull(answer("2", schema("\"q0\":{\"type\":\"string\"},\"q1\":{\"type\":\"string\"}")));
    }

    @Test
    void emptyTextAndBrokenSchemaReturnNull() {
        assertNull(answer("   ", schema("\"q0\":{\"type\":\"string\"}")));
        assertNull(ConversationElicitationTextAnswer.map("2", "not-json"));
        assertNull(ConversationElicitationTextAnswer.map("2", null));
    }

    private static String answer(String text, String schema) {
        return ConversationElicitationTextAnswer.map(text, schema);
    }

    private static String schema(String properties) {
        return "{\"type\":\"object\",\"properties\":{" + properties + "}}";
    }
}

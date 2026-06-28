package com.reubenagent.document.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KnowledgeRouteTokenizer 纯函数单元测试。
 *
 * @author reuben
 * @since 2026-06-28
 */
@DisplayName("KnowledgeRouteTokenizer")
class KnowledgeRouteTokenizerTest {

    private static final String DELIMITER = "[\\s、，,；;：:（）()\\-的和及与或]+";
    private static final int MIN_LENGTH = 2;
    private static final int MAX_COUNT = 40;

    @Nested
    @DisplayName("基础分词")
    class BasicTokenization {

        @Test
        @DisplayName("空文本返回空列表")
        void shouldReturnEmptyForNull() {
            assertThat(KnowledgeRouteTokenizer.tokenize(null, DELIMITER, MIN_LENGTH, MAX_COUNT)).isEmpty();
            assertThat(KnowledgeRouteTokenizer.tokenize("", DELIMITER, MIN_LENGTH, MAX_COUNT)).isEmpty();
            assertThat(KnowledgeRouteTokenizer.tokenize("  ", DELIMITER, MIN_LENGTH, MAX_COUNT)).isEmpty();
        }

        @Test
        @DisplayName("简单空格分隔")
        void shouldSplitBySpaces() {
            List<String> tokens = KnowledgeRouteTokenizer.tokenize(
                    "知识路由 文档管理", DELIMITER, MIN_LENGTH, MAX_COUNT);
            assertThat(tokens).contains("知识路由", "文档管理");
        }

        @Test
        @DisplayName("中文逗号分隔")
        void shouldSplitByChineseComma() {
            List<String> tokens = KnowledgeRouteTokenizer.tokenize(
                    "产品文档，运维手册，API参考", DELIMITER, MIN_LENGTH, MAX_COUNT);
            assertThat(tokens).contains("产品文档", "运维手册", "API参考");
        }

        @Test
        @DisplayName("混合分隔符")
        void shouldSplitByMixedDelimiters() {
            List<String> tokens = KnowledgeRouteTokenizer.tokenize(
                    "知识路由、文档管理和API参考，以及运维手册", DELIMITER, MIN_LENGTH, MAX_COUNT);
            assertThat(tokens).contains("知识路由", "文档管理", "API参考", "运维手册");
        }

        @Test
        @DisplayName("过滤短词（小于 minLength）")
        void shouldFilterShortTokens() {
            List<String> tokens = KnowledgeRouteTokenizer.tokenize("A B C 知识", DELIMITER, 2, MAX_COUNT);
            assertThat(tokens).contains("知识");
            assertThat(tokens).doesNotContain("A", "B", "C");
        }

        @Test
        @DisplayName("去重")
        void shouldDeduplicate() {
            List<String> tokens = KnowledgeRouteTokenizer.tokenize(
                    "知识 知识 路由 路由", DELIMITER, MIN_LENGTH, MAX_COUNT);
            long knowledgeCount = tokens.stream().filter(t -> t.equals("知识")).count();
            long routeCount = tokens.stream().filter(t -> t.equals("路由")).count();
            assertThat(knowledgeCount).isOne();
            assertThat(routeCount).isOne();
        }
    }

    @Nested
    @DisplayName("中文 n-gram 扩展")
    class ChineseNGram {

        @Test
        @DisplayName("4 字词应产生 2-gram 和 3-gram")
        void shouldGenerateNGramsFor4CharWord() {
            List<String> tokens = KnowledgeRouteTokenizer.tokenize(
                    "知识路由引擎", DELIMITER, MIN_LENGTH, MAX_COUNT);
            assertThat(tokens).contains("知识路由引擎");
            assertThat(tokens).contains("知识路由");
            assertThat(tokens).contains("识路由");
            assertThat(tokens).contains("路由引擎");
            assertThat(tokens).contains("知识");
            assertThat(tokens).contains("识路");
            assertThat(tokens).contains("路由");
            assertThat(tokens).contains("由引");
            assertThat(tokens).contains("引擎");
        }

        @Test
        @DisplayName("3 字词不产生 n-gram")
        void shouldNotGenerateNGramsFor3CharWord() {
            List<String> tokens = KnowledgeRouteTokenizer.tokenize(
                    "知识库", DELIMITER, MIN_LENGTH, MAX_COUNT);
            assertThat(tokens).contains("知识库");
            assertThat(tokens).hasSize(1);
        }

        @Test
        @DisplayName("NGram 也遵循 minLength 过滤")
        void shouldFilterShortNGrams() {
            List<String> tokens = KnowledgeRouteTokenizer.tokenize(
                    "知识路由引擎", DELIMITER, 3, MAX_COUNT);
            // 3-gram+ only, no 2-gram
            assertThat(tokens).contains("知识路由引擎");
            assertThat(tokens).contains("知识路由");
            assertThat(tokens).contains("路由引擎");
            assertThat(tokens).doesNotContain("知识", "识路", "路由");
        }
    }

    @Nested
    @DisplayName("截断与上限")
    class Truncation {

        @Test
        @DisplayName("超过 maxCount 时截断")
        void shouldTruncateAtMaxCount() {
            // 生成大量词
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i <= 50; i++) {
                if (i > 1) sb.append(" ");
                sb.append("词").append(String.format("%02d", i));
            }
            List<String> tokens = KnowledgeRouteTokenizer.tokenize(
                    sb.toString(), DELIMITER, MIN_LENGTH, 10);
            assertThat(tokens).hasSizeLessThanOrEqualTo(10);
        }
    }

    @Nested
    @DisplayName("现实场景")
    class RealWorldScenarios {

        @Test
        @DisplayName("用户问题分词")
        void shouldTokenizeUserQuestion() {
            List<String> tokens = KnowledgeRouteTokenizer.tokenize(
                    "知识路由引擎如何根据scope-topic-document三级路由选出最匹配的知识范围",
                    DELIMITER, MIN_LENGTH, MAX_COUNT);
            assertThat(tokens).isNotEmpty();
            assertThat(tokens).contains("知识路由引擎如何根据scope");
            // 不应包含纯标点词
            assertThat(tokens).noneMatch(t -> t.matches("[\\-]+"));
        }

        @Test
        @DisplayName("产品名称中的英文")
        void shouldHandleMixedChineseAndEnglish() {
            List<String> tokens = KnowledgeRouteTokenizer.tokenize(
                    "Spring AI Embedding 使用指南", DELIMITER, MIN_LENGTH, MAX_COUNT);
            // 英文词独立保留
            assertThat(tokens).contains("Spring");
            assertThat(tokens).contains("Embedding");
            assertThat(tokens).contains("使用指南");
        }

        @Test
        @DisplayName("纯英文不分词")
        void shouldPreserveEnglishTokens() {
            List<String> tokens = KnowledgeRouteTokenizer.tokenize(
                    "API Reference Guide", DELIMITER, MIN_LENGTH, MAX_COUNT);
            assertThat(tokens).contains("API", "Reference", "Guide");
        }
    }
}

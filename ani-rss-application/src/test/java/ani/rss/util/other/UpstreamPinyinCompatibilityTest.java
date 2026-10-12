package ani.rss.util.other;

import ani.rss.commons.PinyinUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UpstreamPinyinCompatibilityTest {
    @Test
    void polyphonicTitlesUseWordPronunciationAndMatchingSortInitial() {
        assertEquals("chong qing", PinyinUtils.getPinyin("重庆"));
        assertEquals("chong qi", PinyinUtils.getPinyin("重启"));
        assertEquals("chong sheng", PinyinUtils.getPinyin("重生"));
        assertEquals("tiao jiao", PinyinUtils.getPinyin("调教"));
        assertEquals("C", PinyinUtils.getPinyinInitialLetters("重启人生"));
    }

    @Test
    void existingNumericBlankAndSeparatorBehaviorIsPreserved() {
        assertEquals("chong-qing", PinyinUtils.getPinyin("重庆", "-"));
        assertEquals("0", PinyinUtils.getPinyinInitialLetters("123"));
        assertEquals("0", PinyinUtils.getPinyinInitialLetters(""));
        assertEquals("", PinyinUtils.getPinyin(""));
    }
}

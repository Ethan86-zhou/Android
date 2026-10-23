package com.ethan.lexi;

import org.junit.Test;

import static org.junit.Assert.*;

public final class ChineseLookupTest {
    @Test public void englishNumbersAndPunctuationKeepTheEnglishSearchPath() {
        for (String value : new String[]{"apple", "  RUNNING  ", "USB-C", "100%", "\" OR *", "😀", ""}) {
            assertFalse(value, DictionaryRepository.containsChinese(value));
        }
        assertFalse(DictionaryRepository.containsChinese(null));
    }

    @Test public void commonTraditionalAndSupplementaryChineseChooseTheChineseSearchPath() {
        for (String value : new String[]{"苹果", "蘋果", "USB 接口", "hello，世界", character(0x20000), character(0x323af)}) {
            assertTrue(value, DictionaryRepository.containsChinese(value));
        }
    }

    @Test public void tokenRangesMatchThePackagedIndexAtEveryBoundary() {
        int[][] ranges = {{0x3400, 0x4dbf}, {0x4e00, 0x9fff}, {0xf900, 0xfaff}, {0x20000, 0x323af}};
        for (int[] range : ranges) {
            assertTrue(DictionaryRepository.containsChinese(character(range[0])));
            assertTrue(DictionaryRepository.containsChinese(character(range[1])));
            assertFalse(DictionaryRepository.containsChinese(character(range[0] - 1)));
            assertFalse(DictionaryRepository.containsChinese(character(range[1] + 1)));
        }
    }

    @Test public void repeatedCharactersBecomeOneAndTokenWithoutFtsSyntaxFromInput() {
        assertEquals("h82f9 h679c", ChineseLookup.matchExpression("苹果苹果"));
        assertEquals("h82f9 h679c", ChineseLookup.matchExpression("\"苹果% OR *果\" () ' AND -"));
        assertEquals("h20000 h4e2d", ChineseLookup.matchExpression(character(0x20000) + "中" + character(0x20000)));
        assertEquals("", ChineseLookup.matchExpression("ASCII % * OR 123 😀"));
    }

    @Test public void normalizationBoundsQueriesWithoutSplittingASupplementaryCharacter() {
        String prefix = "a".repeat(255);
        String result = DictionaryRepository.normalize(prefix + character(0x20000) + "果");
        assertEquals(prefix, result);
        assertFalse(Character.isSurrogate(result.charAt(result.length() - 1)));
        String retained = DictionaryRepository.normalize("a".repeat(254) + character(0x20000) + "果");
        assertEquals(256, retained.length());
        assertTrue(DictionaryRepository.containsChinese(retained));
        assertEquals("usb接口", DictionaryRepository.normalize("  USB接口  "));
        assertEquals("can't", DictionaryRepository.normalize("  CAN’T  "));
    }

    @Test public void meaningKeyOnlyRemovesTheSpacesUsedByThePackagedIndex() {
        assertEquals("usb接口", ChineseLookup.meaningKey("usb 接口"));
        assertEquals("苹果%\n'果", ChineseLookup.meaningKey("苹 果%\r\n'果"));
    }

    private static String character(int codePoint) {
        return new String(Character.toChars(codePoint));
    }
}

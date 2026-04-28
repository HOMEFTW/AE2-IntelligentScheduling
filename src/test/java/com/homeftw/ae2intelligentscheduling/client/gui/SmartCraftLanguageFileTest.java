package com.homeftw.ae2intelligentscheduling.client.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

class SmartCraftLanguageFileTest {

    @Test
    void lang_file_uses_real_utf8_text_instead_of_literal_unicode_escapes() throws IOException {
        String lang = new String(
            Files.readAllBytes(Paths.get("src/main/resources/assets/ae2intelligentscheduling/lang/en_US.lang")),
            StandardCharsets.UTF_8);

        assertFalse(lang.matches("(?s).*\\\\u[0-9A-Fa-f]{4}.*"));
    }
}

package com.acme.support.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class KnowledgeValidationTest {

    @Test
    void acceptsSafeNamesOfAllowedTypes() {
        assertThat(KnowledgeService.validateName("acme-support-policy.md")).isEqualTo("acme-support-policy.md");
        assertThat(KnowledgeService.validateName("Returns policy (v2).txt")).isEqualTo("Returns policy (v2).txt");
        assertThat(KnowledgeService.mediaTypeFor("guide.pdf")).isEqualTo("application/pdf");
    }

    @Test
    void rejectsPathTraversalAndSeparators() {
        for (String bad : new String[] {"../secret.md", "..\\secret.md", "dir/policy.md", "dir\\policy.md", "a..b.txt"}) {
            assertThatThrownBy(() -> KnowledgeService.validateName(bad)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsUnsupportedExtensionsAndHiddenNames() {
        for (String bad : new String[] {"run.exe", "page.html", "macro.docm", "noextension", ".hidden.md"}) {
            assertThatThrownBy(() -> KnowledgeService.validateName(bad)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsOversizedFiles() {
        byte[] big = new byte[(int) KnowledgeService.MAX_BYTES + 1];
        Arrays.fill(big, (byte) 'a');
        assertThatThrownBy(() -> KnowledgeService.validateContent("policy.txt", big))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("2 MiB");
    }

    @Test
    void checksPdfMagicAndTextEncoding() {
        assertThatThrownBy(() -> KnowledgeService.validateContent("fake.pdf", "<html>".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
        KnowledgeService.validateContent("real.pdf", "%PDF-1.7 minimal".getBytes(StandardCharsets.US_ASCII));
        assertThatThrownBy(() -> KnowledgeService.validateContent("bin.txt", new byte[] {'a', 0, 'b'}))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("NUL");
        assertThatThrownBy(() -> KnowledgeService.validateContent("bad.md", new byte[] {(byte) 0xC3, (byte) 0x28}))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("UTF-8");
    }

    @Test
    void checksumIsSha256Hex() {
        assertThat(KnowledgeService.sha256Hex("abc".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}

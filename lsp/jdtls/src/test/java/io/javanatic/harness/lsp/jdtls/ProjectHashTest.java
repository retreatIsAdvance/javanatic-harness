package io.javanatic.harness.lsp.jdtls;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ProjectHash 项目输入清单 hash（pom.xml + src/**&#47;*.java 字节合成）：
 * 稳定、对输入敏感、对清单外文件不敏感；64 位小写 hex（锚值形状）。
 */
class ProjectHashTest {

    @TempDir
    Path temp;

    private Path writeJava(String relative, String content) throws IOException {
        Path path = temp.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }

    @Test
    void isStableAcrossCalls() throws IOException {
        writeJava("src/main/java/probe/App.java", "package probe;\nclass App { }\n");
        Files.writeString(temp.resolve("pom.xml"), "<project/>");
        assertThat(ProjectHash.of(temp)).isEqualTo(ProjectHash.of(temp));
    }

    @Test
    void changesWhenSourceChanges() throws IOException {
        Path source = writeJava("src/main/java/probe/App.java", "package probe;\nclass App { }\n");
        Files.writeString(temp.resolve("pom.xml"), "<project/>");
        String before = ProjectHash.of(temp);
        Files.writeString(source, "package probe;\nclass App { int field; }\n");
        assertThat(ProjectHash.of(temp)).isNotEqualTo(before);
    }

    @Test
    void changesWhenPomChanges() throws IOException {
        writeJava("src/main/java/probe/App.java", "package probe;\nclass App { }\n");
        Path pom = Files.writeString(temp.resolve("pom.xml"), "<project/>");
        String before = ProjectHash.of(temp);
        Files.writeString(pom, "<project><version>2</version></project>");
        assertThat(ProjectHash.of(temp)).isNotEqualTo(before);
    }

    @Test
    void ignoresFilesOutsideTheManifest() throws IOException {
        writeJava("src/main/java/probe/App.java", "package probe;\nclass App { }\n");
        Files.writeString(temp.resolve("pom.xml"), "<project/>");
        String before = ProjectHash.of(temp);
        Files.writeString(temp.resolve("README.md"), "changed");
        Files.writeString(temp.resolve("src/notes.txt"), "changed");
        Files.writeString(Files.createDirectories(temp.resolve("target/classes/probe"))
            .resolve("App.class"), "changed");
        assertThat(ProjectHash.of(temp)).isEqualTo(before);
    }

    @Test
    void emptyProjectHasAStableValidHash() throws IOException {
        String hash = ProjectHash.of(temp);
        assertThat(hash).matches("[0-9a-f]{64}");
        assertThat(ProjectHash.of(temp)).isEqualTo(hash);
    }

    @Test
    void missingRootIsAnIoFailure() {
        assertThatThrownBy(() -> ProjectHash.of(temp.resolve("no-such-project")))
            .isInstanceOf(IOException.class);
    }
}

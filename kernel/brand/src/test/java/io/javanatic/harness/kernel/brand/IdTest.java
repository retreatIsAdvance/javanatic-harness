package io.javanatic.harness.kernel.brand;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Id 的构造校验与值语义（phantom type 的运行时面）。 */
class IdTest {

    /** 测试用品牌标记。 */
    interface SessionBrand extends Id.Brand {}

    @Test
    void rejectsNullAndEmpty() {
        assertThatThrownBy(() -> new Id<SessionBrand>(null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Id<SessionBrand>(""))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void valueSemantics() {
        assertThat(new Id<SessionBrand>("sid-1")).isEqualTo(new Id<SessionBrand>("sid-1"));
        assertThat(new Id<SessionBrand>("sid-1")).isNotEqualTo(new Id<SessionBrand>("sid-2"));
        assertThat(new Id<SessionBrand>("sid-1")).hasToString("sid-1");
    }
}

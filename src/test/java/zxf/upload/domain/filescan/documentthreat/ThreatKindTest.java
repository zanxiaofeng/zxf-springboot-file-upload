package zxf.upload.domain.filescan.documentthreat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ThreatKind 处置语义")
class ThreatKindTest {

    @Test
    @DisplayName("仅 MACRO 可在 FLAG 策略下放行打标")
    void onlyMacroCanBeFlagged() {
        assertThat(ThreatKind.MACRO.canBeFlagged()).isTrue();
        assertThat(ThreatKind.ACTIVE_X.canBeFlagged()).isFalse();
        assertThat(ThreatKind.PDF_ACTION.canBeFlagged()).isFalse();
    }
}

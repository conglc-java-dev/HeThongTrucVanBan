package com.TrucVanban.registry.validator;

import com.TrucVanban.registry.enums.OrganizationStatus;
import com.TrucVanban.shared.exception.BusinessLogicException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test cho OrganizationStateTransitionValidator.
 *
 * Đây là State Machine validator - class quan trọng nhất kiểm soát
 * toàn bộ vòng đời trạng thái của Organization:
 *
 *   PENDING_APPROVAL → ACTIVE       (phê duyệt)
 *   PENDING_APPROVAL → REJECTED     (từ chối, bắt buộc có lý do)
 *   ACTIVE           → SUSPENDED    (đình chỉ, bắt buộc có lý do)
 *   SUSPENDED        → ACTIVE       (kích hoạt lại)
 *   REJECTED         → *            (không thể chuyển sang bất kỳ trạng thái nào)
 *
 * Không cần @ExtendWith(MockitoExtension.class) vì class này không có dependency nào,
 * chỉ chứa logic thuần túy → dùng new trực tiếp.
 */
class OrganizationStateTransitionValidatorTest {

    // Dùng new trực tiếp vì validator này không có dependency
    private final OrganizationStateTransitionValidator validator = new OrganizationStateTransitionValidator();

    private static final String ORG_CODE = "AGENCY-001";

    // =============================================================
    // LUỒNG HỢP LỆ (Happy Paths)
    // =============================================================

    @Test
    @DisplayName("PENDING_APPROVAL → ACTIVE: Hợp lệ, không cần lý do")
    void validate_PendingToActive_ShouldPass() {
        // ARRANGE
        OrganizationStatus current = OrganizationStatus.PENDING_APPROVAL;
        OrganizationStatus target  = OrganizationStatus.ACTIVE;

        // ACT & ASSERT
        // assertThatCode(...).doesNotThrowAnyException() = kiểm tra hàm chạy không ném exception
        assertThatCode(() -> validator.validate(ORG_CODE, current, target, null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("PENDING_APPROVAL → REJECTED: Hợp lệ khi có lý do")
    void validate_PendingToRejected_WithReason_ShouldPass() {
        // ARRANGE
        OrganizationStatus current = OrganizationStatus.PENDING_APPROVAL;
        OrganizationStatus target  = OrganizationStatus.REJECTED;

        // ACT & ASSERT
        assertThatCode(() -> validator.validate(ORG_CODE, current, target, "Hồ sơ không đầy đủ"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("ACTIVE → SUSPENDED: Hợp lệ khi có lý do")
    void validate_ActiveToSuspended_WithReason_ShouldPass() {
        // ARRANGE
        OrganizationStatus current = OrganizationStatus.ACTIVE;
        OrganizationStatus target  = OrganizationStatus.SUSPENDED;

        // ACT & ASSERT
        assertThatCode(() -> validator.validate(ORG_CODE, current, target, "Vi phạm quy định"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("SUSPENDED → ACTIVE: Hợp lệ, không cần lý do")
    void validate_SuspendedToActive_ShouldPass() {
        // ARRANGE
        OrganizationStatus current = OrganizationStatus.SUSPENDED;
        OrganizationStatus target  = OrganizationStatus.ACTIVE;

        // ACT & ASSERT
        assertThatCode(() -> validator.validate(ORG_CODE, current, target, null))
                .doesNotThrowAnyException();
    }

    // =============================================================
    // LUỒNG CHUYỂN TRẠNG THÁI KHÔNG HỢP LỆ (Invalid Transitions)
    // =============================================================

    @Test
    @DisplayName("ACTIVE → REJECTED: Không hợp lệ, ném BusinessLogicException")
    void validate_ActiveToRejected_ShouldThrow() {
        // ARRANGE
        OrganizationStatus current = OrganizationStatus.ACTIVE;
        OrganizationStatus target  = OrganizationStatus.REJECTED;

        // ACT & ASSERT
        // assertThatThrownBy = kiểm tra hàm phải ném đúng loại exception và đúng message
        assertThatThrownBy(() -> validator.validate(ORG_CODE, current, target, "Lý do"))
                .isInstanceOf(BusinessLogicException.class)
                .hasMessageContaining("ACTIVE")
                .hasMessageContaining("REJECTED");
    }

    @Test
    @DisplayName("REJECTED → ACTIVE: Không thể chuyển từ REJECTED sang bất kỳ trạng thái nào")
    void validate_RejectedToActive_ShouldThrow() {
        // ARRANGE
        OrganizationStatus current = OrganizationStatus.REJECTED;
        OrganizationStatus target  = OrganizationStatus.ACTIVE;

        // ACT & ASSERT
        assertThatThrownBy(() -> validator.validate(ORG_CODE, current, target, null))
                .isInstanceOf(BusinessLogicException.class)
                .hasMessageContaining("REJECTED")
                .hasMessageContaining("ACTIVE");
    }

    @Test
    @DisplayName("SUSPENDED → REJECTED: Không hợp lệ")
    void validate_SuspendedToRejected_ShouldThrow() {
        // ARRANGE
        OrganizationStatus current = OrganizationStatus.SUSPENDED;
        OrganizationStatus target  = OrganizationStatus.REJECTED;

        // ACT & ASSERT
        assertThatThrownBy(() -> validator.validate(ORG_CODE, current, target, "Lý do"))
                .isInstanceOf(BusinessLogicException.class);
    }

    // =============================================================
    // LUỒNG THIẾU LÝ DO (Missing Reason Validation)
    // =============================================================

    @Test
    @DisplayName("PENDING_APPROVAL → REJECTED: Ném exception khi thiếu lý do (null)")
    void validate_PendingToRejected_WithNullReason_ShouldThrow() {
        // ARRANGE
        OrganizationStatus current = OrganizationStatus.PENDING_APPROVAL;
        OrganizationStatus target  = OrganizationStatus.REJECTED;

        // ACT & ASSERT
        // REJECTED bắt buộc phải có lý do, truyền null → phải ném exception
        assertThatThrownBy(() -> validator.validate(ORG_CODE, current, target, null))
                .isInstanceOf(BusinessLogicException.class)
                .hasMessageContaining("Lý do là bắt buộc");
    }

    @Test
    @DisplayName("PENDING_APPROVAL → REJECTED: Ném exception khi lý do là chuỗi rỗng")
    void validate_PendingToRejected_WithBlankReason_ShouldThrow() {
        // ARRANGE
        OrganizationStatus current = OrganizationStatus.PENDING_APPROVAL;
        OrganizationStatus target  = OrganizationStatus.REJECTED;

        // ACT & ASSERT
        assertThatThrownBy(() -> validator.validate(ORG_CODE, current, target, "   "))
                .isInstanceOf(BusinessLogicException.class)
                .hasMessageContaining("Lý do là bắt buộc");
    }

    @Test
    @DisplayName("ACTIVE → SUSPENDED: Ném exception khi thiếu lý do (null)")
    void validate_ActiveToSuspended_WithNullReason_ShouldThrow() {
        // ARRANGE
        OrganizationStatus current = OrganizationStatus.ACTIVE;
        OrganizationStatus target  = OrganizationStatus.SUSPENDED;

        // ACT & ASSERT
        assertThatThrownBy(() -> validator.validate(ORG_CODE, current, target, null))
                .isInstanceOf(BusinessLogicException.class)
                .hasMessageContaining("Lý do là bắt buộc");
    }
}

package com.k_place.common.presentation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.k_place.common.exception.BusinessException;
import com.k_place.common.exception.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GlobalExceptionHandler 가 예외를 규약된 상태 코드 + ErrorResponse 포맷으로 변환하는지 검증한다.
 * 실제 도메인 Controller 가 아직 없으므로 테스트 전용 Controller 로 각 예외를 유발한다.
 */
// GlobalExceptionHandler 는 @RestControllerAdvice 이므로 @WebMvcTest 가 자동으로 올린다.
// 테스트 전용 Controller 는 컴포넌트 스캔 대상이 아니라서 @Import 로 명시 등록한다.
@WebMvcTest
@Import(GlobalExceptionHandlerTest.TestController.class)
class GlobalExceptionHandlerTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    @DisplayName("BusinessException 은 ErrorCode 에 정의된 상태와 코드로 응답한다")
    void handleBusiness_shouldUseErrorCodeStatus() throws Exception {
        mockMvc.perform(post("/test/business"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("요청한 리소스를 찾을 수 없습니다."))
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }

    @Test
    @DisplayName("본문 검증 실패는 400 과 필드별 사유를 함께 반환한다")
    void handleValidation_shouldReturn400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"rating\":9}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.length()").value(2));
    }

    @Test
    @DisplayName("깨진 JSON 본문은 400 MALFORMED_BODY 로 응답하고 내부 메시지를 노출하지 않는다")
    void handleNotReadable_shouldReturn400() throws Exception {
        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ broken json "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_BODY"))
                .andExpect(jsonPath("$.message").value("요청 본문을 해석할 수 없습니다."));
    }

    @Test
    @DisplayName("필수 파라미터 누락은 400 MISSING_PARAMETER 로 응답한다")
    void handleMissingParameter_shouldReturn400() throws Exception {
        mockMvc.perform(get("/test/param"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_PARAMETER"));
    }

    @Test
    @DisplayName("파라미터 타입 불일치는 400 TYPE_MISMATCH 로 응답한다")
    void handleTypeMismatch_shouldReturn400() throws Exception {
        mockMvc.perform(get("/test/param").param("size", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TYPE_MISMATCH"));
    }

    @Test
    @DisplayName("지원하지 않는 HTTP 메서드는 405 로 응답한다")
    void handleMethodNotSupported_shouldReturn405() throws Exception {
        mockMvc.perform(get("/test/business"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("도메인 상태 전이 실패(IllegalStateException)는 409 로 응답한다")
    void handleIllegalState_shouldReturn409() throws Exception {
        mockMvc.perform(post("/test/illegal-state"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    @DisplayName("예상치 못한 예외는 500 이며 내부 메시지를 노출하지 않는다")
    void handleUnexpected_shouldReturn500AndMaskMessage() throws Exception {
        mockMvc.perform(post("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("서버 내부 오류가 발생했습니다."));
    }

    @RestController
    @RequestMapping("/test")
    static class TestController {

        @PostMapping("/business")
        void business() {
            throw new BusinessException(ErrorCode.NOT_FOUND, "place not found: PLC-1");
        }

        @PostMapping("/validate")
        void validate(@Valid @RequestBody TestRequest request) {
        }

        @org.springframework.web.bind.annotation.GetMapping("/param")
        void param(@RequestParam int size) {
        }

        @PostMapping("/illegal-state")
        void illegalState() {
            throw new IllegalStateException("이미 삭제된 리뷰입니다");
        }

        @PostMapping("/boom")
        void boom() {
            throw new RuntimeException("DB connection string: jdbc://secret");
        }
    }

    record TestRequest(@NotBlank String name, @Min(1) @Max(5) int rating) {}
}

package com.k_place.common.presentation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.k_place.common.exception.ErrorCode;
import java.util.List;

/**
 * 모든 에러 응답의 단일 포맷.
 *
 * <ul>
 *   <li>{@code code} — 클라이언트가 분기하는 식별자. {@link ErrorCode} 이름이 그대로 쓰인다.</li>
 *   <li>{@code message} — 사람이 읽는 메시지. 스택트레이스·SQL·내부 클래스명을 담지 않는다.</li>
 *   <li>{@code fieldErrors} — bean validation 실패일 때만 채운다. 그 외에는 직렬화에서 생략된다.</li>
 * </ul>
 *
 * <pre>{@code
 * {
 *   "code": "VALIDATION_FAILED",
 *   "message": "입력값 검증에 실패했습니다.",
 *   "fieldErrors": [ { "field": "rating", "message": "1 이상이어야 합니다" } ]
 * }
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String code, String message, List<FieldError> fieldErrors) {

    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse(errorCode.name(), errorCode.message(), null);
    }

    public static ErrorResponse of(ErrorCode errorCode, String message) {
        return new ErrorResponse(errorCode.name(), message, null);
    }

    public static ErrorResponse withFields(ErrorCode errorCode, List<FieldError> fieldErrors) {
        return new ErrorResponse(errorCode.name(), errorCode.message(), fieldErrors);
    }

    public record FieldError(String field, String message) {}
}

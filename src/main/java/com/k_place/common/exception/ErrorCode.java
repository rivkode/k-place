package com.k_place.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 에러 코드의 단일 출처.
 *
 * <p>{@code code} 는 클라이언트가 분기하는 값이므로 한번 공개되면 바꾸지 않는다.
 * {@code message} 는 사람이 읽는 기본 문구이며, 내부 구현(스택트레이스, SQL, 클래스명)을 담지 않는다.
 *
 * <p>도메인별 코드는 아래 주석 구획에 이어서 추가한다.
 */
public enum ErrorCode {

    // ── 공통 (400) ──────────────────────────────────────────────
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "입력값 검증에 실패했습니다."),
    MALFORMED_BODY(HttpStatus.BAD_REQUEST, "요청 본문을 해석할 수 없습니다."),
    MISSING_PARAMETER(HttpStatus.BAD_REQUEST, "필수 파라미터가 없습니다."),
    MISSING_HEADER(HttpStatus.BAD_REQUEST, "필수 헤더가 없습니다."),
    TYPE_MISMATCH(HttpStatus.BAD_REQUEST, "파라미터 타입이 올바르지 않습니다."),

    // ── 공통 (4xx) ──────────────────────────────────────────────
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 HTTP 메서드입니다."),
    CONFLICT(HttpStatus.CONFLICT, "현재 상태에서는 처리할 수 없습니다."),
    UNPROCESSABLE(HttpStatus.UNPROCESSABLE_ENTITY, "비즈니스 규칙을 위반했습니다."),

    // ── 공통 (5xx) ──────────────────────────────────────────────
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다.");

    // ── 도메인별 코드는 여기에 추가한다 ─────────────────────────
    // 예) PLACE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 장소를 찾을 수 없습니다."),

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus status() {
        return status;
    }

    public String message() {
        return message;
    }
}

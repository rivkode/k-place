package com.k_place.common.exception;

/**
 * 비즈니스 규칙 위반을 나타내는 예외의 최상위 타입.
 *
 * <p>도메인 예외는 이 클래스를 상속하고 자신의 {@link ErrorCode} 를 넘긴다.
 * {@code GlobalExceptionHandler} 가 ErrorCode 의 HTTP 상태로 그대로 응답하므로,
 * 새 예외를 추가할 때 핸들러를 고칠 필요가 없다.
 *
 * <pre>{@code
 * public class PlaceNotFoundException extends BusinessException {
 *     public PlaceNotFoundException(PlaceId id) {
 *         super(ErrorCode.NOT_FOUND, "place not found: " + id.value());
 *     }
 * }
 * }</pre>
 *
 * <p>생성자의 {@code detail} 은 <b>로그용</b>이다. 클라이언트에게는 ErrorCode 의 메시지가 나간다.
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.message());
    }

    public BusinessException(ErrorCode errorCode, String detail) {
        super(detail);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}

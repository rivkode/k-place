package com.k_place.common.presentation;

import com.k_place.common.exception.BusinessException;
import com.k_place.common.exception.ErrorCode;
import com.k_place.common.presentation.ErrorResponse.FieldError;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 전역 예외 핸들러. 모든 에러 응답은 {@link ErrorResponse} 단일 포맷으로 나간다.
 *
 * <p>매핑:
 * <ul>
 *   <li>{@link BusinessException} → 해당 {@link ErrorCode} 의 상태 (도메인 예외의 기본 통로)</li>
 *   <li>MethodArgumentNotValidException → 400 VALIDATION_FAILED (필드별 사유 포함)</li>
 *   <li>ConstraintViolationException → 400 VALIDATION_FAILED (@Validated 파라미터 검증)</li>
 *   <li>MissingServletRequestParameter / MissingRequestHeader → 400</li>
 *   <li>HttpMessageNotReadable / MethodArgumentTypeMismatch → 400</li>
 *   <li>HttpRequestMethodNotSupported → 405, NoResourceFound → 404</li>
 *   <li>IllegalArgumentException → 400, IllegalStateException → 409 (도메인 불변식/상태 전이)</li>
 *   <li>그 외 → 500 (메시지 마스킹 + ERROR 로그)</li>
 * </ul>
 *
 * <p>새 도메인 예외는 {@link BusinessException} 을 상속하면 이 클래스를 고치지 않아도 매핑된다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException ex) {
        ErrorCode code = ex.errorCode();
        log.info("business exception: code={}, detail={}", code.name(), ex.getMessage());
        return ResponseEntity.status(code.status()).body(ErrorResponse.of(code));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        List<FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldError(fe.getField(), fe.getDefaultMessage()))
                .toList();
        return respond(ErrorCode.VALIDATION_FAILED,
                ErrorResponse.withFields(ErrorCode.VALIDATION_FAILED, fieldErrors));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        List<FieldError> fieldErrors = ex.getConstraintViolations().stream()
                .map(v -> new FieldError(v.getPropertyPath().toString(), v.getMessage()))
                .toList();
        return respond(ErrorCode.VALIDATION_FAILED,
                ErrorResponse.withFields(ErrorCode.VALIDATION_FAILED, fieldErrors));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParameter(MissingServletRequestParameterException ex) {
        return respond(ErrorCode.MISSING_PARAMETER,
                ErrorResponse.of(ErrorCode.MISSING_PARAMETER,
                        "필수 파라미터가 없습니다: " + ex.getParameterName()));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException ex) {
        return respond(ErrorCode.MISSING_HEADER,
                ErrorResponse.of(ErrorCode.MISSING_HEADER,
                        "필수 헤더가 없습니다: " + ex.getHeaderName()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException ex) {
        // 원문 메시지에 파싱 대상 클래스명이 섞여 나오므로 그대로 노출하지 않는다.
        log.debug("malformed request body", ex);
        return respond(ErrorCode.MALFORMED_BODY, ErrorResponse.of(ErrorCode.MALFORMED_BODY));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return respond(ErrorCode.TYPE_MISMATCH,
                ErrorResponse.of(ErrorCode.TYPE_MISMATCH,
                        "파라미터 타입이 올바르지 않습니다: " + ex.getName()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return respond(ErrorCode.METHOD_NOT_ALLOWED, ErrorResponse.of(ErrorCode.METHOD_NOT_ALLOWED));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex) {
        return respond(ErrorCode.NOT_FOUND, ErrorResponse.of(ErrorCode.NOT_FOUND));
    }

    /** 도메인 VO 생성자의 인자 검증 실패 등. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        log.info("illegal argument: {}", ex.getMessage());
        return respond(ErrorCode.INVALID_REQUEST, ErrorResponse.of(ErrorCode.INVALID_REQUEST));
    }

    /** 도메인 상태 전이 실패 등. */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException ex) {
        log.info("illegal state: {}", ex.getMessage());
        return respond(ErrorCode.CONFLICT, ErrorResponse.of(ErrorCode.CONFLICT));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("unhandled exception", ex);
        return respond(ErrorCode.INTERNAL_ERROR, ErrorResponse.of(ErrorCode.INTERNAL_ERROR));
    }

    private ResponseEntity<ErrorResponse> respond(ErrorCode code, ErrorResponse body) {
        return ResponseEntity.status(code.status()).body(body);
    }
}

package com.revenugenereate.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiRequestException.class)
    ProblemDetail handleApiRequest(ApiRequestException exception, HttpServletRequest request) {
        ProblemDetail problem = problem(
                exception.status(), exception.getMessage(), exception.code(), request);
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleInvalidArguments(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        Map<String, String> errors = new TreeMap<>();
        for (FieldError error : exception.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        ProblemDetail problem = problem(
                HttpStatus.BAD_REQUEST, "Request validation failed", "validation-error", request);
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler({ConstraintViolationException.class, HandlerMethodValidationException.class})
    ProblemDetail handleConstraintViolation(Exception exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "Request validation failed", "validation-error", request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail handleMalformedRequest(HttpServletRequest request) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "Request body is missing or malformed",
                "malformed-request",
                request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ProblemDetail handleUploadTooLarge(HttpServletRequest request) {
        return problem(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "Uploaded file exceeds the configured size limit",
                "payload-too-large",
                request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ProblemDetail handleNotFound(HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "The requested resource was not found", "not-found", request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ProblemDetail handleMethodNotAllowed(HttpServletRequest request) {
        return problem(
                HttpStatus.METHOD_NOT_ALLOWED,
                "The HTTP method is not supported for this resource",
                "method-not-allowed",
                request);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpectedError(Exception exception, HttpServletRequest request) {
        logger.error("Unhandled API exception for {} {}", request.getMethod(), request.getRequestURI(), exception);
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred",
                "internal-error",
                request);
    }

    private ProblemDetail problem(
            HttpStatus status, String detail, String type, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setType(URI.create("https://trend-to-revenue.local/problems/" + type));
        problem.setInstance(URI.create(request.getRequestURI()));
        return problem;
    }
}

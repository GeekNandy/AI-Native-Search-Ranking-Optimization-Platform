package com.alpas.ainativesearchrankingoptimizationplatform.api;

import com.alpas.ainativesearchrankingoptimizationplatform.platform.PlatformException;
import java.util.Comparator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    public record Violation(String field, String message) { }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status,
                "One or more fields are invalid.");
        problem.setTitle("Invalid request");
        problem.setProperty("errors", exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new Violation(error.getField(), error.getDefaultMessage()))
                .distinct().sorted(Comparator.comparing(Violation::field)
                        .thenComparing(Violation::message)).toList());
        return response(problem, headers);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        return response(ProblemDetail.forStatusAndDetail(status,
                "Request body must be valid JSON matching the endpoint schema."), headers);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return response(ProblemDetail.forStatusAndDetail(status,
                "A request parameter has an invalid format."), headers);
    }

    @ExceptionHandler({DataAccessResourceFailureException.class,
            TransientDataAccessResourceException.class, QueryTimeoutException.class})
    ResponseEntity<Object> databaseUnavailable(Exception exception) {
        LOG.warn("Database request failed: {}", exception.getClass().getSimpleName());
        return response(ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Database access is temporarily unavailable. The operation could not be confirmed."),
                new HttpHeaders());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpectedFailure(Exception exception) {
        LOG.error("Unexpected request failure", exception);
        return response(ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred."), new HttpHeaders());
    }

    @ExceptionHandler(PlatformException.class)
    ResponseEntity<Object> platformFailure(PlatformException exception) {
        return response(ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(exception.status()),
                exception.getMessage()), new HttpHeaders());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Object> invalidArgument(IllegalArgumentException exception) {
        return response(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                exception.getMessage()), new HttpHeaders());
    }

    private static ResponseEntity<Object> response(ProblemDetail problem, HttpHeaders headers) {
        return ResponseEntity.status(problem.getStatus()).headers(headers)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }
}

package com.company.migrator.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String,Object> handle(Exception ex) {
        Throwable cursor = ex;
        while (cursor.getCause() != null) cursor = cursor.getCause();
        String message = cursor.getMessage() == null ? cursor.getClass().getSimpleName() : cursor.getMessage();
        return Map.of("success", false, "message", message);
    }
}

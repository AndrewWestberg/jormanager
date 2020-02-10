package com.swiftmako.jormanager.ui

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseBody


@ControllerAdvice
class BaseErrorHandles {

    @ResponseBody
    @ExceptionHandler(value = [ValidationException::class])
    fun handleException(exception: ValidationException): ResponseEntity<String?> {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(exception.message)
    }
}
package com.biliparse.api;

/**
 * API 调用异常，携带用户可读的错误信息
 */
public class ApiException extends RuntimeException {

    public ApiException(String message) {
        super(message);
    }

    public ApiException(String message, Throwable cause) {
        super(message, cause);
    }
}

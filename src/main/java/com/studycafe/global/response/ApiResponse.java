package com.studycafe.global.response;

import com.studycafe.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class ApiResponse<T> {

    private final boolean success;
    private final String code;     // 성공: "SUCCESS", 실패: "MEMBER_NOT_FOUND" 등 ErrorCode 이름
    private final T data;
    private final String message;

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, "SUCCESS", data, null);
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(true, "SUCCESS", null, null);
    }

    public static ApiResponse<Void> fail(ErrorCode errorCode) {
        return new ApiResponse<>(false, errorCode.name(), null, errorCode.getMessage());
    }

    // 입력 검증 실패처럼 상황별 메시지를 내려줄 때 사용
    public static ApiResponse<Void> fail(ErrorCode errorCode, String message) {
        return new ApiResponse<>(false, errorCode.name(), null, message);
    }
}

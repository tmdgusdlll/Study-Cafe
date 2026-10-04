package com.studycafe.domain.cafe.exception;

import com.studycafe.global.exception.CustomException;
import com.studycafe.global.exception.ErrorCode;

public class CafeException extends CustomException {

    public CafeException(ErrorCode errorCode) {
        super(errorCode);
    }
}

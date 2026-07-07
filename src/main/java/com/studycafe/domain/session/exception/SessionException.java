package com.studycafe.domain.session.exception;

import com.studycafe.global.exception.CustomException;
import com.studycafe.global.exception.ErrorCode;

public class SessionException extends CustomException {

    public SessionException(ErrorCode errorCode) {
        super(errorCode);
    }
}

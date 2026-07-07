package com.studycafe.domain.member.exception;

import com.studycafe.global.exception.CustomException;
import com.studycafe.global.exception.ErrorCode;

public class MemberException extends CustomException {

    public MemberException(ErrorCode errorCode) {
        super(errorCode);
    }
}

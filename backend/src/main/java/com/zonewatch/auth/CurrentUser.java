package com.zonewatch.auth;

import com.zonewatch.common.ApiException;
import com.zonewatch.common.ProblemCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** The authenticated caller, taken from the verified token. */
public record CurrentUser(String username, Role role) {

    public boolean isSupervisor() {
        return role == Role.SUPERVISOR;
    }

    public static CurrentUser get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof CurrentUser user) {
            return user;
        }
        throw new ApiException(ProblemCode.UNAUTHENTICATED, "authentication required");
    }
}

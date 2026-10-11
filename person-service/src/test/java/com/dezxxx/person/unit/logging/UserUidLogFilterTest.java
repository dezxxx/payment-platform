package com.dezxxx.person.unit.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dezxxx.person.logging.UserUidLog;
import com.dezxxx.person.logging.UserUidLogFilter;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@DisplayName("UserUidLog and UserUidLogFilter")
class UserUidLogFilterTest {

    private static final UUID USER_UID = UUID.fromString("6c8ec4d0-6fd5-4db8-bf4d-7bcbdb2f6c02");

    private final UserUidLogFilter filter = new UserUidLogFilter();

    @AfterEach
    void cleanUp() {
        MDC.clear();
    }

    @Test
    @DisplayName("given a user_uid put during the request, when the request ends, then the thread no longer carries it")
    void clearsAfterRequest() throws Exception {
        // given
        String[] seenInside = new String[1];
        MockFilterChain chain = new MockFilterChain(new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse res) {
                UserUidLog.put(USER_UID);
                seenInside[0] = MDC.get(UserUidLog.USER_UID);
            }
        });

        // when
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        // then
        assertThat(seenInside[0]).isEqualTo(USER_UID.toString());
        assertThat(MDC.get(UserUidLog.USER_UID)).isNull();
    }

    @Test
    @DisplayName("given the request fails, when it ends, then user_uid is still removed")
    void clearsAfterFailure() {
        // given
        MockFilterChain chain = new MockFilterChain(new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse res)
                    throws ServletException {
                UserUidLog.put(USER_UID);
                throw new ServletException("boom");
            }
        });

        // when / then
        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain))
                .isInstanceOf(ServletException.class);

        // then
        assertThat(MDC.get(UserUidLog.USER_UID)).isNull();
    }
}

package com.milvus.vector_spring.auth;

import com.milvus.vector_spring.auth.dto.AuthTokenDto;
import com.milvus.vector_spring.auth.dto.UserLoginRequestDto;
import com.milvus.vector_spring.auth.dto.UserLoginResponseDto;
import com.milvus.vector_spring.common.apipayload.status.ErrorStatus;
import com.milvus.vector_spring.common.exception.CustomException;
import com.milvus.vector_spring.config.jwt.JwtTokenProvider;
import com.milvus.vector_spring.user.User;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RequestMapping("/auth")
@RequiredArgsConstructor
@RestController
public class AuthController {

    private final AuthService authService;
    private final JwtTokenProvider jwtTokenProvider;

    @PostMapping("/login")
    @ResponseStatus(HttpStatus.CREATED)
    public UserLoginResponseDto login(
            @RequestBody UserLoginRequestDto userLoginRequestDto,
            HttpServletResponse response
    ) {
<<<<<<< HEAD
        AuthTokenDto result = authService.login(userLoginRequestDto.email(), userLoginRequestDto.password());
        addRefreshTokenCookie(response, result.refreshToken());
        return result.userInfo();
=======
        AuthTokenDto result = authService.login(userLoginRequestDto.getEmail(), userLoginRequestDto.getPassword());
        addRefreshTokenCookie(response, result.getRefreshToken());
        return result.getUserInfo();
>>>>>>> 68da5e4 (fix: lock -> 원자 update)
    }

    @PostMapping("/logout")
    public ResponseEntity<String> logout(
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        String accessToken = resolveAccessToken(request);
        User user = authService.logout(accessToken);
        deleteRefreshTokenCookie(response);
<<<<<<< HEAD
        return ResponseEntity.ok("Logged out: " + user.getEmail());
=======
        return ResponseEntity.ok("로그아웃 완료: " + user.getEmail());
>>>>>>> 68da5e4 (fix: lock -> 원자 update)
    }

    @PostMapping("/reissue")
    public ResponseEntity<UserLoginResponseDto> reissue(
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        String refreshToken = extractRefreshTokenFromCookie(request);
        AuthTokenDto result = authService.reissue(refreshToken);
        addRefreshTokenCookie(response, result.refreshToken());
        return ResponseEntity.ok(result.userInfo());
    }

    @GetMapping("/admin")
    @PreAuthorize("hasAnyRole('ADMIN')")
    public String test() {
        return "Admin access granted.";
    }

    private String resolveAccessToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7).trim();
        }
        throw new CustomException(ErrorStatus.INVALID_ACCESS_TOKEN);
    }

    private void addRefreshTokenCookie(HttpServletResponse response, String refreshToken) {
        Cookie cookie = new Cookie("refresh_token", refreshToken);
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge((int) jwtTokenProvider.getRefreshTokenTtlSeconds());
        cookie.setSecure(false);
        response.addCookie(cookie);
    }

    private void deleteRefreshTokenCookie(HttpServletResponse response) {
        Cookie cookie = new Cookie("refresh_token", null);
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
    }

    private String extractRefreshTokenFromCookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            throw new CustomException(ErrorStatus.EXPIRED_REFRESH_TOKEN);
        }
        for (Cookie cookie : request.getCookies()) {
            if ("refresh_token".equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        throw new CustomException(ErrorStatus.EXPIRED_REFRESH_TOKEN);
    }
}

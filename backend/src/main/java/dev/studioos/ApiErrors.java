package dev.studioos;

import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiErrors {
  @ExceptionHandler(
      org.springframework.web.context.request.async.AsyncRequestNotUsableException.class)
  public void disconnected() {}

  @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
  public ResponseEntity<?> bad(Exception e) {
    return ResponseEntity.badRequest()
        .body(
            Map.of(
                "message",
                e instanceof MethodArgumentNotValidException ? "입력값을 확인하세요" : e.getMessage()));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<?> failure(Exception e) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(Map.of("message", e.getMessage() == null ? "작업을 완료할 수 없습니다" : e.getMessage()));
  }
}

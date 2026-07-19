package com.example.ethics.api;

import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.dao.OptimisticLockingFailureException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String,Object>> status(ResponseStatusException ex){return ResponseEntity.status(ex.getStatusCode()).body(error(code(ex),publicMessage(ex)));}
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String,Object>> validation(){return ResponseEntity.badRequest().body(error("VALIDATION_FAILED","Gönderilen bilgiler doğrulanamadı."));}
    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<Map<String,Object>> optimistic(){return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED).body(error("CASE_VERSION_MISMATCH","Vaka başka bir yetkili tarafından güncellendi."));}
    private static String code(ResponseStatusException ex){String reason=ex.getReason();return reason!=null&&reason.matches("[A-Z_]+")?reason:ex.getStatusCode().toString();}
    private static String publicMessage(ResponseStatusException ex){return ex.getReason()==null?"İşlem tamamlanamadı.":ex.getReason();}
    private static Map<String,Object> error(String code,String message){return Map.of("error",Map.of("code",code,"message",message));}
}

package com.example.ethics.api;

import com.example.ethics.security.SensitiveResponseHeadersFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.dao.OptimisticLockingFailureException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String,Object>> status(ResponseStatusException ex,HttpServletRequest request){return ResponseEntity.status(ex.getStatusCode()).body(error(code(ex),publicMessage(ex),request));}
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String,Object>> validation(HttpServletRequest request){return ResponseEntity.badRequest().body(error("VALIDATION_FAILED","Gönderilen bilgiler doğrulanamadı.",request));}
    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<Map<String,Object>> optimistic(HttpServletRequest request){return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED).body(error("CASE_VERSION_MISMATCH","Vaka başka bir yetkili tarafından güncellendi.",request));}
    private static String code(ResponseStatusException ex){
        String reason=ex.getReason();
        if(reason!=null&&reason.matches("[A-Z_]+")) return reason;
        return switch(ex.getStatusCode().value()){
            case 400 -> "BAD_REQUEST"; case 401 -> "UNAUTHORIZED"; case 403 -> "FORBIDDEN";
            case 404 -> "RESOURCE_UNAVAILABLE"; case 409 -> "CONFLICT";
            case 412 -> "PRECONDITION_FAILED"; case 422 -> "UNPROCESSABLE_ENTITY";
            case 429 -> "RATE_LIMITED"; default -> "REQUEST_FAILED";
        };
    }
    private static String publicMessage(ResponseStatusException ex){return ex.getReason()==null?"İşlem tamamlanamadı.":ex.getReason();}
    private static Map<String,Object> error(String code,String message,HttpServletRequest request){
        Object requestId=request.getAttribute(SensitiveResponseHeadersFilter.REQUEST_ID_ATTRIBUTE);
        return Map.of("error",Map.of("code",code,"message",message,"requestId",requestId==null?"unavailable":requestId.toString()));
    }
}

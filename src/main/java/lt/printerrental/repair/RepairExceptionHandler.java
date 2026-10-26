package lt.printerrental.repair;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class RepairExceptionHandler {
  @ExceptionHandler(RepairNotFoundException.class)
  ProblemDetail missing(RepairNotFoundException error) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, error.getMessage());
  }

  @ExceptionHandler(RepairConflictException.class)
  ProblemDetail conflict(RepairConflictException error) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, error.getMessage());
  }
}

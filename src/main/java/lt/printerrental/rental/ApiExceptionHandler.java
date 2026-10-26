package lt.printerrental.rental;

import java.util.List;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {
  @ExceptionHandler(ContractNotFoundException.class)
  ProblemDetail contractNotFound(ContractNotFoundException error) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, error.getMessage());
  }

  @ExceptionHandler(PrinterNotFoundException.class)
  ProblemDetail printerNotFound(PrinterNotFoundException error) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, error.getMessage());
  }

  @ExceptionHandler(RentalNotFoundException.class)
  ProblemDetail notFound(RentalNotFoundException error) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, error.getMessage());
  }

  @ExceptionHandler(RentalConflictException.class)
  ProblemDetail conflict(RentalConflictException error) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, error.getMessage());
  }

  @ExceptionHandler(OptimisticLockingFailureException.class)
  ProblemDetail concurrentChange() {
    return ProblemDetail.forStatusAndDetail(
        HttpStatus.CONFLICT, "Request changed; reload it and retry");
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ProblemDetail validation(MethodArgumentNotValidException error) {
    var detail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
    List<FieldError> errors =
        error.getBindingResult().getFieldErrors().stream()
            .map(e -> new FieldError(e.getField(), e.getDefaultMessage()))
            .toList();
    detail.setProperty("errors", errors);
    return detail;
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ProblemDetail badArgument(IllegalArgumentException error) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, error.getMessage());
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class,
    HandlerMethodValidationException.class
  })
  ProblemDetail malformedRequest() {
    return ProblemDetail.forStatusAndDetail(
        HttpStatus.BAD_REQUEST, "Invalid JSON, parameter, UUID, date, status or pagination value");
  }

  public record FieldError(String field, String message) {}
}

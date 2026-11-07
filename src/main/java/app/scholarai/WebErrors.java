package app.scholarai;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

@ControllerAdvice
public class WebErrors {
  @ExceptionHandler(ResponseStatusException.class)
  String request(ResponseStatusException ex, Model model, HttpServletResponse response) {
    response.setStatus(ex.getStatusCode().value());
    model.addAttribute("errorTitle", "Unable to complete this request");
    model.addAttribute("detail", ex.getReason());
    return "error";
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  String upload(Model model, HttpServletResponse response) {
    response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
    model.addAttribute("errorTitle", "File too large");
    model.addAttribute("detail", "Upload a file no larger than 4 MB.");
    return "error";
  }
}

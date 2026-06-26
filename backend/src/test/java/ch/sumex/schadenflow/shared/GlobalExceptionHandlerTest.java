package ch.sumex.schadenflow.shared;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    @RestController
    static class ThrowingController {
        @GetMapping("/boom/notfound")
        String notFound() { throw new DomainException.NotFoundError("claim 1 not found"); }

        @GetMapping("/boom/illegal")
        String illegal() { throw new DomainException.IllegalTransitionError("EINGEREICHT -> AUSBEZAHLT not allowed"); }

        @GetMapping("/boom/forbidden")
        String forbidden() { throw new DomainException.ForbiddenError("role may not perform this"); }

        @GetMapping("/boom/validation")
        String validation() { throw new DomainException.ValidationError("reason is required"); }
    }

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new ThrowingController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void notFoundMapsTo404() throws Exception {
        mockMvc.perform(get("/boom/notfound").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("claim 1 not found"));
    }

    @Test
    void illegalTransitionMapsTo409() throws Exception {
        mockMvc.perform(get("/boom/illegal").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ILLEGAL_TRANSITION"));
    }

    @Test
    void forbiddenMapsTo403() throws Exception {
        mockMvc.perform(get("/boom/forbidden").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void domainValidationMapsTo422() throws Exception {
        mockMvc.perform(get("/boom/validation").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }
}

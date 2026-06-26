package ch.sumex.schadenflow.shared;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(boolean ok, T data, ApiResponse.ErrorBody error) {

    public record ErrorBody(String code, String message) {}

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static ApiResponse<Object> error(String code, String message) {
        return new ApiResponse<>(false, null, new ErrorBody(code, message));
    }
}

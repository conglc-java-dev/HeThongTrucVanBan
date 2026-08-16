package com.TrucVanban.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class LoginRequest {
    @NotBlank(message = "Username is required")
    @Size(max = 50, message = "Username không hợp lệ")
    private String username;
    
    @NotBlank(message = "Password is required")
    @Size(max = 50, message = "Password không hợp lệ")
    private String password;
}

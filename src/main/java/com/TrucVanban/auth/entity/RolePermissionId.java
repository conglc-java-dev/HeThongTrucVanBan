package com.TrucVanban.auth.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RolePermissionId implements Serializable {
    private Long roleId;
    private Long permissionId;
}

package com.TrucVanban.auth.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "role_permissions")
@IdClass(RolePermissionId.class)
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Builder
public class RolePermission {

    @Id
    @Column(name = "role_id", nullable = false)
    private Long roleId;

    @Id
    @Column(name = "permission_id", nullable = false)
    private Long permissionId;

}

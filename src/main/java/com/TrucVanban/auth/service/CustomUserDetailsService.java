package com.TrucVanban.auth.service;

import com.TrucVanban.auth.entity.Permission;
import com.TrucVanban.auth.entity.Role;
import com.TrucVanban.auth.entity.RolePermission;
import com.TrucVanban.auth.entity.User;
import com.TrucVanban.auth.entity.UserRole;
import com.TrucVanban.auth.enums.UserStatus;
import com.TrucVanban.auth.repository.PermissionRepository;
import com.TrucVanban.auth.repository.RolePermissionRepository;
import com.TrucVanban.auth.repository.RoleRepository;
import com.TrucVanban.auth.repository.UserRepository;
import com.TrucVanban.auth.repository.UserRoleRepository;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final PermissionRepository permissionRepository;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("Tên đăng nhập không tồn tại"));

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new DisabledException("Tài khoản đã bị khóa");
        }

        return buildCustomUserDetails(user);
    }

    public CustomUserDetails loadUserById(Long id) throws UsernameNotFoundException {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new UsernameNotFoundException("Không tìm thấy người dùng"));

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new DisabledException("Tài khoản đã bị khóa");
        }

        return buildCustomUserDetails(user);
    }

    private CustomUserDetails buildCustomUserDetails(User user) {

        List<Long> roleIds = userRoleRepository.findByUserId(user.getId()).stream()
                .map(UserRole::getRoleId)
                .collect(Collectors.toList());

        List<String> roles = roleRepository.findAllById(roleIds).stream()
                .map(Role::getCode)
                .collect(Collectors.toList());

        List<Long> permissionIds = roleIds.stream()
                .flatMap(roleId -> rolePermissionRepository.findByRoleId(roleId).stream())
                .map(RolePermission::getPermissionId)
                .distinct()
                .collect(Collectors.toList());

        List<String> permissions = permissionRepository.findAllById(permissionIds).stream()
                .map(Permission::getCode)
                .collect(Collectors.toList());

        return new CustomUserDetails(user, roles, permissions);
    }

    @Getter
    public static class CustomUserDetails implements UserDetails {
        private final User user;
        private final List<String> roles;
        private final List<String> permissions;

        public CustomUserDetails(User user, List<String> roles, List<String> permissions) {
            this.user = user;
            this.roles = roles;
            this.permissions = permissions;
        }

        @Override
        public Collection<? extends GrantedAuthority> getAuthorities() {
            Stream<String> rolesStream = roles != null ? roles.stream() : Stream.empty();
            Stream<String> permissionsStream = permissions != null ? permissions.stream() : Stream.empty();
            return Stream.concat(rolesStream, permissionsStream)
                    .map(SimpleGrantedAuthority::new)
                    .collect(Collectors.toList());
        }

        @Override
        public String getPassword() {
            return user.getPassword();
        }

        @Override
        public String getUsername() {
            return user.getUsername();
        }

        @Override
        public boolean isAccountNonExpired() {
            return true;
        }

        @Override
        public boolean isAccountNonLocked() {
            return user.getStatus() == UserStatus.ACTIVE;
        }

        @Override
        public boolean isCredentialsNonExpired() {
            return true;
        }

        @Override
        public boolean isEnabled() {
            return user.getStatus() == UserStatus.ACTIVE;
        }
    }
}

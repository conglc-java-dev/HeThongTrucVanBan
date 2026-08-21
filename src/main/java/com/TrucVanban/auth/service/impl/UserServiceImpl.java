package com.TrucVanban.auth.service.impl;

import com.TrucVanban.auth.dto.request.CreateUserRequest;
import com.TrucVanban.auth.dto.request.UpdateUserRequest;
import com.TrucVanban.auth.dto.response.UserResponse;
import com.TrucVanban.auth.entity.Role;
import com.TrucVanban.auth.entity.User;
import com.TrucVanban.auth.entity.UserRole;
import com.TrucVanban.auth.mapper.UserMapper;
import com.TrucVanban.auth.repository.RoleRepository;
import com.TrucVanban.auth.repository.UserRepository;
import com.TrucVanban.auth.repository.UserRoleRepository;
import com.TrucVanban.auth.service.UserService;
import com.TrucVanban.shared.exception.DuplicateResourceException;
import com.TrucVanban.shared.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public UserResponse createUser(CreateUserRequest request) {
        if (userRepository.findByUsername(request.getUsername()).isPresent()) {
            throw new DuplicateResourceException("Tên đăng nhập đã tồn tại");
        }
        if (request.getEmail() != null && userRepository.findByEmail(request.getEmail()).isPresent()) {
            throw new DuplicateResourceException("Email đã tồn tại");
        }

        User user = userMapper.toEntity(request);
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        User savedUser = userRepository.save(user);

        if (request.getRoles() != null) {
            for (String roleCode : request.getRoles()) {
                roleRepository.findByCode(roleCode).ifPresent(role -> {
                    userRoleRepository.save(UserRole.builder().userId(savedUser.getId()).roleId(role.getId()).build());
                });
            }
        }

        return mapToResponseWithRoles(savedUser);
    }

    @Override
    @Transactional
    public UserResponse updateUser(Long id, UpdateUserRequest request) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy người dùng"));

        userMapper.updateEntityFromRequest(request, user);
        User savedUser = userRepository.save(user);

        if (request.getRoles() != null) {
            userRoleRepository.deleteByUserId(savedUser.getId());
            for (String roleCode : request.getRoles()) {
                roleRepository.findByCode(roleCode).ifPresent(role -> {
                    userRoleRepository.save(UserRole.builder().userId(savedUser.getId()).roleId(role.getId()).build());
                });
            }
        }

        return mapToResponseWithRoles(savedUser);
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponse getUserById(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy người dùng"));
        return mapToResponseWithRoles(user);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserResponse> getAllUsers() {
        return userRepository.findAll().stream()
                .map(this::mapToResponseWithRoles)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void deleteUser(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy người dùng"));
        userRoleRepository.deleteByUserId(id);
        userRepository.delete(user);
    }

    private UserResponse mapToResponseWithRoles(User user) {
        UserResponse response = userMapper.toResponse(user);
        List<Long> roleIds = userRoleRepository.findByUserId(user.getId()).stream()
                .map(UserRole::getRoleId)
                .collect(Collectors.toList());
        Set<String> roles = roleRepository.findAllById(roleIds).stream()
                .map(Role::getCode)
                .collect(Collectors.toSet());
        response.setRoles(roles);
        return response;
    }
}

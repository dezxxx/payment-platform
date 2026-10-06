package com.dezxxx.person.rest;

import com.dezxxx.person.api.UsersApi;
import com.dezxxx.person.api.model.CreateUserRequestDto;
import com.dezxxx.person.api.model.UpdateUserRequestDto;
import com.dezxxx.person.api.model.UserResponseDto;
import com.dezxxx.person.service.UserService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

// The five endpoints. Implements the generated UsersApi: paths, statuses and
// @Valid come from the contract. No logic here - call UserService, wrap the answer.
@RestController
@RequiredArgsConstructor
public class UserController implements UsersApi {

    private final UserService userService;

    @Override
    public ResponseEntity<UserResponseDto> createUser(CreateUserRequestDto createUserRequestDto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.createUser(createUserRequestDto));
    }

    @Override
    public ResponseEntity<UserResponseDto> getUserById(UUID id) {
        return ResponseEntity.ok(userService.getUserById(id));
    }

    @Override
    public ResponseEntity<UserResponseDto> getUserByEmail(String email) {
        return ResponseEntity.ok(userService.getUserByEmail(email));
    }

    @Override
    public ResponseEntity<UserResponseDto> updateUser(UUID id, UpdateUserRequestDto updateUserRequestDto) {
        return ResponseEntity.ok(userService.updateUser(id, updateUserRequestDto));
    }

    @Override
    public ResponseEntity<Void> deleteUser(UUID id) {
        userService.deleteUser(id);
        return ResponseEntity.noContent().build();
    }
}

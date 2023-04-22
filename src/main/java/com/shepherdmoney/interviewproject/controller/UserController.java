package com.shepherdmoney.interviewproject.controller;

import com.shepherdmoney.interviewproject.model.User;
import com.shepherdmoney.interviewproject.repository.UserRepository;
import com.shepherdmoney.interviewproject.vo.request.CreateUserPayload;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;

@RestController
public class UserController {

    @Autowired
    UserRepository repository;

    /**
     * Creates a user entity with the given information.
     * @param payload Information with which to create user.
     * @return 200 response with id of created user.
     */
    @PutMapping("/user")
    public ResponseEntity<Integer> createUser(@RequestBody CreateUserPayload payload) {
        User newUser = new User();
        newUser.setName(payload.getName());
        newUser.setEmail(payload.getEmail());
        // New user has no credit cards initially
        newUser.setCreditCardList(Collections.emptyList());

        try {
            User savedUser = repository.save(newUser);
            return ResponseEntity.ok(savedUser.getId());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Deletes the user with the given userId if exists.
     * Returns 200 OK response of user exists and deletion was successful.
     * Returns 400 Bad Request if user does not exist.
     * Responses have message explaining response
     * @param userId Id of user to delete.
     * @return 200 response if deletion successful, 400 if user doesn't exist
     */
    @DeleteMapping("/user")
    public ResponseEntity<String> deleteUser(@RequestParam int userId) {
        try {
            // Check if user we are trying to delete exists before deleting
            if (repository.existsById(userId)) {
                repository.deleteById(userId);
                return ResponseEntity.ok("Successfully deleted one user.");
            } else {
                return ResponseEntity
                        .badRequest()
                        .body("User with ID does not exist.");
            }
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}

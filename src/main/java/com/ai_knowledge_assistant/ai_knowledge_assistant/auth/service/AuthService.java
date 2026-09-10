package com.ai_knowledge_assistant.ai_knowledge_assistant.auth.service;

import com.ai_knowledge_assistant.ai_knowledge_assistant.auth.entity.User;
import com.ai_knowledge_assistant.ai_knowledge_assistant.auth.model.AuthResponse;
import com.ai_knowledge_assistant.ai_knowledge_assistant.auth.model.LoginRequest;
import com.ai_knowledge_assistant.ai_knowledge_assistant.auth.model.RegisterRequest;
import com.ai_knowledge_assistant.ai_knowledge_assistant.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.xmlbeans.impl.xb.xsdschema.Attribute;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.HashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;

    public AuthResponse register(RegisterRequest request){

        if(userRepository.existsByEmail(request.getEmail())){
            throw new IllegalArgumentException("Email already exist !");
        }

        User user = User.builder()
                .name(request.getName())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .role(User.Role.USER)
                .build();
        userRepository.saveAndFlush(user);
        log.info("new User Registered: {}",request.getEmail());

        String token = jwtService.generateToken(new HashMap<>(),user);
        return  AuthResponse.builder()
                .token(token)
                .name(user.getName())
                .email(user.getEmail())
                .role(user.getRole().name())
                .build();
    }

    public AuthResponse login(LoginRequest request){

        authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(
                request.getEmail(),request.getPassword()
        ));

        if(!userRepository.existsByEmail(request.getEmail())){
            throw new IllegalArgumentException("Email not Registered");
        }

        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow();
        log.info("User logged in: {}", request.getEmail());
        String token = jwtService.generateToken(new HashMap<>(),user);
        return  AuthResponse.builder()
                .token(token)
                .name(user.getName())
                .email(user.getEmail())
                .role(user.getRole().name())
                .build();


    }


}
